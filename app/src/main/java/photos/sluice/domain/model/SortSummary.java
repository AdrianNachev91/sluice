package photos.sluice.domain.model;

import java.util.List;
import java.util.Set;

/**
 * Outcome counters and filename lists from one sort run, returned by the sort use case. Every file
 * that actually left the Inbox lands in exactly one of six outcome buckets: {@code reimportsDeleted},
 * {@code byteDupsDeleted}, {@code photosSorted}, {@code videosSorted}, {@code lowRes}, and
 * {@code unsorted}. The constructor rejects any instance whose {@code processed} count does not
 * equal those six added together, so a caller may read {@code processed} as that sum.
 *
 * <p>The {@code yearsSorted} field is every distinct year a file actually landed in under Sorted
 * (Photos or Videos) this run. A {@link SortScope.Year} or {@link SortScope.OldestYear} scope
 * always yields at most one entry, since both narrow to a single year before routing anything.
 * Empty means nothing reached Sorted at all. This field is how a caller learns which year an
 * auto-resolved {@link SortScope.OldestYear} scope actually picked, since nothing else reports it.
 *
 * <p>{@code warnings} carries conditions worth a human's attention that stopped nothing. One is the
 * pairing canary, which fires when Takeout sidecars were present and almost none of them paired to
 * a scanned media file. Empty means nothing tripped one.
 *
 * <p>{@code cancelled} is the run's own account of whether it stopped short.
 *
 * <p>{@code leftBehind} counts the in-scope files still in the Inbox. It is zero on a run that
 * stopped before it had a plan to route, since nothing was ever picked to move.
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
        LowConfidenceCounts lowConfidenceCounts,
        List<String> unsortedFiles,
        Set<Integer> yearsSorted,
        List<String> warnings,
        boolean cancelled,
        int leftBehind) {

    /**
     * How many files in each bucket that can hold one were dated from the file's own timestamp.
     *
     * <p>Three of the six buckets, and only three. {@code unsorted} holds what nothing could date,
     * so it carries no date to doubt. {@code reimportsDeleted} and {@code byteDupsDeleted} are
     * settled on a hash before anything is dated at all.
     *
     * <p>Each is a part of the bucket it names rather than a bucket of its own. So none of them is
     * in {@code processed}'s sum, and adding one would count those files twice.
     *
     * @param photosSorted int photos filed under a year and month off a timestamp
     * @param videosSorted int videos filed the same way
     * @param lowRes int files set aside for review whose date came the same way
     */
    public record LowConfidenceCounts(int photosSorted, int videosSorted, int lowRes) {

        /** Every date this run resolved came off the photo rather than off the file. */
        public static final LowConfidenceCounts NONE = new LowConfidenceCounts(0, 0, 0);

        /**
         * How many files this run dated from a timestamp, wherever it then put them.
         *
         * @return int the three added together
         */
        public int total() {
            return this.photosSorted + this.videosSorted + this.lowRes;
        }
    }

    /**
     * Validates that processed equals the six outcome buckets added together, then defensively
     * copies the mutable list/set components.
     *
     * @param processed int total in-scope files processed this run
     * @param reimportsDeleted int redundant Inbox files deleted as already in library
     * @param byteDupsDeleted int byte-identical duplicates deleted within the batch
     * @param photosSorted int photos routed into Sorted
     * @param videosSorted int videos routed into Sorted
     * @param lowRes int files routed to the low-res bucket
     * @param unsorted int files routed to Review\Unsorted
     * @param sidecarsDeleted int consumed Takeout JSON sidecars deleted
     * @param lowConfidenceFiles a {@link List} of {@link String} filenames dated off the file's own
     * timestamp, wherever this run then put them
     * @param lowConfidenceCounts {@link LowConfidenceCounts} how many of those went to each bucket that can hold one
     * @param unsortedFiles a {@link List} of {@link String} filenames routed to Review\Unsorted
     * @param yearsSorted a {@link Set} of {@link Integer} distinct years any file landed in this run
     * @param warnings a {@link List} of {@link String} conditions worth attention that stopped nothing
     * @param cancelled boolean whether the run gave up before reaching the end of its scope
     * @param leftBehind int in-scope files the routing pass did not reach, still in the Inbox
     */
    public SortSummary {
        // sidecarsDeleted is deliberately outside the sum. A sidecar is metadata, not media, and
        // one JSON can be shared by several media files, so it does not partition the same set.
        final int bucketed = reimportsDeleted + byteDupsDeleted + photosSorted + videosSorted + lowRes + unsorted;
        if (processed != bucketed) {
            throw new IllegalArgumentException(("processed must equal the six outcome buckets added together: "
                    + "processed=%d, sum=%d (reimportsDeleted=%d, byteDupsDeleted=%d, photosSorted=%d, "
                    + "videosSorted=%d, lowRes=%d, unsorted=%d)")
                    .formatted(processed, bucketed, reimportsDeleted, byteDupsDeleted, photosSorted, videosSorted,
                            lowRes, unsorted));
        }
        // Two readings of one set: the names, and where each of them went. A record whose halves
        // disagree has parts that do not add up.
        if (lowConfidenceCounts.total() != lowConfidenceFiles.size()) {
            throw new IllegalArgumentException(("guessed must account for every low-confidence file: "
                    + "files=%d, guessed=%d (photosSorted=%d, videosSorted=%d, lowRes=%d)")
                    .formatted(lowConfidenceFiles.size(), lowConfidenceCounts.total(), lowConfidenceCounts.photosSorted(),
                            lowConfidenceCounts.videosSorted(), lowConfidenceCounts.lowRes()));
        }
        lowConfidenceFiles = List.copyOf(lowConfidenceFiles);
        unsortedFiles = List.copyOf(unsortedFiles);
        yearsSorted = Set.copyOf(yearsSorted);
        warnings = List.copyOf(warnings);
    }
}
