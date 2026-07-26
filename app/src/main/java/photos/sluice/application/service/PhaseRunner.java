package photos.sluice.application.service;

import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.job.ProgressCallback;

// Brackets a phase's progress events (phaseStarted -> ticks -> phaseFinished) around one engine
// call. Not a Spring bean - Pipeline, CullEngine, and CurateEngine each own their own instance,
// built from the same ProgressPort they already receive.
final class PhaseRunner {

    private final ProgressPort progressPort;

    PhaseRunner(ProgressPort progressPort) {
        this.progressPort = progressPort;
    }

    // phaseFinished fires in a finally so the phaseStarted/phaseFinished bracket always closes,
    // even when the engine call itself throws. A listener otherwise has no signal the phase ever
    // ended.
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
        T run(ProgressCallback progress) throws Exception;
    }
}
