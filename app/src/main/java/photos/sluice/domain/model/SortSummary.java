package photos.sluice.domain.model;

import java.util.List;

// Outcome counters and filename lists from one sort run. processed always equals the sum of
// reimportsDeleted + byteDupsDeleted + photosSorted + videosSorted + lowRes + unsorted - every
// in-scope file lands in exactly one of those six buckets. Returned by SortUseCase; printing a
// human-readable report from it is a caller's job, not this type's.
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
        List<String> unsortedFiles) {

    public SortSummary {
        lowConfidenceFiles = List.copyOf(lowConfidenceFiles);
        unsortedFiles = List.copyOf(unsortedFiles);
    }
}
