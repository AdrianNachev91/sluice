package photos.sluice.domain.model;

import org.jspecify.annotations.Nullable;

// The set of ways a sort run's Inbox files can be selected: a specific year (optionally narrowed
// to a month range), the N oldest files, or the oldest year present. There is no "nothing
// specified" variant: a caller with no explicit selection builds OldestYear() itself, which is the
// default selection when a user gives none.
public sealed interface SortScope {

    record Year(int year, @Nullable MonthRange months) implements SortScope {
    }

    record OldestN(int n) implements SortScope {
    }

    record OldestYear() implements SortScope {
    }
}
