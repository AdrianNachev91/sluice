package photos.sluice.domain.sift;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.Numerals;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves a {@link SiftScope} against the Sorted {@code Photos} root. It decides which
 * directories to scan, what {@code basePath} to report, and how to order (and, for
 * {@link SiftScope.OldestN}, cap) the candidates found there.
 *
 * <p>Pure {@link Path}/{@link java.time.Instant} logic only. The caller does the actual directory
 * listing and mtime reads (I/O), then hands the results back here for ordering.
 */
public final class SiftScopeSelector {

    /**
     * Directories to scan under photosRoot for a scope. Year confines the scan to the requested
     * month subdirectories (sorted, deduplicated), or the whole year directory when months is null,
     * which covers every month present. OldestN scans the whole Photos root, since the N oldest
     * files can come from any year.
     *
     * @param photosRoot {@link Path} the Sorted Photos root
     * @param scope {@link SiftScope} the sift scope to resolve
     * @return a {@link List} of {@link Path} directories to scan for candidates
     */
    public List<Path> directoriesToScan(final Path photosRoot, final SiftScope scope) {
        return switch (scope) {
            case SiftScope.Year(final int year, final List<Integer> months) ->
                    monthDirectories(photosRoot, year, months);
            case SiftScope.OldestN _ -> List.of(photosRoot);
        };
    }

    /**
     * The basePath reported in index.json: the year directory for Year, or the whole Photos root
     * for OldestN. Year's basePath always names the scope's year, even when narrowed to specific
     * months - not the exact subset scanned within it.
     *
     * @param photosRoot {@link Path} the Sorted Photos root
     * @param scope {@link SiftScope} the sift scope to resolve
     * @return {@link Path} the base path to report for this scope
     */
    public Path basePath(final Path photosRoot, final SiftScope scope) {
        return switch (scope) {
            case SiftScope.Year(final int year, List<Integer> _) -> photosRoot.resolve(String.valueOf(year));
            case SiftScope.OldestN _ -> photosRoot;
        };
    }

    /**
     * Sorts candidates by mtime ascending. OldestN additionally caps to n - directoriesToScan
     * already scoped Year to exactly the right files, so Year needs no further limiting here.
     *
     * @param candidates a {@link List} of {@link SiftCandidate} the candidates to order
     * @param scope {@link SiftScope} the sift scope being resolved
     * @return a {@link List} of {@link SiftCandidate} the ordered (and possibly capped) candidates
     */
    public List<SiftCandidate> order(final List<SiftCandidate> candidates, final SiftScope scope) {
        final List<SiftCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparing(SiftCandidate::mtime))
                .toList();
        return switch (scope) {
            case SiftScope.Year _ -> sorted;
            case SiftScope.OldestN(final int n) -> sorted.stream().limit(n).toList();
        };
    }

    /**
     * Resolves the month subdirectories under a year for a scope, or the whole year directory
     * when months is null.
     *
     * @param photosRoot {@link Path} the Sorted Photos root
     * @param year int the year to resolve
     * @param months a {@link List} of {@link Integer} specific months to include, or null for the whole year
     * @return a {@link List} of {@link Path} the resolved month (or year) directories
     */
    private static List<Path> monthDirectories(final Path photosRoot, final int year,
                                               final @Nullable List<Integer> months) {
        final Path yearDir = photosRoot.resolve(String.valueOf(year));
        if (months == null) {
            return List.of(yearDir);
        }
        return months.stream()
                .distinct()
                .sorted()
                .map(month -> yearDir.resolve(Numerals.padded(month, 2)))
                .toList();
    }
}
