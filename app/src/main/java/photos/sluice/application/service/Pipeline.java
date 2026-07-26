package photos.sluice.application.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// The public facade wiring the mechanical engines through JobRunner so a driving adapter (the
// JavaFX UI, a future CLI) gets a JobHandle back instead of blocking. Progress is bracketed through
// ProgressPort around each engine call, via PhaseRunner. Cull/curate orchestration itself lives in
// CullEngine/CurateEngine; Pipeline builds and wires both, and exposes their methods under one
// type so a driving adapter depends on a single class. Depends on the engines' concrete classes
// rather than their SortUseCase/CommitUseCase/RescueUseCase port/in interfaces - the
// progress-callback overloads live only on the concrete types, not on those narrower interfaces.
@Component
public class Pipeline {

    private static final String SORTING = "Sorting...";
    private static final String COMMITTING = "Committing...";
    private static final String RESCUING = "Rescuing...";

    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode/watchTimeout, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. See the package-private
    // constructor overload for how tests override it.
    private static final Duration DEFAULT_WATCH_POLL_INTERVAL = Duration.ofSeconds(2);

    private final SortEngine sortEngine;
    private final CommitEngine commitEngine;
    private final RescueEngine rescueEngine;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final CullEngine cullEngine;
    private final CurateEngine curateEngine;

