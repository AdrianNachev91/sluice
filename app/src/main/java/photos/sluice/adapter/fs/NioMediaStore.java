package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.job.CancellationSignal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link MediaStore} implementation backed directly by {@code java.nio.file}. It provides every
 * file-system primitive the domain needs - listing, moving, copying, deleting, collision-free
 * renaming, and pruning empty directories - so higher layers never touch NIO directly.
 *
 * <p>Flowchart and scenario table: {@code app/docs/design/adapter/fs/media-store.md}.
 */
@Component
public class NioMediaStore implements MediaStore {

    private static final String PART_SUFFIX = MediaStore.INCOMPLETE_TRANSFER_SUFFIX;

    // How much of a file moves between two chances to give up on it. At the ~200 MB/s a local disk
    // gives, that is a stop answered within about 5ms. A large photo still crosses in a few dozen
    // reads rather than thousands.
    private static final int TRANSFER_BLOCK_BYTES = 1024 * 1024;

    /**
     * Lists every regular file under a directory tree, recursively.
     *
     * <p>A part file left by a transfer that never landed is answered like any other, since a caller
     * clearing a directory out has to be handed it. {@link MediaStore#isIncompleteTransfer} is what
     * tells one apart from media.
     *
     * @param root {@link Path} directory to walk
     * @return a {@link List} of {@link Path}, all regular files found under root
     */
    @Override
    public List<Path> listFiles(final Path root) {
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
    }

    /**
     * Walks a tree, keeping what it reached and noting every place it was refused.
     *
     * @param root {@link Path}
     * @return {@link Walk} the files reached and the places refused
     */
    @Override
    public Walk listFilesToleratingRefusals(final Path root) {
        final List<Path> files = new ArrayList<>();
        final List<Path> unreadable = new ArrayList<>();
        try {
            // walkFileTree rather than walk. A stream reports a refusal by throwing out of the
            // terminal operation, which ends the whole walk. This one is asked about each failure
            // as it happens and answers CONTINUE, so a denied directory costs that directory.
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                    unreadable.add(file);
                    return FileVisitResult.CONTINUE;
                }

                // Fires for a directory that could be entered and then failed partway through.
                @Override
                public FileVisitResult postVisitDirectory(final Path dir, final @Nullable IOException e) {
                    if (e != null) {
                        unreadable.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            // Only root itself failing reaches here, since every failure below it was answered
            // above.
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
        return new Walk(files, unreadable);
    }

    /**
     * Lists every immediate subdirectory of a directory, non-recursive.
     *
     * @param root {@link Path} directory to list
     * @return a {@link List} of {@link Path}, root's immediate subdirectories
     */
    @Override
    public List<Path> listChildDirectories(final Path root) {
        try (final Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list " + root, e);
        }
    }

    /**
     * Reads a file's last-modified timestamp.
     *
     * @param path {@link Path} file to check
     * @return {@link Instant} the file's last-modified instant
     */
    @Override
    public Instant lastModifiedTime(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read last modified time of " + path, e);
        }
    }

    /**
     * Computes the collision-free destination path a move would use, without moving anything.
     *
     * @param source {@link Path} file that would be moved
     * @param destDir {@link Path} destination directory
     * @return {@link Path} the resolved, not-yet-existing destination path
     */
    @Override
    public Path resolveDestination(final Path source, final Path destDir) {
        return resolveCollision(destDir, source.getFileName().toString());
    }

    /**
     * Moves a file into a destination directory, abandoning it if the signal escalates.
     *
     * @param source {@link Path} file to move
     * @param destDir {@link Path} destination directory
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param transferProgress {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the file's final path after the move
     */
    @Override
    public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                     final TransferProgress transferProgress) {
        return this.moveTo(source, this.resolveDestination(source, destDir), stop, transferProgress);
    }

