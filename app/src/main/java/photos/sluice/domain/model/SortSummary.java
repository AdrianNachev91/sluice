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

    /**
     * Defensively copies the mutable list/set components.
     *
     * @param processed int total in-scope files processed this run
     * @param reimportsDeleted int redundant Inbox files deleted as already in library
     * @param byteDupsDeleted int byte-identical duplicates deleted within the batch
     * @param photosSorted int photos routed into Sorted
     * @param videosSorted int videos routed into Sorted
     * @param lowRes int files routed to the low-res bucket
     * @param unsorted int files routed to Review\Unsorted
     * @param sidecarsDeleted int consumed Takeout JSON sidecars deleted
     * @param lowConfidenceFiles a {@link List} of {@link String} filenames sorted on a low-confidence date
     * @param unsortedFiles a {@link List} of {@link String} filenames routed to Review\Unsorted
     * @param yearsSorted a {@link Set} of {@link Integer} distinct years any file landed in this run
     */
    public SortSummary {
        lowConfidenceFiles = List.copyOf(lowConfidenceFiles);
        unsortedFiles = List.copyOf(unsortedFiles);
        yearsSorted = Set.copyOf(yearsSorted);
    }
}
