package photos.sluice.application.service;

import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.job.ProgressCallback;

// Brackets a phase's progress events (phaseStarted -> ticks -> phaseFinished) around one engine
// call. Not a Spring bean - Pipeline, CullEngine, and CurateEngine each own their own instance,
// built from the same ProgressPort they already receive.
final class PhaseRunner {

    private final ProgressPort progressPort;

    /**
     * Creates a phase runner that reports through the given progress port.
     *
     * @param progressPort {@link ProgressPort} sink for phase start/tick/finish events
     */
    PhaseRunner(ProgressPort progressPort) {
        this.progressPort = progressPort;
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
    <T> T run(String phase, PhaseWork<T> work) throws Exception {
        progressPort.phaseStarted(phase);
        try {
            return work.run((current, total) -> progressPort.tick(phase, current, total));
        } finally {
            progressPort.phaseFinished(phase);
        }
    }

    // Function<ProgressCallback, T> can't wrap cullDispatcher.cull()/applyEngine.apply(), both of
    // which declare checked exceptions. Declares throws Exception itself instead, the same shape
    // JobWork already uses for the same reason. A lambda that throws nothing still satisfies it.
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
