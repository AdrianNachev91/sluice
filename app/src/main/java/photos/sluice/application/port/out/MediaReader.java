package photos.sluice.application.port.out;

import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The inspect-only half of media storage: what is on disk, and what it looks like. Nothing here
 * moves, copies, writes, or deletes anything.
 *
 * <p>{@link MediaStore} extends this with the mutating half. A collaborator that only ever needs to
 * look at the filesystem takes this type instead. A mutation is then not merely discouraged there
 * but unavailable: the method does not exist on the type it holds. That makes a read-only guarantee
 * a compile-time property rather than a documented intention.
 */
public interface MediaReader {

    /**
     * Every regular file under root, recursively, as absolute paths. Order is unspecified.
     *
     * @param root {@link Path} the directory to scan
     * @return a {@link List} of {@link Path}, every regular file found, as absolute paths
     */
    List<Path> listFiles(Path root);

    /**
     * The same walk, carrying on past a directory it is refused, and answering with both halves.
     *
     * <p>For a tree the app does not own, where a refusal is ordinary: a Windows-formatted card
     * carries a System Volume Information directory nobody may read.
     *
     * @param root {@link Path}
     * @return {@link Walk} the files reached and the places refused
     */
    Walk listFilesTolerating(Path root);

    /**
     * What a tolerant walk found.
     *
     * @param files a {@link List} of {@link Path} every regular file it reached, as absolute paths
     * @param unreadablePlaces a {@link List} of {@link Path}, deepest first
     */
    record Walk(List<Path> files, List<Path> unreadablePlaces) {

        /**
         * Defensively copies the mutable collection components.
         *
         * @param files a {@link List} of {@link Path}
         * @param unreadablePlaces a {@link List} of {@link Path}
         */
        public Walk {
            files = List.copyOf(files);
            unreadablePlaces = List.copyOf(unreadablePlaces);
        }
    }

    /**
     * Every immediate subdirectory of root, non-recursive, as absolute paths. Order is unspecified.
     * A file sitting directly in root, rather than in one of its subdirectories, is not named here.
     * Only directories are. root itself must exist. A caller checking {@link #exists} first is what
     * separates a genuinely empty root from a missing one.
     *
     * <p>Deliberately shallow, unlike {@link #listFiles}. A caller enumerating many independent
     * subdirectories lists them at this level first, each one worth reading on its own. It then
     * reads each subdirectory inside its own guard. One subdirectory's read failure then costs one
     * entry rather than the whole enumeration.
     *
     * @param root {@link Path} the directory to list
     * @return a {@link List} of {@link Path}, every immediate subdirectory found, as absolute paths
     */
    List<Path> listChildDirectories(Path root);

    /**
     * When path was last modified. Used where a directory's own age is the signal, e.g. a waiting
     * cull job's prep dir. A media file's capture date comes from DateSource instead.
     *
     * @param path {@link Path} the file or directory to check
     * @return {@link Instant} the last-modified instant
     */
    Instant lastModifiedTime(Path path);

    /**
     * Checks whether a path exists.
     *
     * @param path {@link Path} the path to check
     * @return boolean true if the path exists
     */
    boolean exists(Path path);

    /**
     * The real, symlink- and junction-free form of path, when a directory can be confirmed there.
     * Empty when none can be.
     *
     * <p>One call answers both halves of checking a configured folder root. Whether it is there at
     * all, and what it really is once the filesystem has followed every name. Two roots configured
     * under different names that reach one directory come back equal here, which is what lets a
     * caller compare them as plain paths.
     *
     * <p>Empty means no directory could be confirmed there. Usually nothing is there. It can also
     * mean the filesystem would not say what is: a permission denial on the way down, or a share
     * that has gone away, both answer the same way as an absence. A caller cannot tell those apart
     * from here.
     *
     * <p>The throw covers the narrower case where a directory was confirmed and then could not be
     * resolved. So a throw does mean the filesystem refused to answer, while empty does not rule
     * that out.
     *
     * @param path {@link Path} the path to resolve
     * @return an {@link Optional} of {@link Path}, the real directory, or empty if none was confirmed
     * @throws UncheckedIOException if a directory is there and resolving it failed
     */
    Optional<Path> realDirectory(Path path);

    /**
     * The same resolution for a path that is not a directory.
     *
     * <p>A caller holding both a folder and a file has to compare them as plain paths, so both
     * have to be spelled the same way. Windows gives the same file a short name and a long one,
     * and a link gives it a second name anywhere.
     *
     * @param path {@link Path} the path to resolve
     * @return {@link Path} the resolved path
     * @throws UncheckedIOException if it could not be resolved, which includes it not being there
     */
    Path realFile(Path path);

    /**
     * Reads a file's size.
     *
     * @param path {@link Path} the file to measure
     * @return long the file size in bytes
     */
    long size(Path path);

    /**
     * Every line of file, in order, or empty if file does not exist. This is the read-side
     * counterpart to {@link MediaStore#appendLine}, for resuming from a crash-safety log written
     * one line per completed step.
     *
     * <p>Text is UTF-8. A file whose bytes are not valid UTF-8 must throw an
     * {@link UncheckedIOException} caused by a {@link CharacterCodingException}, never silently
     * substitute replacement characters. That exact cause is what separates damaged content from
     * every other I/O failure. A caller may degrade around damaged content. A locked or
     * permission-denied file must stay loud rather than read as an empty log.
     *
     * @param file {@link Path} the file to read
     * @return a {@link List} of {@link String}, every line in the file, or empty if the file does not exist
     */
    List<String> readLines(Path file);
}
