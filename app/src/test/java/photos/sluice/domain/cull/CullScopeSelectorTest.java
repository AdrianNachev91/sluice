package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CullScopeSelectorTest {

    private final CullScopeSelector selector = new CullScopeSelector();
    private final Path photosRoot = Path.of("Sorted", "Photos");

    @Test
    void directoriesToScanForYearWithMonthsReturnsSortedDeduplicatedMonthDirs() {
        final var scope = new CullScope.Year(2019, List.of(8, 6, 6, 7));

        assertThat(selector.directoriesToScan(photosRoot, scope)).containsExactly(
                photosRoot.resolve("2019").resolve("06"),
                photosRoot.resolve("2019").resolve("07"),
                photosRoot.resolve("2019").resolve("08"));
    }

    @Test
    void directoriesToScanForYearWithNullMonthsReturnsWholeYearDir() {
        final var scope = new CullScope.Year(2019, null);

        assertThat(selector.directoriesToScan(photosRoot, scope))
                .containsExactly(photosRoot.resolve("2019"));
    }

    @Test
    void directoriesToScanForOldestNReturnsWholePhotosRoot() {
        assertThat(selector.directoriesToScan(photosRoot, new CullScope.OldestN(50)))
                .containsExactly(photosRoot);
    }

    @Test
    void basePathForYearIsTheYearDirEvenWhenNarrowedToSpecificMonths() {
        final var scope = new CullScope.Year(2019, List.of(6));

        assertThat(selector.basePath(photosRoot, scope)).isEqualTo(photosRoot.resolve("2019"));
    }

    @Test
    void basePathForOldestNIsThePhotosRoot() {
        assertThat(selector.basePath(photosRoot, new CullScope.OldestN(50))).isEqualTo(photosRoot);
    }

    @Test
    void orderForYearSortsByMtimeAscendingWithoutLimiting() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var middle = candidate("b.jpg", "2019-06-02T00:00:00Z");
        final var newest = candidate("c.jpg", "2019-06-03T00:00:00Z");

        final var ordered = selector.order(
                List.of(newest, oldest, middle), new CullScope.Year(2019, null));

        assertThat(ordered).containsExactly(oldest, middle, newest);
    }

    @Test
    void orderForOldestNSortsThenCapsToN() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var middle = candidate("b.jpg", "2019-06-02T00:00:00Z");
        final var newest = candidate("c.jpg", "2019-06-03T00:00:00Z");

        final var ordered = selector.order(
                List.of(newest, oldest, middle), new CullScope.OldestN(2));

        assertThat(ordered).containsExactly(oldest, middle);
    }

    @Test
    void orderForOldestNWithNGreaterThanCandidateCountReturnsEverything() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var newest = candidate("b.jpg", "2019-06-02T00:00:00Z");

        final var ordered = selector.order(List.of(newest, oldest), new CullScope.OldestN(50));

        assertThat(ordered).containsExactly(oldest, newest);
    }

    private static CullCandidate candidate(final String name, final String instant) {
        return new CullCandidate(Path.of(name), Instant.parse(instant));
    }
}
