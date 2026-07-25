package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.util.function.Function;

// Wires the mechanical engines through JobRunner so a driving adapter (the JavaFX UI, a future CLI)
// gets a JobHandle back instead of blocking, with progress bracketed through ProgressPort around
// each engine call. Depends on the engines' concrete classes rather than their SortUseCase/
// CommitUseCase/RescueUseCase port/in interfaces because the progress-callback overloads live only
// on the concrete types, not on those narrower interfaces.
@Component
public class Pipeline {

    private static final String SORTING = "Sorting...";
    private static final String COMMITTING = "Committing...";
    private static final String RESCUING = "Rescuing...";

    private final SortEngine sortEngine;
    private final CommitEngine commitEngine;
    private final RescueEngine rescueEngine;
    private final JobRunner jobRunner;
    private final ProgressPort progressPort;

    public Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            JobRunner jobRunner, ProgressPort progressPort) {
        this.sortEngine = sortEngine;
        this.commitEngine = commitEngine;
        this.rescueEngine = rescueEngine;
        this.jobRunner = jobRunner;
        this.progressPort = progressPort;
    }

    public JobHandle<SortSummary> sort(SortScope scope) {
        return jobRunner.submit(_ -> runPhase(SORTING, progress -> sortEngine.sort(scope, progress)));
    }

    public JobHandle<CommitSummary> commit(CommitScope scope) {
        return jobRunner.submit(_ -> runPhase(COMMITTING, progress -> commitEngine.commit(scope, progress)));
    }

    public JobHandle<RescueSummary> rescue(String reviewFolder) {
        return jobRunner.submit(_ -> runPhase(RESCUING, progress -> rescueEngine.rescue(reviewFolder, progress)));
    }

    // phaseFinished fires in a finally so the phaseStarted/phaseFinished bracket always closes, even
    // when the engine call itself throws - a listener otherwise has no signal the phase ever ended.
    private <T> T runPhase(String phase, Function<ProgressCallback, T> work) {
        progressPort.phaseStarted(phase);
        try {
            return work.apply((current, total) -> progressPort.tick(phase, current, total));
        } finally {
            progressPort.phaseFinished(phase);
        }
    }
}
