package photos.sluice.domain.commit;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.MonthRange;

/**
 * The set of ways a commit run's Sorted files can be selected: a specific year, optionally narrowed
 * to a month range, everything, or the undated folder alone.
 *
 * <p>There is no separate "unscoped" variant. A caller with no explicit scope in mind builds
 * {@link All} directly, since it skips the year filter and includes Funny.
 */
public sealed interface CommitScope {

    /**
     * Scopes a commit run to files dated in the given year, optionally narrowed to a month range.
     *
     * @param year the year to scope to
     * @param months a {@link MonthRange} narrowing the year, or null to include the whole year
     */
    record Year(int year, @Nullable MonthRange months) implements CommitScope {
    }

    /**
     * Scopes a commit run to every file in Sorted, with no year or month filtering.
     */
    record All() implements CommitScope {
    }

    /**
     * Scopes a commit run to the undated folder, whose files carry no date, so {@link Year} can
     * never reach one.
     */
    record Undated() implements CommitScope {
    }
}
