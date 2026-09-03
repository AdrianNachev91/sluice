package photos.sluice.adapter.ui;

/**
 * What the sidebar says is happening on the Dashboard, for a reader who is looking at another
 * screen.
 *
 * <p>Two states rather than one, because they ask for different things. {@code RUNNING} is work
 * under way and needs nothing from anybody. {@code FINISHED} is an answer nobody has read.
 */
public enum DashboardMark {

    /** Nothing is happening there, so the entry carries no mark at all. */
    NONE,

    /** A job is going. */
    RUNNING,

    /** A job has ended and its card is still waiting to be closed. */
    FINISHED
}
