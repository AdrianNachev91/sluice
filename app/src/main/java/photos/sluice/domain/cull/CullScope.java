package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The set of ways a cull run's photos can be selected: a specific year (optionally narrowed to
 * months), or the N oldest-by-mtime files present.
 *
 * <p>Months may be an explicit, non-contiguous set. A contiguous
 * {@link photos.sluice.domain.model.MonthRange} (used by
 * {@link photos.sluice.domain.model.SortScope}/{@link photos.sluice.domain.commit.CommitScope})
 * cannot express skipping a single outlier month out of an otherwise-batched year. {@link OldestN}
 * orders by raw filesystem mtime, not a resolved date. This differs from
 * {@link photos.sluice.domain.model.SortScope.OldestN}, which follows the dating-resolution chain.
 */
public sealed interface CullScope {

    /**
     * Selects every candidate dated to a specific year, optionally narrowed to an explicit set of
     * months.
     */
    record Year(int year, @Nullable List<Integer> months) implements CullScope {
        /**
         * Defensively copies the months list.
         *
         * @param year int the scope's year
         * @param months a {@link List} of {@link Integer} specific months to include, or null for the whole year
         */
        public Year {
            months = months == null ? null : List.copyOf(months);
        }
    }

    /**
     * Selects the {@code n} oldest candidates by mtime, irrespective of which year or month they
     * fall in.
     */
    record OldestN(int n) implements CullScope {
    }

    /**
     * The on-disk tag identifying this scope's prep dir (logs/cull-prep/<tag>/). Also PrepDir.scope()
     * and a WaitingCullJob's own scope() - both carry this same string. Lives here, not in the
     * adapter that names the directory, so the application layer can compute it too: Pipeline uses
     * it to recognize an existing waiting job for the same scope before rebuilding its prep dir.
     *
     * @param scope {@link CullScope} the cull scope to tag
     * @return {@link String} the scope's on-disk tag
     */
    static String tag(CullScope scope) {
        return switch (scope) {
            case Year(int year, List<Integer> months) -> yearTag(year, months);
            case OldestN(int n) -> "oldest-" + n;
        };
    }

    /**
     * Builds the tag suffix for a year scope, including months when narrowed.
     *
     * @param year int the scope's year
     * @param months a {@link List} of {@link Integer} specific months to include, or null for the whole year
     * @return {@link String} the year (and optional month suffix) tag
     */
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
