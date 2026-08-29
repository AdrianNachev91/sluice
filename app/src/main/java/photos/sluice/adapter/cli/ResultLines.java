package photos.sluice.adapter.cli;

import java.util.List;
import java.util.Locale;

/**
 * Builds the counted lines a job verb's result is made of.
 */
final class ResultLines {

    /**
     * Prevents instantiation of this static utility class.
     */
    private ResultLines() {
    }

    /**
     * Adds a line only where it counts something.
     *
     * @param lines a {@link List} of {@link String} the lines so far
     * @param label {@link String} what it says
     * @param counted int what it counted
     */
    static void addWhenAny(final List<String> lines, final String label, final int counted) {
        if (counted > 0) {
            lines.add(count(label, counted));
        }
    }

    /**
     * One line, its count punctuated in threes.
     *
     * @param label {@link String} what it says
     * @param counted int what it counted
     * @return {@link String} the line
     */
    static String count(final String label, final int counted) {
        return label + ": " + grouped(counted);
    }

    /**
     * Punctuates a count in threes.
     *
     * @param counted long the count
     * @return {@link String} the count, written out
     */
    static String grouped(final long counted) {
        return String.format(Locale.ROOT, "%,d", counted);
    }
}
