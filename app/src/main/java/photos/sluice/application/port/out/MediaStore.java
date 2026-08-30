package photos.sluice.application.port.out;

import photos.sluice.domain.job.CancellationSignal;

import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Media storage as a whole: the inspect-only surface of {@link MediaReader}, plus everything that
 * changes what is on disk. Every move, copy, write, and delete the app performs goes through here.
 *
 * <p>A collaborator that never mutates should take {@link MediaReader} instead, so the mutators
 * below are simply not reachable from it.
 *
 * <p>Every transfer below takes a {@link CancellationSignal}, asked wherever bytes actually move.
 * So a caller can give up on one large file instead of waiting it out. A move that turns out to be
 * a rename has no bytes to interrupt and ignores the signal entirely, which leaves the loop around
 * it as the only thing that stops. Answering the escalation throws
 * {@link TransferAbandonedException}, and the destination is not left holding a prefix of the
 * source under its own name. A caller with nothing to stop passes {@link CancellationSignal#NEVER}.
 *
 * <p>Every transfer also takes a {@link TransferProgress}, told at intervals how much of the file
 * has been written. It is the only reading that moves while one large file crosses, since the
 * caller's own count cannot advance until that file lands. A rename reports nothing through it,
 * having written no bytes. A caller with nothing watching passes {@link TransferProgress#NONE}.
 *
 * <p>One form each rather than an overload beside it taking neither. An overload lets an
 * implementation override the form nobody calls, which compiles, reads as complete, and silently
 * does nothing.
 */
public interface MediaStore extends MediaReader {

    /** The suffix an implementation gives a transfer it has not finished writing. */
    String INCOMPLETE_TRANSFER_SUFFIX = ".sluice-part";

    /**
     * Whether a path names a transfer that never landed, rather than a file of the user's.
     *
     * <p>{@link #listFiles} answers these like anything else, because a caller clearing a directory
     * has to be handed them. A caller that would hash, move or index what it gets back asks this
     * first. The file holds a prefix of a photo, and nothing downstream can tell by reading it.
     *
     * @param file {@link Path} a file a walk turned up
     * @return boolean true where the file is a half-written transfer
     */
    static boolean isIncompleteTransfer(final Path file) {
        return file.getFileName().toString().endsWith(INCOMPLETE_TRANSFER_SUFFIX);
    }

    /**
     * Moves a file into a destination directory.
     *
     * @param source {@link Path} the file to move
     * @param destDir {@link Path} the destination directory
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param watching {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the path the file was moved to
     * @throws TransferAbandonedException if stop escalated before the move finished
     */
    Path move(Path source, Path destDir, CancellationSignal stop, TransferProgress watching);

    /**
     * The exact free path move(source, destDir) would land on, without performing the move. That is
     * the first name not already occupied under destDir: source's own leaf, then " (2)", " (3)",
     * and so on. Exists so a caller can durably record where a decision is headed BEFORE moving it.
     * That closes the crash window between "decided the destination" and "the move actually
     * happened."
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
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param watching {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the destination path
     * @throws TransferAbandonedException if stop escalated before the move finished
     */
    Path moveTo(Path source, Path destination, CancellationSignal stop, TransferProgress watching);

    /**
     * Copies a file into a destination directory.
     *
     * @param source {@link Path} the file to copy
     * @param destDir {@link Path} the destination directory
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param watching {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the path the copy was written to
     * @throws TransferAbandonedException if stop escalated before the copy finished
     */
    Path copy(Path source, Path destDir, CancellationSignal stop, TransferProgress watching);

    /**
     * Copies source to exactly destination.
     *
     * @param source {@link Path} the file to copy
     * @param destination {@link Path} must be free
     * @param stop {@link CancellationSignal} asked while the bytes are moving
     * @param watching {@link TransferProgress} told how far the bytes have got
     * @return {@link Path} the destination path
     * @throws UncheckedIOException if destination is already taken, or the copy fails
     * @throws TransferAbandonedException if stop escalated before the copy finished
     */
    Path copyTo(Path source, Path destination, CancellationSignal stop, TransferProgress watching);

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
