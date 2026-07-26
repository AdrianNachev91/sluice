package photos.sluice.application.port.out;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public interface MediaStore {

    /**
     * Every regular file under root, recursively, as absolute paths. Order is unspecified.
     *
     * @param root {@link Path} the directory to scan
     * @return a {@link List} of {@link Path}, every regular file found, as absolute paths
     */
    List<Path> listFiles(Path root);

    /**
     * When path was last modified. Used where a directory's own age is the signal (e.g. a waiting
     * cull job's prep dir), not a media file's capture date - that comes from DateSource instead.
     *
     * @param path {@link Path} the file or directory to check
     * @return {@link Instant} the last-modified instant
     */
    Instant lastModifiedTime(Path path);

    /**
     * Moves a file into a destination directory.
     *
     * @param source {@link Path} the file to move
     * @param destDir {@link Path} the destination directory
     * @return {@link Path} the path the file was moved to
     */
    Path move(Path source, Path destDir);

    /**
     * The exact free path move(source, destDir) would land on, without performing the move - the
     * first name not already occupied under destDir (source's own leaf, then " (2)", " (3)", ...).
     * Exists so a caller can durably record where a decision is headed BEFORE moving it, closing the
     * crash window between "decided the destination" and "the move actually happened."
     *
     * @param source {@link Path} the file that would be moved
     * @param destDir {@link Path} the destination directory
     * @return {@link Path} the free destination path that a move would land on
     */
    Path resolveDestination(Path source, Path destDir);

    /**
     * Moves source to exactly destination - no collision handling of its own, since the caller is
     * expected to have already reserved that exact path via resolveDestination.
     *
     * @param source {@link Path} the file to move
     * @param destination {@link Path} the exact destination path
     * @return {@link Path} the destination path
     */
    Path moveTo(Path source, Path destination);

    /**
     * Copies a file into a destination directory.
     *
     * @param source {@link Path} the file to copy
     * @param destDir {@link Path} the destination directory
     * @return {@link Path} the path the copy was written to
     */
    Path copy(Path source, Path destDir);

    /**
     * Deletes a file.
     *
     * @param path {@link Path} the file to delete
     */
    void delete(Path path);

    /**
     * Ensures a directory exists, creating it (and any parents) if needed.
     *
     * @param dir {@link Path} the directory to ensure exists
     */
    void ensureDirectory(Path dir);

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
     * Appends a line to a file, creating it if needed.
     *
     * @param file {@link Path} the file to append to
     * @param line {@link String} the line to append
     */
    void appendLine(Path file, String line);

    /**
     * Creates file with exactly this content, replacing whatever was there before - unlike
     * appendLine, which accumulates. For a file meant to hold a single, self-contained record
     * (rewritten wholesale whenever it changes) rather than a growing log of entries.
     *
     * @param file {@link Path} the file to write
     * @param content {@link String} the full content to write
     */
    void write(Path file, String content);

    /**
     * Every line of file, in order, or empty if file does not exist - the read-side counterpart to
     * appendLine, for resuming from a crash-safety log written one line per completed step.
     *
     * @param file {@link Path} the file to read
     * @return a {@link List} of {@link String}, every line in the file, or empty if the file does not exist
     */
    List<String> readLines(Path file);

    /**
     * Removes every subdirectory under root left empty of all files (root itself is never a
     * candidate). A directory holding a genuine non-media leftover is left in place.
     *
     * @param root {@link Path} the directory whose empty subdirectories are removed
     */
    void removeEmptyDirectories(Path root);

    /**
     * Removes dir itself, and everything under it, provided dir contains no file anywhere in its
     * subtree. A no-op (dir is left untouched, including any empty subdirectories) if even one
     * file remains anywhere below it.
     *
     * @param dir {@link Path} the directory to remove if empty of files
     */
    void removeIfEmptyOfFiles(Path dir);
}