    /**
     * Explicit @Autowired: Spring's implicit single-constructor injection only kicks in when a
     * class has exactly one constructor. The package-private test-seam overload below means there
     * are two, so this one has to be named as the one Spring should use.
     *
     * @param sortEngine {@link SortEngine} the sort engine
     * @param commitEngine {@link CommitEngine} the commit engine
     * @param rescueEngine {@link RescueEngine} the rescue engine
     * @param montageRenderer {@link MontageRenderer} renders cull contact-sheet montages
     * @param cullDispatcher {@link CullDispatcher} dispatches cull decisions to the vision agent
     * @param applyEngine {@link ApplyEngine} applies merged cull decisions
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param montageConfig {@link MontageConfig} montage grid sizing configuration
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     */
    @Autowired
    public Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            MontageRenderer montageRenderer, CullDispatcher cullDispatcher, ApplyEngine applyEngine,
            CullPrepPort cullPrepPort, CullSettings cullSettings, MediaStore mediaStore, PathsPort pathsPort,
            MontageConfig montageConfig, JobRunner jobRunner, ProgressPort progressPort) {
        this(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine, cullPrepPort,
                cullSettings, mediaStore, pathsPort, montageConfig, jobRunner, progressPort,
                DEFAULT_WATCH_POLL_INTERVAL);
    }

    /**
     * Test seam: production wiring always goes through the public constructor above, which fixes
     * the poll cadence at DEFAULT_WATCH_POLL_INTERVAL. Tests exercising real watch-mode timing pass
     * a much shorter interval here so the behavior proves out in milliseconds, not seconds, without
     * resorting to a mock clock.
     *
     * @param sortEngine {@link SortEngine} the sort engine
     * @param commitEngine {@link CommitEngine} the commit engine
     * @param rescueEngine {@link RescueEngine} the rescue engine
     * @param montageRenderer {@link MontageRenderer} renders cull contact-sheet montages
     * @param cullDispatcher {@link CullDispatcher} dispatches cull decisions to the vision agent
     * @param applyEngine {@link ApplyEngine} applies merged cull decisions
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param montageConfig {@link MontageConfig} montage grid sizing configuration
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param watchPollInterval {@link Duration} how often a watch-mode job re-checks its prep dir
     */
    Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            MontageRenderer montageRenderer, CullDispatcher cullDispatcher, ApplyEngine applyEngine,
            CullPrepPort cullPrepPort, CullSettings cullSettings, MediaStore mediaStore, PathsPort pathsPort,
            MontageConfig montageConfig, JobRunner jobRunner, ProgressPort progressPort, Duration watchPollInterval) {
        this.sortEngine = sortEngine;
        this.commitEngine = commitEngine;
        this.rescueEngine = rescueEngine;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.cullEngine = new CullEngine(montageRenderer, cullDispatcher, applyEngine, cullPrepPort, cullSettings,
                mediaStore, pathsPort, montageConfig, jobRunner, progressPort, watchPollInterval);
        this.curateEngine = new CurateEngine(sortEngine, jobRunner, progressPort, cullEngine);
    }

    /**
     * Delegates to CullEngine, which does the real work - see its own doc. Public and callable
     * directly (not just via @PostConstruct) so a test can drive it without a Spring context.
     */
    @PostConstruct
    public void armWatchesForExistingWaitingJobs() {
        cullEngine.armWatchesForExistingWaitingJobs();
    }

    /**
     * Runs a sort job as a cancellable background job.
     *
     * @param scope {@link SortScope} which files to sort
     * @return a {@link JobHandle} of {@link SortSummary} a handle to the running job
     */
    public JobHandle<SortSummary> sort(SortScope scope) {
        return jobRunner.submit(handle -> runPhase(SORTING,
                progress -> sortEngine.sort(scope, progress, handle::isCancellationRequested)));
    }

    /**
     * Runs a commit job as a cancellable background job.
     *
     * @param scope {@link CommitScope} which files to commit
     * @return a {@link JobHandle} of {@link CommitSummary} a handle to the running job
     */
    public JobHandle<CommitSummary> commit(CommitScope scope) {
        return jobRunner.submit(handle -> runPhase(COMMITTING,
                progress -> commitEngine.commit(scope, progress, handle::isCancellationRequested)));
    }

    /**
     * Runs a rescue job as a cancellable background job.
     *
     * @param reviewFolder {@link String} the Review folder to promote
     * @return a {@link JobHandle} of {@link RescueSummary} a handle to the running job
     */
    public JobHandle<RescueSummary> rescue(String reviewFolder) {
        return jobRunner.submit(handle -> runPhase(RESCUING,
                progress -> rescueEngine.rescue(reviewFolder, progress, handle::isCancellationRequested)));
    }

    /**
     * Delegates to CullEngine to run a cull job.
     *
     * @param scope {@link CullScope} which files to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    public JobHandle<CullJobOutcome> cull(CullScope scope) {
        return cullEngine.cull(scope);
    }

    /**
     * Delegates to CurateEngine to run a sort followed by a cull.
     *
     * @param scope {@link SortScope} which files to curate
     * @return a {@link JobHandle} of {@link CurateOutcome} a handle to the running job
     */
    public JobHandle<CurateOutcome> curate(SortScope scope) {
        return curateEngine.curate(scope);
    }

    /**
     * Delegates to CullEngine to resume a waiting cull job.
     *
     * @param prepDir {@link Path} the cull prep directory to resume
     * @param allowPartial boolean whether to proceed with missing shards
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    public JobHandle<CullJobOutcome> resume(Path prepDir, boolean allowPartial) {
        return cullEngine.resume(prepDir, allowPartial);
    }

    /**
     * Delegates to CullEngine to list waiting cull jobs.
     *
     * @return a {@link List} of {@link WaitingCullJob} the currently waiting cull jobs
     */
    public List<WaitingCullJob> waitingJobs() {
        return cullEngine.waitingJobs();
    }

    /**
     * Test seam: whether a watcher is currently polling prepDir. Lets a test prove CullEngine's own
     * disarmWatch() claim - that any dispatchAndApply() call retires an existing watcher, not just
     * the watcher's own auto-resume trigger.
     *
     * @param prepDir {@link Path} the cull prep directory to check
     * @return boolean true if a watcher is currently polling it
     */
    boolean isWatchActive(Path prepDir) {
        return cullEngine.isWatchActive(prepDir);
    }

    /**
     * Runs work through PhaseRunner, bracketing it with the given phase label.
     *
     * @param phase {@link String} the phase label for progress reporting
     * @param work a {@link PhaseRunner.PhaseWork} of T the work to run
     * @return T the result of the work
     */
    private <T> T runPhase(String phase, PhaseRunner.PhaseWork<T> work) throws Exception {
        return phaseRunner.run(phase, work);
    }

    // Thrown by curate() instead of a plain IllegalStateException when an auto-resolved OldestYear
    // scope's checkNoWaitingJobFor() conflict surfaces after its sort has already moved real files.
    // Every other checkNoWaitingJobFor() failure happens before anything runs. Only this one needs
    // to carry a partial result forward - sortSummary() is what the sort stage already produced.
    public static final class CurateConflictException extends IllegalStateException {
        private final transient SortSummary sortSummary;

        /**
         * Creates the exception carrying the partial sort result.
         *
         * @param message {@link String} the exception message
         * @param sortSummary {@link SortSummary} the sort summary produced before the conflict
         */
        CurateConflictException(String message, SortSummary sortSummary) {
            super(message);
            this.sortSummary = sortSummary;
        }

        /**
         * Returns the sort summary produced before the conflict.
         *
         * @return {@link SortSummary} the partial sort result
         */
        public SortSummary sortSummary() {
            return sortSummary;
        }
    }
}
