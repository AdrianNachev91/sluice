package photos.sluice.application.port.in;

/**
 * A root a rescue can move photos out of, which is every root the review screen lists.
 *
 * <p>An enum of its own rather than {@link ReviewListing.Root}. What a rescue takes is then this
 * port's decision rather than a screen's.
 */
public enum RescueRoot {

    /** The categories somebody configured, plus what a sort would not put in Sorted. */
    REVIEW,

    /** What a sift could not render a judgeable tile for, moved out of Sorted to get there. */
    UNREVIEWABLE,

    /**
     * Groups of near-copies, whose keeper is the one photo here that is already back in Sorted.
     *
     * <p>Its copy in the group is deleted rather than moved, once its bytes are found at the
     * destination. Every other member moves like anything else.
     */
    DUPLICATES
}
