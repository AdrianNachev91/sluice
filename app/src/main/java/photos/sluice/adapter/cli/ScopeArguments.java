package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.sift.SiftScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;

import java.util.List;

/**
 * What a caller typed to say which photos to work on, and the scope each verb makes of it.
 *
 * <p>A verb declares its own options, because the help each one needs differs. Sort orders its
 * oldest by the date it resolves for a photo and sift by the file's own timestamp, so one
 * description could not serve both.
 *
 * <p>The year arrives as text rather than as a number. Move-to-library takes {@code all} and
 * {@code unsorted} in the same position. And a year that reads as a number but is not four digits
 * is refused with a document rather than by the parser.
 *
 * @param year {@link String} the positional year, or {@code all}, or null where none was given
 * @param months {@link String} what was typed after {@code --months}, or null
 * @param oldest {@link Integer} what was typed after {@code --oldest}, or null
 */
public record ScopeArguments(@Nullable String year, @Nullable String months, @Nullable Integer oldest) {

    /**
     * The option narrowing a year to some of its months.
     */
    public static final String MONTHS = "--months";

    /**
     * The option asking for the oldest photos rather than a year.
     */
    public static final String OLDEST = "--oldest";

    /**
     * The word standing for every year at once, where a verb takes one.
     */
    public static final String ALL = "all";

    /**
     * The word standing for the photos nothing could date, which a move to the library alone takes.
     */
    public static final String UNDATED = "unsorted";

    /**
     * How many digits a year has.
     */
    private static final int YEAR_DIGITS = 4;

    /**
     * What a year may not start with.
     */
    private static final String LEADING_ZERO = "0";

    /**
     * The smallest count {@code --oldest} can be asked for.
     */
    private static final int MINIMUM_PHOTOS = 1;

    /**
     * The scope a sort run takes from these arguments.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @return {@link SortScope} what to sort
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    public SortScope sortScope(final String verb) {
        this.refuseTwoWays(verb);
        this.refuseUndated(verb, "A sort reads your Inbox, and " + UNDATED
                + " names photos already in Sorted");
        if (this.oldest != null) {
            return new SortScope.OldestN(count(this.oldest));
        }
        if (this.year == null) {
            return new SortScope.OldestYear();
        }
        return new SortScope.Year(yearOf(this.year, null), this.span(verb));
    }

    /**
     * The scope a sift run takes from these arguments.
     *
     * <p>Sifting spends from the caller's provider account balance, so the photos it covers are
     * named rather than guessed at.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @return {@link SiftScope} what to sift
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    public SiftScope siftScope(final String verb) {
        this.refuseTwoWays(verb);
        this.refuseUndated(verb, "A sift looks at photos filed under a year, and " + UNDATED
                + " names the ones nothing could date");
        if (this.oldest != null) {
            return new SiftScope.OldestN(count(this.oldest));
        }
        if (this.year == null) {
            throw missing(verb, "Name a year, like " + verb + " 2019, or " + OLDEST + " 30.");
        }
        return new SiftScope.Year(yearOf(this.year, null),
                this.months == null ? null : Months.of(this.months));
    }

    /**
     * The scope a move-to-library run takes from these arguments.
     *
     * <p>{@code all} is spelled out because this verb puts files into the library, and a mistyped
     * argument should not sweep everything there.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @return {@link CommitScope} what to move
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    public CommitScope commitScope(final String verb) {
        if (this.oldest != null) {
            throw conflicting(verb, verb + " takes a year or " + ALL + ", never a count of photos.");
        }
        this.refuseMonthsWithNoYear(verb);
        if (this.year == null) {
            throw missing(verb, "Name a year, like " + verb + " 2019, or " + verb + " " + ALL + ".");
        }
        if (ALL.equals(this.year)) {
            if (this.months != null) {
                throw conflicting(verb, ALL + " is every year, so there is no year for " + MONTHS + " to narrow.");
            }
            return new CommitScope.All();
        }
        if (UNDATED.equals(this.year)) {
            if (this.months != null) {
                throw conflicting(verb, UNDATED + " is photos with no date, so there is no year for "
                        + MONTHS + " to narrow.");
            }
            return new CommitScope.Undated();
        }
        // Both of the words this verb also takes, since a refusal enumerating what it accepts is
        // the one place a caller learns there are any.
        return new CommitScope.Year(yearOf(this.year, "For every year at once, write " + verb + " "
                        + ALL + ". For the photos nothing could date, write " + verb + " " + UNDATED + "."),
                this.span(verb));
    }

    /**
     * Refuses the undated word for a verb that has nothing undated to work on.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @param why {@link String} what this verb reads, and what the word names
     * @throws ScopeRefusedException when the undated word was given to a verb that cannot take it
     */
    private void refuseUndated(final String verb, final String why) {
        if (UNDATED.equals(this.year)) {
            throw conflicting(verb, why + ". Write " + CommitCommand.VERB + " " + UNDATED
                    + " to move them into your Library.");
        }
    }

