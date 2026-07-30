package photos.sluice.domain.dedup;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.dedup.ByteIdenticalDedup.DedupPlan;
import photos.sluice.domain.model.HashedMedia;
import photos.sluice.domain.model.MediaFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ByteIdenticalDedupTest {

    private final ByteIdenticalDedup dedup = new ByteIdenticalDedup();

    @Test
    void distinctHashesAllGoToSortInOrder() {
        final HashedMedia a = hashed("a.jpg", "hashA");
        final HashedMedia b = hashed("b.jpg", "hashB");

        final DedupPlan plan = this.dedup.plan(List.of(a, b), Set.of());

        assertThat(plan.toSort()).containsExactly(a.file(), b.file());
        assertThat(plan.redundantVsLibrary()).isEmpty();
        assertThat(plan.withinBatchDuplicates()).isEmpty();
    }

    @Test
    void secondOccurrenceOfSameHashWithinBatchIsMarkedDuplicate() {
        final HashedMedia first = hashed("a.jpg", "sameHash");
        final HashedMedia second = hashed("a-copy.jpg", "sameHash");

        final DedupPlan plan = this.dedup.plan(List.of(first, second), Set.of());

        assertThat(plan.toSort()).containsExactly(first.file());
        assertThat(plan.withinBatchDuplicates()).containsExactly(second.file());
    }

    @Test
    void hashPresentInLibraryIsMarkedRedundant() {
        final HashedMedia inLibrary = hashed("a.jpg", "libHash");

        final DedupPlan plan = this.dedup.plan(List.of(inLibrary), Set.of("libHash"));

        assertThat(plan.redundantVsLibrary()).containsExactly(inLibrary.file());
        assertThat(plan.toSort()).isEmpty();
    }

    @Test
    void libraryRedundancyTakesPriorityOverBatchDedupForEveryOccurrence() {
        final HashedMedia first = hashed("a.jpg", "libHash");
        final HashedMedia second = hashed("a-copy.jpg", "libHash");

        final DedupPlan plan = this.dedup.plan(List.of(first, second), Set.of("libHash"));

        assertThat(plan.redundantVsLibrary()).containsExactly(first.file(), second.file());
        assertThat(plan.toSort()).isEmpty();
        assertThat(plan.withinBatchDuplicates()).isEmpty();
    }

    @Test
    void mixedBatchPartitionsEachFileIntoTheCorrectBucketWithoutCrossContamination() {
        final HashedMedia distinct = hashed("distinct.jpg", "distinctHash");
        final HashedMedia dupFirst = hashed("dup1.jpg", "batchDupHash");
        final HashedMedia dupSecond = hashed("dup2.jpg", "batchDupHash");
        final HashedMedia libRedundant = hashed("lib.jpg", "libHash");

        final DedupPlan plan = this.dedup.plan(List.of(distinct, dupFirst, dupSecond, libRedundant), Set.of("libHash"));

        assertThat(plan.toSort()).containsExactly(distinct.file(), dupFirst.file());
        assertThat(plan.withinBatchDuplicates()).containsExactly(dupSecond.file());
        assertThat(plan.redundantVsLibrary()).containsExactly(libRedundant.file());
    }

    @Test
    void emptyInputProducesEmptyPlan() {
        final DedupPlan plan = this.dedup.plan(List.of(), Set.of());

        assertThat(plan.toSort()).isEmpty();
        assertThat(plan.redundantVsLibrary()).isEmpty();
        assertThat(plan.withinBatchDuplicates()).isEmpty();
    }

    private static HashedMedia hashed(final String fileName, final String sha256) {
        return new HashedMedia(new MediaFile(Path.of(fileName)), sha256);
    }
}
