package photos.sluice.adapter.fs;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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

    /**
     * Lists every regular file under a directory tree, recursively.
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
     * Moves a file into a destination directory, resolving any name collision first.
     *
     * @param source {@link Path} file to move
     * @param destDir {@link Path} destination directory
     * @return {@link Path} the file's final path after the move
     */
    @Override
    public Path move(final Path source, final Path destDir) {
        return this.moveTo(source, this.resolveDestination(source, destDir));
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
     * Moves a file to an exact destination path, creating parent directories as needed.
     *
     * @param source {@link Path} file to move
     * @param destination {@link Path} exact target path
     * @return {@link Path} the destination path
     */
    @Override
    public Path moveTo(final Path source, final Path destination) {
        this.ensureDirectory(destination.getParent());
        try {
            Files.move(source, destination);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to move " + source + " to " + destination, e);
        }
        return destination;
    }

    /**
     * Copies a file into a destination directory, preserving attributes and resolving collisions.
     *
     * @param source {@link Path} file to copy
     * @param destDir {@link Path} destination directory
     * @return {@link Path} the path of the copy
     */
    @Override
    public Path copy(final Path source, final Path destDir) {
        final Path dest = this.prepareDestination(source, destDir);
        try {
            // Unlike move (a rename, where attributes ride along for free), a plain copy is not
            // required to preserve timestamps - and the date-resolution fallback chain relies on
            // mtime, so a copied file must keep its original one.
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to copy " + source + " to " + dest, e);
        }
        return dest;
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
     * @param file {@link Path} file to read
     * @return a {@link List} of {@link String}, the file's lines, or an empty list if the file is
     *     missing
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
        // Every subdirectory below dir is now known empty of files too (containsAnyFile already
        // checked the whole subtree), so this prunes all of them bottom-up, leaving dir itself
        // with no children - at which point it is safe to remove too.
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
}
