package photos.sluice.application.port.out;

import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

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
