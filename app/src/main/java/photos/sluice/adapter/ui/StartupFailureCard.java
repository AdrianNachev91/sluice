package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;

/**
 * What the failure screen draws, chosen from a {@link StartupFailurePresenter} and carrying only
 * display-ready values. The view picks a layout by type and reads nothing from it that it has to
 * decide anything about.
 *
 * <p>Every variant except {@link BusyRoot} also carries the whole rendered trace, since every card
 * but that one offers the disclosure a user can report from.
 */
public sealed interface StartupFailureCard {

    /**
     * The one line naming what happened. Every variant carries one, so a view drawing any card can
     * read it without a type switch of its own.
     *
     * @return {@link String} the detail line
     */
    String detail();

    /**
     * Another Sluice process holds the working root. The one card with no trace, since nothing
     * failed: a second window found the first already running.
     *
     * @param detail {@link String} the one line naming the situation
     */
    record BusyRoot(String detail) implements StartupFailureCard {
    }

    /**
     * A value in the user's own config file could not be used. Repairable by taking the one setting
     * out.
     *
     * @param detail {@link String} the one line naming the setting
     * @param property {@link String} the setting, dotted as config binding names it
     * @param file {@link String} the config file's path
     * @param position {@link String} where in the file the value sits, or null when only the file
     *     is known
     * @param trace {@link String} the whole rendered trace
     */
    record RejectedInFile(String detail, String property, String file, @Nullable String position, String trace)
            implements StartupFailureCard {
    }

    /**
     * The config file will not parse. Repairable only by setting the whole file aside, since a file
     * that will not parse has no document to take one key out of.
     *
     * @param detail {@link String} the one line naming the situation
     * @param file {@link String} the config file's path
     * @param position {@link String} where the parser gave up, or null when it could not say
     * @param problem {@link String} what the parser reported
     * @param trace {@link String} the whole rendered trace
     */
    record Unparsable(String detail, String file, @Nullable String position, String problem, String trace)
            implements StartupFailureCard {
    }

    /**
     * Anything else, including a rejected value this app cannot trace back to the user's own file.
     *
     * @param detail {@link String} the one line the app has to offer
     * @param trace {@link String} the whole rendered trace
     */
    record Generic(String detail, String trace) implements StartupFailureCard {
    }
}
