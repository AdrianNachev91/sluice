package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.job.ProgressCallback;

import java.util.List;
import java.util.function.Function;

/**
 * Announces the phases a job will report, then brackets each one it runs, {@code phaseStarted} then
 * ticks then {@code phaseFinished}, around one engine call.
 */
final class PhaseRunner {

    private final ProgressPort progressPort;

    /**
     * Creates a phase runner that reports through the given progress port.
     *
     * @param progressPort {@link ProgressPort} sink for phase start/tick/finish events
     */
    PhaseRunner(final ProgressPort progressPort) {
        this.progressPort = progressPort;
    }

    /**
     * Announces the phases a job means to run, ahead of its first bracket.
     *
     * @param phases a {@link List} of {@link String} the phase labels, in order
     */
    void planned(final List<String> phases) {
        this.progressPort.phasesPlanned(phases);
    }

    /**
     * phaseFinished fires in a finally so the phaseStarted/phaseFinished bracket always closes,
     * even when the engine call itself throws. A listener otherwise has no signal the phase ever
     * ended.
     *
     * @param phase {@link String} name of the phase being run
     * @param work a {@link PhaseWork} of T the engine call to bracket
     * @return T the engine call's result
     */
    <T> T run(final String phase, final PhaseWork<T> work) throws Exception {
        this.progressPort.phaseStarted(phase);
        try {
            return work.run(this.reporting(phase));
        } finally {
            this.progressPort.phaseFinished(phase);
        }
    }

    /**
     * The same bracket around work that throws nothing checked.
     *
     * <p>Apart from {@link #run} so that a stage throwing nothing checked need not declare
     * {@code throws Exception} to report its own phases.
     *
     * <p>T may be null, which is how a stage reports that it stopped before finishing.
     *
     * @param phase {@link String} name of the phase being run
     * @param work a {@link Function} of {@link ProgressCallback} to T the stage to bracket
     * @param <T> the type of result the stage produces
     * @return T the stage's result
     */
    <T extends @Nullable Object> T around(final String phase, final Function<ProgressCallback, T> work) {
        this.progressPort.phaseStarted(phase);
        try {
            return work.apply(this.reporting(phase));
        } finally {
            this.progressPort.phaseFinished(phase);
        }
    }

    /**
     * The callback one phase's work reports through.
     *
     * <p>An anonymous class rather than a lambda, and it has to stay one. {@link ProgressCallback}
     * carries {@code partOf} as a default, so a lambda compiles, satisfies the interface, and
     * silently reports no partial readings at all.
     *
     * @param phase {@link String} name of the phase being run
     * @return {@link ProgressCallback} what the work ticks
     */
    private ProgressCallback reporting(final String phase) {
        return new ProgressCallback() {
            @Override
            public void tick(final int current, final int total) {
                PhaseRunner.this.progressPort.tick(phase, current, total);
            }

            @Override
            public void partOf(final int current, final int total, final double partDone) {
                PhaseRunner.this.progressPort.tickWithin(phase, current, total, partDone);
            }
        };
    }

    /**
     * The engine call {@link PhaseRunner#run} brackets with progress events. Declares
     * {@code throws Exception} so that it can wrap an engine call declaring a checked one, which a
     * plain {@code Function<ProgressCallback, T>} cannot. A lambda that throws nothing still
     * satisfies it.
     *
     * @param <T> the type of result the engine call produces
     */
    @FunctionalInterface
    interface PhaseWork<T> {
        /**
         * Runs the engine call, reporting progress through the given callback.
         *
         * @param progress {@link ProgressCallback} callback for reporting tick progress
         * @return T the engine call's result
         */
        T run(ProgressCallback progress) throws Exception;
    }
}
