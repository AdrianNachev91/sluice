package photos.sluice.adapter.cli;

import photos.sluice.domain.model.MonthRange;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Reads what somebody typed after {@code --months}, and narrows it to a span where the verb only
 * takes one.
 *
 * <p>Two spellings, and every verb on this surface takes both. A span, {@code 6-8}. A list,
 * {@code 6,8,11}. A single month is either one written short. A list may hold spans, as in
 * {@code 6-8,11}, which is the same set the desktop's own scope field reads out of that text.
 *
 * <p>What comes back is sorted and holds no month twice, so a caller sees one shape whichever
 * spelling produced it. Whether a verb can act on a set with a gap in it is that verb's question,
 * answered by {@link #spanOf}.
 */
final class Months {

    /**
     * Separates the months of a list.
     */
    private static final String LIST_SEPARATOR = ",";

    /**
     * Separates the ends of a span.
     */
    private static final String SPAN_SEPARATOR = "-";

    /**
     * The first month of the year.
     */
    private static final int FIRST_MONTH = 1;

    /**
     * The last month of the year.
     */
    private static final int LAST_MONTH = 12;

    /**
     * Prevents instantiation of this static utility class.
     */
    private Months() {}

    /**
     * The months one {@code --months} value names.
     *
     * @param text {@link String} what was typed after the option
     * @return a {@link List} of {@link Integer} the months, sorted, each appearing once
     * @throws ScopeRefusedException when a piece of the value is neither a month nor a span, or
     *         names something outside the calendar
     */
    static List<Integer> of(final String text) {
        return Arrays.stream(text.split(LIST_SEPARATOR, -1))
                .map(piece -> monthsIn(piece, text))
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The months one comma-separated piece names, that piece being a span or a single month.
     *
     * @param piece {@link String} one piece of the value
     * @param text {@link String} the whole value, for the refusal to show
     * @return a {@link List} of {@link Integer} the months it names
     * @throws ScopeRefusedException when the piece is not months
     */
    private static List<Integer> monthsIn(final String piece, final String text) {
        return piece.contains(SPAN_SEPARATOR) ? span(piece, text) : List.of(month(piece, text));
    }

    /**
     * The span one {@code --months} value covers, where it has no gap in it.
     *
     * <p>{@link MonthRange} is a first and a last, so it cannot say that a month between them is
     * out.
     *
     * @param text {@link String} what was typed after the option
     * @param verb {@link String} the verb refusing, as the person typed it
     * @return {@link MonthRange} the span from the first month to the last
     * @throws ScopeRefusedException when the value is not months, or the months have a gap in them
     */
    static MonthRange spanOf(final String text, final String verb) {
        final List<Integer> months = of(text);
        final int from = months.getFirst();
        final int to = months.getLast();
        if (months.size() != to - from + 1) {
            throw new ScopeRefusedException(new Refusal(RefusalKind.MONTHS_NOT_A_SPAN,
                    "Not a span: " + Refusal.shownValue(text) + ". " + verb
                            + " narrows a year by a span of months, so write one, like 6-8.",
                    Fields.of("verb", verb, "value", text, "months", months)));
        }
        return new MonthRange(from, to);
    }

    /**
     * The months one span covers, from its first to its last.
     *
     * @param piece {@link String} the span, as one piece of the value
     * @param text {@link String} the whole value, for the refusal to show
     * @return a {@link List} of {@link Integer} every month the span covers
     * @throws ScopeRefusedException when it is not a span of two months, or runs backwards
     */
    private static List<Integer> span(final String piece, final String text) {
        final String[] ends = piece.split(SPAN_SEPARATOR, -1);
        if (ends.length != 2) {
            throw refused(text);
        }
        final int from = month(ends[0], text);
        final int to = month(ends[1], text);
        if (from > to) {
            throw refused(text);
        }
        return IntStream.rangeClosed(from, to).boxed().toList();
    }

    /**
     * One month, from the digits naming it.
     *
     * @param part {@link String} one piece of the value
     * @param text {@link String} the whole value, for the refusal to show
     * @return int the month
     * @throws ScopeRefusedException when the piece is not a number in the calendar
     */
    private static int month(final String part, final String text) {
        final String trimmed = part.trim();
        if (!AsciiDigits.isAllDigits(trimmed)) {
            throw refused(text);
        }
        final int month;
        try {
            month = Integer.parseInt(trimmed);
        } catch (final NumberFormatException tooManyDigits) {
            throw refused(text);
        }
        if (month < FIRST_MONTH || month > LAST_MONTH) {
            throw refused(text);
        }
        return month;
    }

    /**
     * The refusal for a value that is not months.
     *
     * @param text {@link String} what was typed after the option
     * @return {@link ScopeRefusedException} the refusal to throw
     */
    private static ScopeRefusedException refused(final String text) {
        return new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                "Not months: " + Refusal.shownValue(text) + ". Write a span, like 6-8, or a list, "
                        + "like 6,8,11, or both, like 6-8,11.",
                Fields.of("option", "--months", "value", text)));
    }
}
