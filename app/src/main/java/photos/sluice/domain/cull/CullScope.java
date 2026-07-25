package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

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

    // The on-disk tag identifying this scope's prep dir (logs/cull-prep/<tag>/). Also PrepDir.scope()
    // and a WaitingCullJob's own scope() - both carry this same string. Lives here, not in the
    // adapter that names the directory, so the application layer can compute it too: Pipeline uses
    // it to recognize an existing waiting job for the same scope before rebuilding its prep dir.
    static String tag(CullScope scope) {
        return switch (scope) {
            case Year(int year, List<Integer> months) -> yearTag(year, months);
            case OldestN(int n) -> "oldest-" + n;
        };
    }

    private static String yearTag(int year, @Nullable List<Integer> months) {
        if (months == null) {
            return String.valueOf(year);
        }
        String monthSuffix = months.stream()
                .distinct()
                .sorted()
                .map("%02d"::formatted)
                .collect(Collectors.joining("-"));
        return year + "-" + monthSuffix;
    }
}
