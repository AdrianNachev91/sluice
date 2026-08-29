package photos.sluice.adapter.ui;

/**
 * What a reader is told when a screen would not open.
 */
public final class ScreenFailure {

    /**
     * What to show in place of a screen that could not be built.
     *
     * <p>Says only what the catch behind it can know. It names no cause, because a catch-all has
     * none, and it promises nothing about the other screens for the same reason.
     *
     * <p>It carries no reassurance about the reader's photos. A press can start a job and then send
     * the reader somewhere else, so the only true version is about the screen rather than the app.
     * That version answers nothing a reader was asking.
     *
     * <p>A restart is offered without predicting it will work. Some causes it clears, a presenter
     * left unusable or a file another process had open, and some it reproduces exactly. Nothing
     * here can tell which, so the reader is told to try rather than told it helps.
     *
     * <p>The failure rides along verbatim, the way a refusal nobody wrote words for does. Every log
     * this app writes goes to a stream, and a reader who opened it from their desktop has no
     * console to find. Asking for a bug report without handing over the one thing worth reporting
     * leaves them nothing to send.
     *
     * @param failure {@link Throwable} what the screen's construction threw
     * @return {@link String} the sentence to show
     */
    public static String wouldNotOpen(final Throwable failure) {
        return "This screen would not open. Try closing and reopening Sluice, and if it keeps "
                + "happening, report this as a bug, quoting this: " + failure;
    }

    /**
     * Prevents instantiation of this static factory class.
     */
    private ScreenFailure() {
    }
}
