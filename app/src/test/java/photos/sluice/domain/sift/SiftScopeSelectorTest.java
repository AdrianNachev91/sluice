package photos.sluice.domain.sift;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SiftScopeSelectorTest {

    private final SiftScopeSelector selector = new SiftScopeSelector();
    private final Path photosRoot = Path.of("Sorted", "Photos");

    @Test
    void directoriesToScanForYearWithMonthsReturnsSortedDeduplicatedMonthDirs() {
        final var scope = new SiftScope.Year(2019, List.of(8, 6, 6, 7));

        assertThat(this.selector.directoriesToScan(this.photosRoot, scope)).containsExactly(
                this.photosRoot.resolve("2019").resolve("06"),
                this.photosRoot.resolve("2019").resolve("07"),
                this.photosRoot.resolve("2019").resolve("08"));
    }

    @Test
    void directoriesToScanForYearWithNullMonthsReturnsWholeYearDir() {
        final var scope = new SiftScope.Year(2019, null);

        assertThat(this.selector.directoriesToScan(this.photosRoot, scope))
                .containsExactly(this.photosRoot.resolve("2019"));
    }

    @Test
    void directoriesToScanForOldestNReturnsWholePhotosRoot() {
        assertThat(this.selector.directoriesToScan(this.photosRoot, new SiftScope.OldestN(50)))
                .containsExactly(this.photosRoot);
    }

    @Test
    void basePathForYearIsTheYearDirEvenWhenNarrowedToSpecificMonths() {
        final var scope = new SiftScope.Year(2019, List.of(6));

        assertThat(this.selector.basePath(this.photosRoot, scope)).isEqualTo(this.photosRoot.resolve("2019"));
    }

    @Test
    void basePathForOldestNIsThePhotosRoot() {
        assertThat(this.selector.basePath(this.photosRoot, new SiftScope.OldestN(50))).isEqualTo(this.photosRoot);
    }

    @Test
    void orderForYearSortsByMtimeAscendingWithoutLimiting() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var middle = candidate("b.jpg", "2019-06-02T00:00:00Z");
        final var newest = candidate("c.jpg", "2019-06-03T00:00:00Z");

        final var ordered = this.selector.order(
                List.of(newest, oldest, middle), new SiftScope.Year(2019, null));

        assertThat(ordered).containsExactly(oldest, middle, newest);
    }

    @Test
    void orderForOldestNSortsThenCapsToN() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var middle = candidate("b.jpg", "2019-06-02T00:00:00Z");
        final var newest = candidate("c.jpg", "2019-06-03T00:00:00Z");

        final var ordered = this.selector.order(
                List.of(newest, oldest, middle), new SiftScope.OldestN(2));

        assertThat(ordered).containsExactly(oldest, middle);
    }

    @Test
    void orderForOldestNWithNGreaterThanCandidateCountReturnsEverything() {
        final var oldest = candidate("a.jpg", "2019-06-01T00:00:00Z");
        final var newest = candidate("b.jpg", "2019-06-02T00:00:00Z");

        final var ordered = this.selector.order(List.of(newest, oldest), new SiftScope.OldestN(50));

        assertThat(ordered).containsExactly(oldest, newest);
    }

    private static SiftCandidate candidate(final String name, final String instant) {
        return new SiftCandidate(Path.of(name), Instant.parse(instant));
    }
}
