package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;

import java.util.List;

/**
 * What the scope field and the chosen mode come to together.
 *
 * <p>Sealed over the scopes a run can be started from and those it cannot. A refusal carries the
 * sentence to show, and {@link Nothing} carries none because the cards above the field have already
 * said why there is no work.
 *
 * <p>The engines each narrow by their own type, and the conversions here are where one of these
 * becomes one of those. Each throws on every case it cannot carry, rather than falling back on a
 * default. Where a conversion has an arm that widens to everything, that is what stops a scope
 * naming no work from landing in it.
 */
sealed interface RunScope {

    /**
     * Whether a scope names work at all.
     *
     * @param scope {@link RunScope} what the field and mode come to
     * @return boolean true where there is something for a run to take
     */
    static boolean startable(final RunScope scope) {
        return switch (scope) {
            case OldestYear _, Everything _, OfYear _ -> true;
            case Refused _, Nothing _ -> false;
        };
    }

    /**
     * What a run covers, written out for a screen that cannot show the field that named it.
     *
     * @param ran {@link RunMode} the mode being started
     * @param scope {@link RunScope} what the field and mode come to
     * @return {@link String} what this run covers
     */
    static String describe(final RunMode ran, final RunScope scope) {
        return switch (scope) {
            case OfYear(final int year, final List<Integer> months) -> months.isEmpty()
                    ? String.valueOf(year)
                    : year + ", " + RunWords.namedMonths(months);
            case OldestYear _ -> "the oldest year in your Inbox";
            case Everything _ -> "everything in Sorted";
            // Neither can reach a started job: the button is dead over both. Answered anyway, since
            // a switch over a sealed set that throws for two of five is one added case away from
            // throwing on a screen.
            case Refused _, Nothing _ -> ran.verb();
        };
    }

    /**
     * The sort scope this one stands for.
     *
     * @param scope {@link RunScope} the parsed scope
     * @return {@link SortScope} what the engine is asked for
     */
    static SortScope asSort(final RunScope scope) {
        return switch (scope) {
            case OfYear(final int year, final List<Integer> months) ->
                    new SortScope.Year(year, range(months));
            case OldestYear _, Everything _ -> new SortScope.OldestYear();
            // Oldest-year is the widest thing a sort can be asked for, so a scope that names no
            // work must not land in it.
            case Refused _, Nothing _ ->
                    throw new IllegalStateException("A sort was started from a scope naming no work");
        };
    }

    /**
     * The cull scope this one stands for.
     *
     * @param scope {@link RunScope} the parsed scope
     * @return {@link CullScope} what the engine is asked for
     */
    static CullScope asCull(final RunScope scope) {
        if (scope instanceof OfYear(final int year, final List<Integer> months)) {
            return new CullScope.Year(year, months.isEmpty() ? null : months);
        }
        throw new IllegalStateException("A sift is only ever started against a year");
    }

    /**
     * The commit scope this one stands for.
     *
     * @param scope {@link RunScope} the parsed scope
     * @return {@link CommitScope} what the engine is asked for
     */
    static CommitScope asCommit(final RunScope scope) {
        return switch (scope) {
            case OfYear(final int year, final List<Integer> months) ->
                    new CommitScope.Year(year, range(months));
            case Everything _, OldestYear _ -> new CommitScope.All();
            // Everything else here widens to the whole library, so a scope naming no work must not
            // fall into it.
            case Refused _, Nothing _ ->
                    throw new IllegalStateException("A move to the library was started from a scope naming no work");
        };
    }

    /**
     * A run of months as the range type both sort and commit narrow by.
     *
     * @param months a {@link List} of {@link Integer} the months, already proved to be a run
     * @return {@link MonthRange} the range, or null for a whole year
     */
    private static @Nullable MonthRange range(final List<Integer> months) {
        return months.isEmpty() ? null : new MonthRange(months.getFirst(), months.getLast());
    }

    /** Whichever year is oldest in the Inbox, picked during the run. */
    record OldestYear() implements RunScope {
    }

    /** Everything staged, with no year filter at all. */
    record Everything() implements RunScope {
    }

    /**
     * One year, and the months narrowed to within it.
     *
     * @param year int the year
     * @param months a {@link List} of {@link Integer} the months, empty for the whole year
     */
    record OfYear(int year, List<Integer> months) implements RunScope {
    }

    /**
     * This mode cannot take what the field says.
     *
     * @param reason {@link String} what is wrong with it
     */
    record Refused(String reason) implements RunScope {
    }

    /**
     * There is nothing for this mode to work on, and the cards above have already said so.
     *
     * <p>Apart from {@link Refused} because it carries no sentence. An empty Inbox on a new install
     * is the ordinary state of one, not a fault. A line under the field would put it in the tone a
     * screen keeps for something being wrong.
     */
    record Nothing() implements RunScope {
    }
}