    /**
     * Refuses arguments that name the photos two ways at once.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @throws ScopeRefusedException when more than one of them names a set of photos
     */
    private void refuseTwoWays(final String verb) {
        if (this.oldest != null && this.year != null) {
            throw conflicting(verb, "A year and " + OLDEST + " name different photos, so " + verb
                    + " takes one or the other.");
        }
        if (this.oldest != null && this.months != null) {
            throw conflicting(verb, MONTHS + " narrows a year, and " + OLDEST + " names no year.");
        }
        this.refuseMonthsWithNoYear(verb);
    }

    /**
     * Refuses months with no year for them to narrow.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @throws ScopeRefusedException when months were given and no year was
     */
    private void refuseMonthsWithNoYear(final String verb) {
        if (this.year == null && this.months != null) {
            throw missing(verb, MONTHS + " narrows a year, so name one, like " + verb + " 2019 " + MONTHS + " 6-8.");
        }
    }

    /**
     * The span this verb narrows its year to, where one was asked for.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @return {@link MonthRange} the span, or null where the whole year was asked for
     * @throws ScopeRefusedException when the months cannot be read, or have a gap in them
     */
    private @Nullable MonthRange span(final String verb) {
        return this.months == null ? null : Months.spanOf(this.months, verb);
    }

    /**
     * The year some text names.
     *
     * <p>Four digits, and no leading zero. A year written any other way cannot be read back out of
     * the folder name its own sift produces, so a second sift of the same months would be neither
     * noticed nor refused, and would spend for them again.
     *
     * @param text {@link String} what was typed in the year's position
     * @param alsoAccepted {@link String} what else this verb takes in that position, or null where
     *        it takes a year and nothing else
     * @return int the year
     * @throws ScopeRefusedException when it is not four digits, or carries a leading zero
     */
    private static int yearOf(final String text, final @Nullable String alsoAccepted) {
        if (text.length() != YEAR_DIGITS || !AsciiDigits.isAllDigits(text) || text.startsWith(LEADING_ZERO)) {
            final String sentence = "Not a year: " + Refusal.shownValue(text)
                    + ". A year is four digits with no leading zero, like 2019.";
            throw new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                    alsoAccepted == null ? sentence : Refusal.sentences(List.of(sentence, alsoAccepted)),
                    Fields.of("parameter", "year", "value", text)));
        }
        return Integer.parseInt(text);
    }

    /**
     * The count {@code --oldest} was asked for.
     *
     * @param asked {@link Integer} what was typed after the option
     * @return int the count
     * @throws ScopeRefusedException when it is fewer than one photo
     */
    private static int count(final Integer asked) {
        if (asked < MINIMUM_PHOTOS) {
            throw new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                    "Not a photo count: " + asked + ". " + OLDEST + " needs at least 1.",
                    Fields.of("option", OLDEST, "value", asked)));
        }
        return asked;
    }

    /**
     * The refusal for arguments that named nothing to work on.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @param remedy {@link String} what to write instead
     * @return {@link ScopeRefusedException} the refusal to throw
     */
    private static ScopeRefusedException missing(final String verb, final String remedy) {
        return new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_MISSING,
                "Missing scope. " + remedy, Fields.of("verb", verb)));
    }

    /**
     * The refusal for arguments that named the photos two ways at once.
     *
     * @param verb {@link String} the verb asking, as the person typed it
     * @param why {@link String} which two, and why they do not go together
     * @return {@link ScopeRefusedException} the refusal to throw
     */
    private static ScopeRefusedException conflicting(final String verb, final String why) {
        return new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_CONFLICTING,
                "Unclear scope. " + why, Fields.of("verb", verb)));
    }
}
