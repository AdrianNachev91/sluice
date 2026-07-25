package photos.sluice.domain.model;

import java.util.List;
import java.util.Set;

// Outcome counters and filename lists from one sort run. processed always equals the sum of
// reimportsDeleted + byteDupsDeleted + photosSorted + videosSorted + lowRes + unsorted - every
// in-scope file lands in exactly one of those six buckets. Returned by SortUseCase; printing a
// human-readable report from it is a caller's job, not this type's.
//
// yearsSorted is every distinct year a file actually landed in under Sorted (Photos or Videos)
// this run. A Year or OldestYear scope always yields at most one, since both narrow to a single
// year before routing anything. Empty means nothing reached Sorted at all. Pipeline.curate() reads
// this to learn which year an auto-resolved OldestYear scope actually picked, since nothing else
// reports it.
public record SortSummary(
        int processed,
        int reimportsDeleted,
        int byteDupsDeleted,
        int photosSorted,
        int videosSorted,
        int lowRes,
        int unsorted,
        int sidecarsDeleted,
        List<String> lowConfidenceFiles,
        List<String> unsortedFiles,
        Set<Integer> yearsSorted) {

    public SortSummary {
        lowConfidenceFiles = List.copyOf(lowConfidenceFiles);
        unsortedFiles = List.copyOf(unsortedFiles);
        yearsSorted = Set.copyOf(yearsSorted);
    }
}
