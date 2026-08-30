package photos.sluice.application.port.out;

/**
 * The effect boundary a running job uses to report what it's doing, so a driving adapter (the
 * desktop dashboard, a console logger) can render it without polling. Only one job is active at a
 * time, so no event carries a job id; every event describes the current job's own progress.
 *
 * <p>{@link #phaseStarted} and {@link #phaseFinished} always bracket a phase. That holds even when
 * a phase's total is zero and it never ticks. A listener can then tell "not started yet" from
 * "done with nothing to do". The {@code phase} parameter is a short human-readable label the
 * caller controls directly ("Sorting...", "Reading photos...", "Applying decisions...") rather
 * than a code a listener has to translate.
 */
public interface ProgressPort {

    /** Reports nowhere, for a caller running an engine without watching it. */
    ProgressPort NO_OP = new ProgressPort() {
        @Override
        public void phaseStarted(final String phase) {
        }

        @Override
        public void tick(final String phase, final int current, final int total) {
        }

        @Override
        public void phaseFinished(final String phase) {
        }
    };

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
     * How far into the unit now being worked on the phase has reached.
     *
     * <p>Ignored unless an implementation takes it up. Falling back to {@link #tick} would be worse
     * than silence: the unit reports itself when it finishes, so each partial reading would arrive
     * as a second announcement of a unit that has not moved.
     *
     * @param phase {@link String} short human-readable label for the phase
     * @param current int units completed before this one
     * @param total int total units in the phase
     * @param partDone double how much of the current unit is done, from 0 to 1
     */
    default void tickWithin(final String phase, final int current, final int total,
                            final double partDone) {
    }

    /**
     * Signals that a phase has finished.
     *
     * @param phase {@link String} short human-readable label for the phase
     */
    void phaseFinished(String phase);
}
