package photos.sluice.domain.model;

import java.util.List;
import java.util.Set;

/**
 * Outcome counters and filename lists from one sort run, returned by the sort use case. The
 * {@code processed} count is always the sum of six other counters: {@code reimportsDeleted},
 * {@code byteDupsDeleted}, {@code photosSorted}, {@code videosSorted}, {@code lowRes}, and
 * {@code unsorted}. Every in-scope file lands in exactly one of those six buckets. Printing a
 * human-readable report from this record is a caller's job, not this type's.
 *
 * <p>The {@code yearsSorted} field is every distinct year a file actually landed in under Sorted
 * (Photos or Videos) this run. A {@link SortScope.Year} or {@link SortScope.OldestYear} scope
 * always yields at most one entry, since both narrow to a single year before routing anything.
 * Empty means nothing reached Sorted at all. A curate run reads this field to learn which year an
 * auto-resolved {@link SortScope.OldestYear} scope actually picked, since nothing else reports it.
 *
 * <p>{@code warnings} carries conditions worth a human's attention that stopped nothing: today,
 * only the pairing canary firing when Takeout sidecars were present but almost none of them paired
 * to a scanned media file. Empty means nothing tripped it.
 */
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
        Set<Integer> yearsSorted,
        List<String> warnings) {

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
     * @param warnings a {@link List} of {@link String} conditions worth attention that stopped nothing
     */
    public SortSummary {
        lowConfidenceFiles = List.copyOf(lowConfidenceFiles);
        unsortedFiles = List.copyOf(unsortedFiles);
        yearsSorted = Set.copyOf(yearsSorted);
        warnings = List.copyOf(warnings);
    }
}
