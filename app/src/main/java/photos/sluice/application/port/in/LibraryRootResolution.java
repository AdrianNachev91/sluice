package photos.sluice.application.port.in;

/**
 * What to do about the hash index when the library root moves.
 *
 * <p>The index is keyed to the working root and its rows describe the library. Moving the library
 * root alone leaves it vouching for files that are in the old folder. That index is what authorizes
 * deleting an Inbox file as a copy already safe elsewhere. So a stale one means the library the user
 * is now filing into never receives a photo, with nothing saying so.
 *
 * <p>There is no third option that leaves the index alone. Both of these remove the stale state
 * rather than guarding it. That is why the caller has to name one instead of the app choosing.
 */
public enum LibraryRootResolution {

    /**
     * Copy the old library into the new one first, and only move the root once that has finished.
     * The index is then true again, because every hash it records is in the current library.
     */
    COPY_AND_KEEP_INDEX,

    /**
     * File the index aside and start an empty one. Photos already in the new library are unknown to
     * Sluice unless it put them there, so a re-import can produce duplicates. Duplication is the
     * accepted failure direction here. Silent loss is not.
     */
    START_A_FRESH_INDEX
}
