package photos.sluice.application.port.out;

import java.util.List;

/**
 * The effect boundary a running job uses to report what it's doing, so a driving adapter (the
 * desktop dashboard, a console logger) can render it without polling. Only one job is active at a
 * time, so no event carries a job id; every event describes the current job's own progress.
 *
 * <p>{@link #phasesPlanned} opens a job's reporting, ahead of every other event here. Each job
 * announces once, so a listener holding the previous job's list replaces it rather than adding to
 * it.
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
     * The phases this job means to report, in the order it means to report them, announced before
     * it starts the first one.
     *
     * <p>What it promises is the order, not that every entry is reached. A job stopped part way
     * through, or one that finds a phase has nothing to do, simply never starts the rest. So an
     * entry here that never arrives at {@link #phaseStarted} is an ordinary end to a job, and the
     * list is never revised to drop it.
     *
     * <p>The list is empty for a job that reports no phases at all, which is how such a job says
     * so. Silence would leave a listener showing the phases of the job before it.
     *
     * @param phases a {@link List} of {@link String} the phase labels, in order
     */
    default void phasesPlanned(final List<String> phases) {
    }

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
