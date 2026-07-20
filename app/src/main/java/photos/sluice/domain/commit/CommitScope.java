package photos.sluice.domain.commit;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.SortScope.MonthRange;

// The set of ways a commit run's Sorted files can be selected: a specific year (optionally
// narrowed to a month range), or everything. There is no separate "unscoped" variant. The
// reference engine's no-flags default and its explicit all-scope flag are behaviorally identical -
// both skip the year filter and include Funny - so a caller with no explicit scope just builds
// All() itself.
public sealed interface CommitScope {

    record Year(int year, @Nullable MonthRange months) implements CommitScope {
    }

    record All() implements CommitScope {
    }
}
