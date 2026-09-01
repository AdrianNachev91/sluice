package photos.sluice.application.port.in;

import photos.sluice.domain.rescue.RescueSummary;

/**
 * The use case for moving what somebody has left in one waiting folder back into Sorted. A sift and
 * a move to the library can both reach it again there. It is how such a folder gets emptied and
 * removed once what remains in it has been judged worth keeping.
 */
public interface RescueUseCase {

    /**
     * Moves every media file left in one folder back into Sorted.
     *
     * <p>One does not move. A file whose own bytes are already at the destination is deleted from
     * the folder instead, and counted apart from what moved.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder's path below that root, as
     *     {@link ReviewListing.Folder#name()} spells it
     * @return {@link RescueSummary} summary of the rescue run
     * @throws NoteIsNotTextException if a note in the folder holds something other than text, which
     *     stops the run rather than costing those photos their month
     */
    RescueSummary rescue(RescueRoot root, String folder);
}
