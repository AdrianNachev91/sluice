package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.paths.SortFolderNames;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * Reads the dashboard's scope field, without knowing which mode will take what it says.
 *
 * <p>Static and holding nothing. What the field accepts is one rule, and the mode chosen decides
 * only what to do with the year and months it comes to. Keeping the reading here means a refusal
 * about the text itself is worded once, whichever mode the user is on.
 *
 * <p>So the refusal names both shapes this reads, including the one only a move to the library can
 * act on. Which mode can take which is refused separately, and says why.
 */
final class RunScopeText {

    /**
     * The word naming the photos nothing could date, which is what a rescue leaves in Sorted.
     *
     * <p>Read here whatever the mode, so the word means the same thing on every one of them. Which
     * modes can take it is a separate question, and the mode is what answers it.
     */
    static final String UNDATED = SortFolderNames.UNDATED;

    private RunScopeText() {
    }

    /**
     * Reads the scope field's text.
     *
     * @param text {@link String} the field's text
     * @return {@link Typed} the year and months in it, or why it could not be read
     */
    static Typed parse(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new Typed.Blank();
        }
        if (UNDATED.equalsIgnoreCase(trimmed)) {
            return new Typed.Undated();
        }
        final String[] parts = trimmed.split("\\s+", 2);
        if (!parts[0].matches("\\d{4}")) {
            return new Typed.Refused("A scope is a four-digit year, like 2019, or the word "
                    + UNDATED + ".");
        }
        final int year = Integer.parseInt(parts[0]);
        return parts.length == 1 ? new Typed.OfYear(year, List.of()) : months(year, parts[1]);
    }

    /**
     * Whether a sorted, deduplicated month list is a run with no gap in it.
     *
     * @param months a {@link List} of {@link Integer} the months, sorted and deduplicated
     * @return boolean true when they run end to end
     */
    static boolean contiguous(final List<Integer> months) {
        return months.getLast() - months.getFirst() + 1 == months.size();
    }

    /**
     * Reads the month part of a scope.
     *
     * @param year int the year already read
     * @param text {@link String} everything after the year
     * @return {@link Typed} the year and its months, or why they could not be read
     */
    private static Typed months(final int year, final String text) {
        final List<Integer> months = new ArrayList<>();
        for (final String part : text.split(",")) {
            final String piece = part.trim();
            final Typed refusal = refusedFormat(piece);
            if (refusal != null) {
                return refusal;
            }
            months.addAll(monthsIn(piece));
        }
        return new Typed.OfYear(year, months.stream().distinct().sorted().toList());
    }

    /**
     * What is wrong with one comma-separated piece of the month part, where anything is.
     *
     * <p>A piece is one month or a run between two. This only judges it, and {@link #monthsIn} only
     * reads it. Kept apart because one method doing both would add months as a side effect of being
     * asked about refusals. Nothing named after the answer it returns should also be changing
     * something.
     *
     * @param part {@link String} the piece to judge
     * @return {@link Typed} the refusal, or null where the piece is a legal one
     */
    private static @Nullable Typed refusedFormat(final String part) {
        final String[] ends = part.split("-", 2);
        final OptionalInt first = monthIn(ends[0]);
        final OptionalInt last = ends.length == 1 ? first : monthIn(ends[1]);
        if (first.isEmpty() || last.isEmpty()) {
            return new Typed.Refused("Months are numbers from 1 to 12, like 6 or 6-8.");
        }
        if (last.getAsInt() < first.getAsInt()) {
            return new Typed.Refused("A run of months goes from the earlier one to the later, like 6-8.");
        }
        return null;
    }

    /**
     * The months one comma-separated piece covers, which is one month or a whole run of them.
     *
     * <p>Reads a piece {@link #refusedFormat} has already passed, so both ends are known to be
     * months and known to be the right way round. Asked about anything else it fails loudly rather
     * than answering an empty run, since an empty answer would read as a piece covering no months.
     *
     * @param part {@link String} the piece to read
     * @return a {@link List} of {@link Integer} every month it covers, in order
     */
    private static List<Integer> monthsIn(final String part) {
        final String[] ends = part.split("-", 2);
        final int first = monthIn(ends[0]).orElseThrow(RunScopeText::notAMonth);
        final int last = ends.length == 1 ? first : monthIn(ends[1]).orElseThrow(RunScopeText::notAMonth);
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    /**
     * What to throw when a piece reaches {@link #monthsIn} without being a legal one.
     *
     * @return {@link IllegalStateException} for whoever finds this in a log
     */
    private static IllegalStateException notAMonth() {
        return new IllegalStateException("months were read from an unchecked scope piece");
    }

    /**
     * One month read from text.
     *
     * @param text {@link String} the text to read
     * @return {@link OptionalInt} the month, empty where the text is not one
     */
    private static OptionalInt monthIn(final String text) {
        final String trimmed = text.trim();
        if (!trimmed.matches("\\d{1,2}")) {
            return OptionalInt.empty();
        }
        final int month = Integer.parseInt(trimmed);
        return month >= 1 && month <= 12 ? OptionalInt.of(month) : OptionalInt.empty();
    }

    /**
     * What the scope field's text amounts to before a mode has been applied to it.
     */
    sealed interface Typed {

        /** Nothing has been typed. */
        record Blank() implements Typed {
        }

        /** The word naming the photos nothing could date. */
        record Undated() implements Typed {
        }

        /**
         * A year, and the months narrowed to within it.
         *
         * @param year int the year
         * @param months a {@link List} of {@link Integer} the months, sorted and deduplicated,
         *     empty for the whole year
         */
        record OfYear(int year, List<Integer> months) implements Typed {
        }

        /**
         * The text could not be read as a scope.
         *
         * @param reason {@link String} what is wrong with it
         */
        record Refused(String reason) implements Typed {
        }
    }
}
