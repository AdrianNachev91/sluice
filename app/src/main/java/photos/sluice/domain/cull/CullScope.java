package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.util.List;

// The set of ways a cull run's photos can be selected: a specific year (optionally narrowed to
// months), or the N oldest-by-mtime files present. Months may be an explicit, non-contiguous set,
// since a contiguous MonthRange (used by SortScope/CommitScope) can't express skipping a single
// outlier month out of an otherwise-batched year. OldestN orders by raw filesystem mtime, not a
// resolved date. This differs from SortScope.OldestN, which follows the dating-resolution chain.
public sealed interface CullScope {

    record Year(int year, @Nullable List<Integer> months) implements CullScope {
        public Year {
            months = months == null ? null : List.copyOf(months);
        }
    }

    record OldestN(int n) implements CullScope {
    }
}
