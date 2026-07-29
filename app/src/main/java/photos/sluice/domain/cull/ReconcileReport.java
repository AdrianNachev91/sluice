package photos.sluice.domain.cull;

import java.util.List;

/**
 * {@code ReconcileEngine.reconcile()}'s outcome. A move-log rebuild sweeps every already-validated
 * decision and unreviewable file against actual disk state. It exists because move-records.log, the
 * record {@code classify()} would otherwise trust, may itself be missing or corrupt. reconstructed counts
 * a file reconcile() located at the exact destination the same decision would have produced,
 * recording its hash there. stillPending counts a file still sitting exactly where the culler found
 * it - untouched, nothing to reconstruct. missingSource is every decision or unreviewable file
 * reconcile() could account for neither way; PrepDirDoctor's CHOICE remedies pick up from there.
 */
public record ReconcileReport(int reconstructed, int stillPending, List<Finding.MissingSource> missingSource) {

    /**
     * Defensively copies the mutable findings list.
     *
     * @param reconstructed int files reconcile() newly confirmed moved at their destination
     * @param stillPending int files still sitting untouched at their original location
     * @param missingSource a {@link List} of {@link Finding.MissingSource} files reconcile() could not account for
     */
    public ReconcileReport {
        missingSource = List.copyOf(missingSource);
    }
}
