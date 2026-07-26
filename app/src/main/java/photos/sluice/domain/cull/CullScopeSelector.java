package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

// Resolves a CullScope against the Sorted "Photos" root: which directories to scan, what basePath
// to report, and how to order (and, for OldestN, cap) the candidates found there. Pure Path/Instant
// logic only - the caller does the actual directory listing and mtime reads (I/O), then hands the
// results back here for ordering.
public final class CullScopeSelector {

    /**
     * Directories to scan under photosRoot for a scope. Year confines the scan to the requested
     * month subdirectories (sorted, deduplicated), or the whole year directory when months is null.
     * That covers every month present, for a caller that wants a whole year without listing every
     * month explicitly. OldestN scans the whole Photos root, since the N oldest files can come from
     * any year.
     *
     * @param photosRoot {@link Path} the Sorted Photos root
     * @param scope {@link CullScope} the cull scope to resolve
     * @return a {@link List} of {@link Path} directories to scan for candidates
     */
    public List<Path> directoriesToScan(Path photosRoot, CullScope scope) {
        return switch (scope) {
            case CullScope.Year(int year, List<Integer> months) -> monthDirectories(photosRoot, year, months);
            case CullScope.OldestN _ -> List.of(photosRoot);
        };
    }

    /**
     * The basePath reported in index.json: the year directory for Year, or the whole Photos root
     * for OldestN. Year's basePath always names the scope's year, even when narrowed to specific
     * months - not the exact subset scanned within it.
     *
     * @param photosRoot {@link Path} the Sorted Photos root
     * @param scope {@link CullScope} the cull scope to resolve
     * @return {@link Path} the base path to report for this scope
     */
    public Path basePath(Path photosRoot, CullScope scope) {
        return switch (scope) {
            case CullScope.Year(int year, List<Integer> _) -> photosRoot.resolve(String.valueOf(year));
            case CullScope.OldestN _ -> photosRoot;
        };
    }

    /**
     * Sorts candidates by mtime ascending. OldestN additionally caps to n - directoriesToScan
     * already scoped Year to exactly the right files, so Year needs no further limiting here.
     *
     * @param candidates a {@link List} of {@link CullCandidate} the candidates to order
     * @param scope {@link CullScope} the cull scope being resolved
     * @return a {@link List} of {@link CullCandidate} the ordered (and possibly capped) candidates
     */
    public List<CullCandidate> order(List<CullCandidate> candidates, CullScope scope) {
        List<CullCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparing(CullCandidate::mtime))
                .toList();
        return switch (scope) {
            case CullScope.Year _ -> sorted;
            case CullScope.OldestN(int n) -> sorted.stream().limit(n).toList();
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
    private static List<Path> monthDirectories(Path photosRoot, int year, @Nullable List<Integer> months) {
        Path yearDir = photosRoot.resolve(String.valueOf(year));
        if (months == null) {
            return List.of(yearDir);
        }
        return months.stream()
                .distinct()
                .sorted()
                .map(month -> yearDir.resolve("%02d".formatted(month)))
                .toList();
    }
}