    /**
     * Moves a file to an exact destination path, abandoning it if the signal escalates.
     *
     * <p>A rename within one volume is instant and has nothing to interrupt, so it stays a rename
     * and ignores the escalation. A move across file stores is a full read and write instead. That
     * one becomes an interruptible copy, with the source deleted once the copy has landed.
     *
     * <p>Which of the two it is, is decided up front rather than by catching a failure.
     * {@code Files.move} does the cross-volume copy itself, silently and uninterruptibly, so
     * nothing is thrown to catch. {@code ATOMIC_MOVE} would throw, and it also overwrites an
     * occupied destination on POSIX, which is the one thing this app never does.
     *
     * @param source {@link Path} file to move
     * @param destination {@link Path} exact target path
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param transferProgress {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the destination path
     */
    @Override
    public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                       final TransferProgress transferProgress) {
        this.ensureDirectory(destination.getParent());
        if (!this.sameFileStore(source, destination.getParent())) {
            this.copyTo(source, destination, stop, transferProgress);
            this.delete(source);
            return destination;
        }
        try {
            Files.move(source, destination);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to move " + source + " to " + destination, e);
        }
        return destination;
    }

    /**
     * Copies a file into a destination directory, abandoning it if the signal escalates.
     *
     * @param source {@link Path} file to copy
     * @param destDir {@link Path} destination directory
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param transferProgress {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the path of the copy
     */
    @Override
    public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                     final TransferProgress transferProgress) {
        return this.copyTo(source, this.prepareDestination(source, destDir), stop, transferProgress);
    }

    /**
     * Copies a file to an exact destination path, abandoning it if the signal escalates.
     *
     * @param source {@link Path} file to copy
     * @param destination {@link Path} exact target path, which must be free
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param transferProgress {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the destination path
     */
    @Override
    public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                       final TransferProgress transferProgress) {
        this.ensureDirectory(destination.getParent());
        interruptibleCopy(source, destination, stop, transferProgress);
        return destination;
    }

    /**
     * Deletes a single file.
     *
     * @param path {@link Path} file to delete
     */
    @Override
    public void delete(final Path path) {
        try {
            Files.delete(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    /**
     * Creates a directory and any missing parent directories.
     *
     * @param dir {@link Path} directory to create
     */
    @Override
    public void ensureDirectory(final Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create directory " + dir, e);
        }
    }

    /**
     * Checks whether a path exists.
     *
     * @param path {@link Path} path to check
     * @return boolean true if the path exists
     */
    @Override
    public boolean exists(final Path path) {
        return Files.exists(path);
    }

    /**
     * Reports whether a directory is there, letting a refusal through as a throw.
     *
     * @param path {@link Path} path to check
     * @return boolean true if a directory is there, false if nothing is
     */
    @Override
    public boolean directoryExists(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isDirectory();
        } catch (final NoSuchFileException e) {
            return false;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read what is at " + path, e);
        }
    }

    /**
     * Resolves a path to its real directory form, or reports that no directory is there.
     *
     * <p>The directory check runs first, so a path naming nothing reports absence rather than
     * failing. A failure after that check has passed is a real one and stays loud.
     *
     * @param path {@link Path} path to resolve
     * @return an {@link Optional} of {@link Path}, the real directory, or empty if there is none
     */
    @Override
    public Optional<Path> realDirectory(final Path path) {
        if (!Files.isDirectory(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(path.toRealPath());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to resolve the real path of " + path, e);
        }
    }

    /**
     * Resolves a path that is not a directory to its real form.
     *
     * @param path {@link Path} path to resolve
     * @return {@link Path} the resolved path
     */
    @Override
    public Path realFile(final Path path) {
        try {
            return path.toRealPath();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to resolve the real path of " + path, e);
        }
    }

    /**
     * Reads a file's size in bytes.
     *
     * @param path {@link Path} file to check
     * @return long the file size in bytes
     */
    @Override
    public long size(final Path path) {
        try {
            return Files.size(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read size of " + path, e);
        }
    }

    /**
     * Appends a line of text to a file, creating it if necessary.
     *
     * @param file {@link Path} file to append to
     * @param line {@link String} line of text to append
     */
    @Override
    public void appendLine(final Path file, final String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to append line to " + file, e);
        }
    }

    /**
     * Writes text to a file, replacing any existing content.
     *
     * @param file {@link Path} file to write
     * @param content {@link String} content to write
     */
    @Override
    public void write(final Path file, final String content) {
        try {
            Files.writeString(file, content + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    /**
     * Reads all lines from a file, or an empty list if it does not exist.
     *
     * <p>The explicit UTF-8 charset is what holds the port's decode contract. A strict decoder
     * reports undecodable bytes rather than substituting replacement characters, so damaged
     * content arrives as a {@link CharacterCodingException} cause instead of passing for a
     * successful read.
     *
     * @param file {@link Path} file to read
     * @return a {@link List} of {@link String}, the file's lines, or an empty list if the file is
     * missing
     */
    @Override
    public List<String> readLines(final Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read lines from " + file, e);
        }
    }

    /**
     * Removes every empty directory under a root, deepest first, collapsing nested chains.
     *
     * @param root {@link Path} directory tree to clean up
     */
    @Override
    public void removeEmptyDirectories(final Path root) {
        final List<Path> directories;
        try (final Stream<Path> walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory).filter(dir -> !dir.equals(root)).toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
        // Deepest directories first. A chain of nested empty directories then collapses bottom-up
        // in this single pass. By the time a shallower directory is checked, any empty child it
        // had has already been removed, leaving it genuinely empty too if nothing else remains.
        directories.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                .forEach(this::deleteIfEmptyOfFiles);
    }

    /**
     * Removes a directory, and its empty subdirectories, only if it holds no files anywhere.
     *
     * @param dir {@link Path} directory to remove if empty of files
     */
    @Override
    public void removeIfEmptyOfFiles(final Path dir) {
        if (!Files.exists(dir) || containsAnyFile(dir)) {
            return;
        }
        // containsAnyFile already checked the whole subtree, so every subdirectory below dir is
        // known empty of files too. This prunes them bottom-up, leaving dir itself with no
        // children, at which point it is safe to remove too.
        this.removeEmptyDirectories(dir);
        this.deleteIfEmptyOfFiles(dir);
    }

    /**
     * Deletes a directory if it exists and holds no files anywhere below it.
     *
     * @param dir {@link Path} directory to delete if empty of files
     */
    private void deleteIfEmptyOfFiles(final Path dir) {
        if (!Files.exists(dir) || containsAnyFile(dir)) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to remove empty directory " + dir, e);
        }
    }

    /**
     * Checks whether any regular file exists anywhere under a directory.
     *
     * @param dir {@link Path} directory to inspect
     * @return boolean true if a regular file exists anywhere below dir
     */
    private static boolean containsAnyFile(final Path dir) {
        try (final Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to inspect " + dir, e);
        }
    }

    /**
     * Copies source into a part file beside destination, then renames it into place.
     *
     * <p>A part file rather than the destination itself. An abandoned copy then cannot leave a
     * truncated photo under a name a later scan would read.
     *
     * <p>The rename at the end is within one directory, so it is a rename on every platform. It
     * carries no REPLACE_EXISTING, which is what keeps an occupied destination a failure rather
     * than an overwrite.
     *
     * <p>Timestamps are restored by hand because a block-by-block copy preserves nothing. The
     * date-resolution chain falls back to mtime, so a copy that lost it would be filed under the
     * date it was copied. Access and creation times ride along on the same restore call.
     *
     * <p>Progress is reported per block written, so a file produces one reading per megabyte. Too
     * coarse to be worth throttling.
     *
     * @param source {@link Path} file to read
     * @param destination {@link Path} exact target path
     * @param stop {@link CancellationSignal} asked between blocks
     * @param transferProgress {@link TransferProgress} told how much has been written
     */
    private static void interruptibleCopy(final Path source, final Path destination,
                                          final CancellationSignal stop,
                                          final TransferProgress transferProgress) {
        final Path part = destination.resolveSibling(destination.getFileName() + PART_SUFFIX);
        try {
            final BasicFileAttributes sourceTimes = Files.readAttributes(source, BasicFileAttributes.class);
            final long size = sourceTimes.size();
            try (final InputStream in = Files.newInputStream(source);
                 final OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                final byte[] buffer = new byte[TRANSFER_BLOCK_BYTES];
                long written = 0;
                int read = in.read(buffer);
                while (read >= 0) {
                    if (stop.isAbandonRequested()) {
                        throw new AbandonedMidBlockException();
                    }
                    out.write(buffer, 0, read);
                    written += read;
                    transferProgress.moved(written, size);
                    read = in.read(buffer);
                }
            }
            Files.getFileAttributeView(part, BasicFileAttributeView.class)
                    .setTimes(sourceTimes.lastModifiedTime(), sourceTimes.lastAccessTime(),
                            sourceTimes.creationTime());
            Files.move(part, destination);
        } catch (final AbandonedMidBlockException e) {
            deleteIfPresent(part);
            throw new TransferAbandonedException(source);
        } catch (final IOException e) {
            deleteIfPresent(part);
            throw new UncheckedIOException("Failed to copy " + source + " to " + destination, e);
        }
    }

    /**
     * Whether two paths sit on the same file store, which is what decides if a move is a rename.
     *
     * <p>A store that cannot be read answers false, so the move takes the copy-and-delete route.
     * That route is correct on one volume too, merely slower. Guessing the other way would hand
     * {@code Files.move} a cross-volume copy nothing can stop.
     *
     * <p>Package-private so a test can override it. Nothing in one can conjure a second file store,
     * so the cross-store branch would otherwise be reachable only on a machine with the right
     * volumes attached.
     *
     * @param source {@link Path} the file being moved
     * @param destinationDir {@link Path} the directory it is moving into
     * @return boolean true when both are known to sit on one store
     */
    boolean sameFileStore(final Path source, final Path destinationDir) {
        try {
            return Files.getFileStore(source).equals(Files.getFileStore(destinationDir));
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * Deletes a file that may or may not be there, and says nothing either way.
     *
     * <p>It only ever clears a part file on a path already reporting something else. Letting a
     * failure here through would replace the reason the caller is being told with a reason about
     * housekeeping.
     *
     * @param path {@link Path} the file to remove if it exists
     */
    private static void deleteIfPresent(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {}
    }

    /**
     * Ensures the destination directory exists and resolves a collision-free path within it.
     *
     * @param source {@link Path} file that will be copied
     * @param destDir {@link Path} destination directory
     * @return {@link Path} a collision-free destination path
     */
    private Path prepareDestination(final Path source, final Path destDir) {
        this.ensureDirectory(destDir);
        return resolveCollision(destDir, source.getFileName().toString());
    }

    /**
     * First try the original leaf name, then append " (2)", " (3)", ... before the extension
     * until a free path is found. Never overwrites an existing file.
     *
     * @param destDir {@link Path} destination directory
     * @param leaf {@link String} file name to place in destDir
     * @return {@link Path} a path in destDir that does not currently exist
     */
    private static Path resolveCollision(final Path destDir, final String leaf) {
        Path candidate = destDir.resolve(leaf);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        final String base = baseName(leaf);
        final String extension = extension(leaf);
        int n = 2;
        do {
            candidate = destDir.resolve(base + " (" + n + ")" + extension);
            n++;
        } while (Files.exists(candidate));
        return candidate;
    }

    /**
     * Extracts the file name without its extension.
     *
     * @param leaf {@link String} file name
     * @return {@link String} the file name minus its extension
     */
    private static String baseName(final String leaf) {
        final int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? leaf : leaf.substring(0, dot);
    }

    /**
     * Extracts a file name's extension, including the leading dot.
     *
     * @param leaf {@link String} file name
     * @return {@link String} the extension including its leading dot, or empty string if none
     */
    private static String extension(final String leaf) {
        final int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? "" : leaf.substring(dot);
    }


    /**
     * Unwinds the copy loop out of its try-with-resources, so both streams are closed by the time
     * the part file is deleted. Windows refuses to delete a file it still holds open.
     */
    private static final class AbandonedMidBlockException extends IOException {
    }
}
