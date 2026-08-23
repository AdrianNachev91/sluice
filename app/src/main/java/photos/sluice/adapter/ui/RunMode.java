package photos.sluice.adapter.ui;

/**
 * The five kinds of work the dashboard can start, as the buttons offering them.
 *
 * <p>These are the user's words rather than the engine's. What the screen calls sifting is what the
 * code calls culling, and what it calls moving to the library is what the code calls committing.
 *
 * <p>An enum rather than the button ids as strings. The screen hands one of these back to say which
 * button was pressed, and a value that came from this list cannot be one nothing recognises. A
 * lookup on a string would need an answer for a spelling no button could produce.
 */
public enum RunMode {

    /** Dates what is in the Inbox and files it into Sorted. */
    SORT,

    /** Has a vision provider look at what is in Sorted and sort it into the photo categories. */
    SIFT,

    /** Moves what is in Sorted into the library. */
    MOVE_TO_LIBRARY,

    /** Sorts and then sifts, in one go. */
    CURATE,

    /** Moves what is left in a Review folder into the library. */
    RESCUE
}
