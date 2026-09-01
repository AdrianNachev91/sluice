package photos.sluice.adapter.cli;

import photos.sluice.domain.model.SortSummary;

import java.util.List;

/**
 * The wire shape for what a sort did, and the reading that builds it.
 *
 * <p>Every field comes from what the engine answered. Nothing here parses a message or invents a
 * number. So a caller acts on what the sort decided rather than on how this surface happened to
 * word it.
 */
public final class SortPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private SortPayloads() {
    }

    /**
     * What one sort did.
     *
     * <p>Every count above {@code dateFilesRemoved} adds up to {@code processed}, which is the
     * record's own rule rather than this surface's. Date files sit outside that sum, because one of
     * them can belong to several photos and counting them in would count those photos twice.
     *
     * @param processed int files this run dealt with
     * @param photosSorted int photos filed under a year
     * @param videosSorted int videos filed under a year
     * @param alreadyInLibrary int Inbox copies removed because the library already holds them
     * @param identicalCopies int byte-identical copies within this batch, all but one removed
     * @param setAsideForReview int files too small or too low-resolution to sift
     * @param couldNotBeDated int files with no date that could be trusted
     * @param dateFilesRemoved int date files removed once every photo they named had gone
     * @param lowConfidenceDates int files whose date came off the file's own timestamp rather than
     *        off the photo. They are filed like any other. Of the counts adding up to
     *        {@code processed}, this overlaps three: {@code photosSorted}, {@code videosSorted}
     *        and {@code setAsideForReview}
     * @param yearsSorted a {@link List} of {@link Integer} the years anything landed in
     * @param datesMayBeWrong boolean whether an export's date files were present but almost none
     *        paired to a photo. The whole run is then dated off weaker signals
     * @param stopped boolean whether the run gave up before the end of its scope
     * @param leftBehind int in-scope files the run never reached, still in the Inbox
     */
    public record SortedPayload(int processed, int photosSorted, int videosSorted, int alreadyInLibrary,
                                int identicalCopies, int setAsideForReview, int couldNotBeDated,
                                int dateFilesRemoved, int lowConfidenceDates, List<Integer> yearsSorted,
                                boolean datesMayBeWrong, boolean stopped, int leftBehind) {
    }

    /**
     * Reads what a sort did onto the wire.
     *
     * <p>The years are sorted here because the engine answers with a set. A caller reading two runs
     * of the same shape should not meet them in two orders.
     *
     * <p>What tripped a warning becomes a flag rather than the sentence the engine wrote. That
     * sentence names the date files an export writes, and the shape of what is inside them. An
     * agent would have to parse prose to act on it. The flag is the same fact in a field.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return {@link SortedPayload} its machine-readable shape
     */
    public static SortedPayload sorted(final SortSummary sorted) {
        return new SortedPayload(sorted.processed(), sorted.photosSorted(), sorted.videosSorted(),
                sorted.reimportsDeleted(), sorted.byteDupsDeleted(), sorted.lowRes(), sorted.unsorted(),
                sorted.sidecarsDeleted(), sorted.lowConfidenceFiles().size(),
                sorted.yearsSorted().stream().sorted().toList(),
                !sorted.warnings().isEmpty(), sorted.cancelled(), sorted.leftBehind());
    }
}
