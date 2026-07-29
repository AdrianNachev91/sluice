package photos.sluice.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * The set of ways a sort run's Inbox files can be selected. Options are a specific year,
 * optionally narrowed to a month range, the N oldest files, or the oldest year present.
 *
 * <p>There is no "nothing specified" variant. A caller with no explicit selection builds
 * {@link OldestYear} itself, which is the default selection when a user gives none.
 */
public sealed interface SortScope {

    /**
     * Selects every file dated to {@code year}, or to just {@code months} within it when
     * {@code months} is given.
     */
    record Year(int year, @Nullable MonthRange months) implements SortScope {
    }

    /** Selects the {@code n} oldest-dated files across the whole Inbox. */
    record OldestN(int n) implements SortScope {
    }

    /** Selects every file dated to whichever year is oldest among the Inbox's files. */
    record OldestYear() implements SortScope {
    }
}
