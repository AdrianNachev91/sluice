package photos.sluice.application.startup;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Why the app could not start, in a form a surface can render without knowing anything about the
 * framework underneath. Typed rather than worded, so a window and a command line each say it their
 * own way.
 *
 * <p>Sealed, so a surface that handles every case says so by compiling. A failure nobody wrote a
 * variant for arrives as {@link Unclassified}, which is what keeps this from becoming a list that
 * silently falls behind.
 *
 * <p>Every variant carries the rendered stack trace. There is no in-app recovery from a startup
 * failure, so handing the user the evidence is the whole of what a surface can offer. Nothing here
 * sends it anywhere.
 */
public sealed interface StartupFailure {

    /**
     * The failure's stack trace, rendered.
     *
     * @return {@link String} the trace, empty when nothing rendered one
     */
    String trace();

    /**
     * Another process holds the working root. The ordinary way to meet this is opening Sluice twice.
     *
     * @param workingRoot {@link Path} the folder the other process holds
     * @param trace {@link String} the rendered stack trace
     */
    record WorkingRootBusy(Path workingRoot, String trace) implements StartupFailure {
    }

    /**
     * A configured setting holds a value the app refused. The user can put this right themselves,
     * which is what makes it worth telling apart from the rest.
     *
     * @param property {@link String} the setting, in its canonical spelling
     * @param spot {@link ConfigSpot} where the offending value sits, or null when it did not come
     *     from the user's own config file
     * @param trace {@link String} the rendered stack trace
     */
    record RejectedSetting(String property, @Nullable ConfigSpot spot, String trace) implements StartupFailure {
    }

    /**
     * The config file could not be parsed, so nothing in it was read at all.
     *
     * @param spot {@link ConfigSpot} the file, and where in it parsing gave up
     * @param problem {@link String} what the parser could not make sense of
     * @param trace {@link String} the rendered stack trace
     */
    record UnparsableConfigFile(ConfigSpot spot, String problem, String trace) implements StartupFailure {
    }

    /**
     * The app refused the settings it was configured with, and said why in its own words.
     *
     * <p>Apart from {@link RejectedSetting} because the two know different things. That one names
     * the setting and usually where it sits, and leaves the wording to the surface. This one has
     * the sentence and neither the setting nor a place. Its refusal comes from a value the app
     * builds out of several settings, rather than from any one of them failing to bind.
     *
     * @param problem {@link String} what is wrong, as the refusal itself worded it
     * @param trace {@link String} the rendered stack trace
     */
    record UnusableSettings(String problem, String trace) implements StartupFailure {
    }

    /**
     * A failure this app has written no copy for. The trace is the whole of what it can offer.
     *
     * @param trace {@link String} the rendered stack trace
     */
    record Unclassified(String trace) implements StartupFailure {
    }

    /**
     * A place in the user's config file. The position is separate because a file can be named
     * without one. A parser can give up without saying where, and a value's origin can carry the
     * resource and no location.
     *
     * @param file {@link Path} the config file
     * @param position {@link ConfigPosition} where in it, or null when only the file is known
     */
    record ConfigSpot(Path file, @Nullable ConfigPosition position) {
    }

    /**
     * A line and column, counted from one, the way an editor shows them.
     *
     * @param line int the line
     * @param column int the column
     */
    record ConfigPosition(int line, int column) {
    }
}
