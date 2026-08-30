package photos.sluice.adapter.ui;

import java.io.PrintWriter;
import java.io.StringWriter;

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
     * <p>Ends on the failure rather than carrying it, since that sits behind a fold of its own.
     *
     * @return {@link String} the sentence to show
     */
    public static String wouldNotOpen() {
        return "This screen would not open. Try closing and reopening Sluice, and if it keeps "
                + "happening, report this as a bug, quoting this:";
    }

    /**
     * What the control revealing the failure says.
     *
     * <p>Names what it reveals rather than saying "details", because opening it puts paths from
     * the reader's own machine on screen and that is theirs to choose.
     *
     * @return {@link String} the label
     */
    public static String showTheDetails() {
        return "Show the error details";
    }

    /**
     * The failure itself, for the reader to send on.
     *
     * <p>The whole trace, frames included. A type and a message name what broke without saying
     * where. The catch behind this names no cause of its own, so the frames are the only thing
     * that can.
     *
     * @param failure {@link Throwable} what the screen's construction threw
     * @return {@link String} what to quote
     */
    public static String trace(final Throwable failure) {
        final var text = new StringWriter();
        try (final var writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        return text.toString().stripTrailing();
    }

    /**
     * What the button copying that failure says.
     *
     * @return {@link String} the label
     */
    public static String copy() {
        return "Copy";
    }

    /**
     * What that button says once it has copied.
     *
     * @return {@link String} the label
     */
    public static String copied() {
        return "Copied";
    }

    /**
     * Prevents instantiation of this static factory class.
     */
    private ScreenFailure() {
    }
}
