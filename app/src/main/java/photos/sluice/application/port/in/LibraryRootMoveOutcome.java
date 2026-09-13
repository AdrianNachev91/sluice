package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * How a library-root move ended.
 *
 * <p>A variant per ending rather than one record with fields nobody set. Each carries what its own
 * message needs and nothing else. A cancelled copy is the one where the root did not move. It is a
 * variant rather than a boolean, so a caller reporting success has to say which success it means.
 */
public sealed interface LibraryRootMoveOutcome {

    /**
     * The old library was copied into the new one and the root now names the new one. The old
     * folder is untouched and still holds everything, which is what a caller tells the user before
     * they delete anything by hand.
     *
     * <p>A destination that already held photos keeps them, and an arriving copy that is not
     * already there lands beside them under a suffixed name. So moving into an occupied folder
     * duplicates rather than overwrites. That is the accepted direction here, the same one the
     * fresh-index resolution takes: a duplicate is something a later sift can resolve, and an
     * overwrite is not.
     *
     * @param filesCopied int how many files were written into the new library
     * @param filesFound int how many the old library held; any above the copied count were already
     *        in the new folder and were left as they were
     * @param copiedFrom {@link Path} the folder those files came from, which still holds every one
     *        of them. Carried so a report can name it: nothing here removes it, and whoever asked
     *        for the move is the one who decides whether it goes
     */
    record CopiedAndMoved(int filesCopied, int filesFound, Path copiedFrom)
            implements LibraryRootMoveOutcome {
    }

    /**
     * The copy stopped because cancellation was asked for, so the root did not move. The settings
     * still name the old library and the index is still true of it. What was copied stays where it
     * landed, which is what lets a second attempt continue the copy rather than start it again.
     *
     * @param filesCopied int how many files were written before it stopped
     * @param filesFound int how many the old library held when the copy started
     * @param copiedInto {@link Path} the folder those files were written into
     */
    record CopyCancelled(int filesCopied, int filesFound, Path copiedInto) implements LibraryRootMoveOutcome {
    }

    /**
     * The root moved and the hash index was filed aside, so duplicate detection starts over.
     *
     * @param previousIndexFiledAt {@link Path} where the old index went, null when there was none
     *         to file
     */
    record MovedWithFreshIndex(@Nullable Path previousIndexFiledAt) implements LibraryRootMoveOutcome {
    }
}
