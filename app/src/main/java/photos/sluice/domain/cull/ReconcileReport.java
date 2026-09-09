package photos.sluice.domain.cull;

import java.util.List;

/**
 * {@code ReconcileEngine.reconcile()}'s outcome. A move-log rebuild sweeps every already-validated
 * decision and unreviewable file against actual disk state.
 *
 * <p>It exists because move-records.log, the record {@code classify()} would otherwise trust, may
 * itself be missing or corrupt.
 *
 * <p>A reconstructed file is one found at the exact destination its own decision would have
 * produced, and its hash is recorded there. A skipped one is left settled rather than raised again.
 *
 * <p>choicesLost says the run found the choices file undecodable and filed it away. Every answer it
 * held is gone, and each finding those answers had settled will be raised again. This is the one
 * way a reconcile costs the user an answer, so a report that carries it has to say so out loud.
 */
public record ReconcileReport(int reconstructed, int stillPending, int skipped,
                              List<Finding.MissingSource> missingSource, boolean choicesLost) {

    /**
     * Defensively copies the mutable findings list.
     *
     * @param reconstructed int files reconcile() newly confirmed moved at their destination
     * @param stillPending int files still sitting untouched at their original location
     * @param skipped int files an earlier user answer had already given up on
     * @param missingSource a {@link List} of {@link Finding.MissingSource} files reconcile() could not account for
     * @param choicesLost boolean whether an undecodable choices file was filed away, losing every answer in it
     */
    public ReconcileReport {
        missingSource = List.copyOf(missingSource);
    }
}
