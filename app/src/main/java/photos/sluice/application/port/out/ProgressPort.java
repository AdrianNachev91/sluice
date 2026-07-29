package photos.sluice.application.port.out;

/**
 * The effect boundary a running job uses to report what it's doing, so a driving adapter (the
 * desktop dashboard, a console logger) can render it without polling. Only one job is active at a
 * time, so no event carries a job id; every event describes the current job's own progress.
 *
 * <p>{@link #phaseStarted} and {@link #phaseFinished} always bracket a phase. That holds even when
 * a phase's total is zero and it never ticks. A listener can then tell "not started yet" from
 * "done with nothing to do". The {@code phase} parameter is a short human-readable label the
 * caller controls directly ("Sorting...", "Building montages...", "Applying decisions...") rather
 * than a code a listener has to translate.
 */
public interface ProgressPort {

    /**
     * Signals that a phase has begun.
     *
     * @param phase {@link String} short human-readable label for the phase
     */
    void phaseStarted(String phase);

    /**
     * One already-sized unit of the named phase finished, e.g. phase="Sorting...", current=850,
     * total=1204.
     *
     * @param phase {@link String} short human-readable label for the phase
     * @param current int units completed so far
     * @param total int total units in the phase
     */
    void tick(String phase, int current, int total);

    /**
     * Signals that a phase has finished.
     *
     * @param phase {@link String} short human-readable label for the phase
     */
    void phaseFinished(String phase);
}
