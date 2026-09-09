package photos.sluice.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortSummaryTest {

    @Test
    void processedEqualToTheSixBucketsAddedTogetherIsAccepted() {
        final SortSummary summary = summary(6, 1, 1, 1, 1, 1, 1);

        assertThat(summary.processed()).isEqualTo(6);
    }

    @Test
    void anAllZeroSummaryIsAccepted() {
        final SortSummary summary = summary(0, 0, 0, 0, 0, 0, 0);

        assertThat(summary.processed()).isZero();
    }

    @Test
    void processedAboveTheSixBucketsAddedTogetherThrows() {
        assertThatThrownBy(() -> summary(7, 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processed=7")
                .hasMessageContaining("sum=6");
    }

    @Test
    void processedBelowTheSixBucketsAddedTogetherThrows() {
        assertThatThrownBy(() -> summary(5, 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRejectionMessageNamesEveryBucketAndItsValue() {
        assertThatThrownBy(() -> summary(0, 1, 2, 3, 4, 5, 6))
                .hasMessageContaining("reimportsDeleted=1")
                .hasMessageContaining("byteDupsDeleted=2")
                .hasMessageContaining("photosSorted=3")
                .hasMessageContaining("videosSorted=4")
                .hasMessageContaining("lowRes=5")
                .hasMessageContaining("unsorted=6");
    }

    @Test
    void sidecarsDeletedIsOutsideTheSum() {
        final SortSummary summary = new SortSummary(1, 0, 0, 1, 0, 0, 0, 4,
                List.of(), SortSummary.Guessed.NONE, List.of(), Set.of(), List.of(), false, 0);

        assertThat(summary.sidecarsDeleted()).isEqualTo(4);
    }

    private static SortSummary summary(final int processed, final int reimportsDeleted, final int byteDupsDeleted,
                                       final int photosSorted, final int videosSorted, final int lowRes,
                                       final int unsorted) {
        return new SortSummary(processed, reimportsDeleted, byteDupsDeleted, photosSorted, videosSorted, lowRes,
                unsorted, 0, List.of(), SortSummary.Guessed.NONE, List.of(), Set.of(), List.of(), false, 0);
    }
}
