package photos.sluice.application.service;

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
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The public facade wiring the mechanical engines through {@link JobRunner}. A driving adapter
 * (the JavaFX UI, a future CLI) gets a {@link JobHandle} back instead of blocking. Progress is
 * bracketed through {@link ProgressPort} around each engine call, via {@link PhaseRunner}.
 *
 * <p>Cull and curate orchestration live in {@link CullEngine} and {@link CurateEngine}. Pipeline
 * builds and wires both, then exposes their methods under one type so a driving adapter depends
 * on a single class.
 *
 * <p>Depends on the engines' concrete classes rather than their {@code SortUseCase}/
 * {@code CommitUseCase}/{@code RescueUseCase} port-in interfaces. The progress-callback overloads
 * live only on the concrete types, not on those narrower interfaces.
 */
@Component
public class Pipeline {

    private static final String SORTING = "Sorting...";
    private static final String COMMITTING = "Committing...";
    private static final String RESCUING = "Rescuing...";
    private static final String DISCARDING = "Discarding...";

    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode, this cadence isn't a documented user-facing knob, just an internal
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
    private final DisasterDrawer disasterDrawer;
    private final Troubleshooter troubleshooter;
    private final PrepDirDoctor prepDirDoctor;
    private final PrepDirRemedies prepDirRemedies;
    private final Path cullPrepRoot;
    private final Path graveyardRoot;

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
     * @param prepDirRemedies {@link PrepDirRemedies} discards a prep dir the user gave up on
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param montageConfig {@link MontageConfig} montage grid sizing configuration
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param disasterDrawer {@link DisasterDrawer} sweeps retention-expired recovery artifacts at startup
     * @param troubleshooter {@link Troubleshooter} runs the single-button prep-dir recovery
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses prep dirs and purges completed runs
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     */
    @Autowired
    public Pipeline(final SortEngine sortEngine, final CommitEngine commitEngine, final RescueEngine rescueEngine,
                    final MontageRenderer montageRenderer, final CullDispatcher cullDispatcher,
                    final ApplyEngine applyEngine,
                    final PrepDirRemedies prepDirRemedies, final CullPrepPort cullPrepPort,
                    final CullSettings cullSettings,
                    final MediaStore mediaStore, final PathsPort pathsPort, final MontageConfig montageConfig,
                    final JobRunner jobRunner,
                    final ProgressPort progressPort, final DisasterDrawer disasterDrawer,
                    final Troubleshooter troubleshooter, final PrepDirDoctor prepDirDoctor,
                    final ApplyPlanner applyPlanner, final LedgerReader ledgerReader) {
        this(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine, prepDirRemedies,
                cullPrepPort, cullSettings, mediaStore, pathsPort, montageConfig, jobRunner, progressPort,
                disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner, ledgerReader,
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
     * @param prepDirRemedies {@link PrepDirRemedies} discards a prep dir the user gave up on
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param montageConfig {@link MontageConfig} montage grid sizing configuration
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param disasterDrawer {@link DisasterDrawer} sweeps retention-expired recovery artifacts at startup
     * @param troubleshooter {@link Troubleshooter} runs the single-button prep-dir recovery
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses prep dirs and purges completed runs
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     * @param watchPollInterval {@link Duration} how often a watch-mode job re-checks its prep dir
     */
    Pipeline(final SortEngine sortEngine, final CommitEngine commitEngine, final RescueEngine rescueEngine,
             final MontageRenderer montageRenderer, final CullDispatcher cullDispatcher, final ApplyEngine applyEngine,
             final PrepDirRemedies prepDirRemedies, final CullPrepPort cullPrepPort, final CullSettings cullSettings,
             final MediaStore mediaStore, final PathsPort pathsPort, final MontageConfig montageConfig,
             final JobRunner jobRunner,
             final ProgressPort progressPort, final DisasterDrawer disasterDrawer,
             final Troubleshooter troubleshooter, final PrepDirDoctor prepDirDoctor,
             final ApplyPlanner applyPlanner, final LedgerReader ledgerReader, final Duration watchPollInterval) {
        this.sortEngine = sortEngine;
        this.commitEngine = commitEngine;
        this.rescueEngine = rescueEngine;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.cullEngine = new CullEngine(montageRenderer, cullDispatcher, applyEngine, cullPrepPort, cullSettings,
                mediaStore, pathsPort, montageConfig, jobRunner, progressPort, applyPlanner, ledgerReader,
                prepDirDoctor, prepDirRemedies, watchPollInterval);
        this.curateEngine = new CurateEngine(sortEngine, jobRunner, progressPort, this.cullEngine);
        this.disasterDrawer = disasterDrawer;
        this.troubleshooter = troubleshooter;
        this.prepDirDoctor = prepDirDoctor;
        this.prepDirRemedies = prepDirRemedies;
        this.cullPrepRoot = pathsPort.logs().resolve("cull-prep");
        this.graveyardRoot = pathsPort.logs().resolve("disasters");
    }

    /**
     * Delegates to CullEngine, which does the real work - see its own doc. A driving adapter that
     * stays open calls this itself, once its process owns the working root. A one-shot caller that
     * only reads never calls it at all, and so never arms pollers it is about to kill.
     */
    public void armWatchesForResumableRuns() {
        this.cullEngine.armWatchesForResumableRuns();
    }

    /**
     * Delegates to DisasterDrawer to sweep every prep dir's disaster drawer for retention-expired
     * entries, meaning a corrupt original or a troubleshoot report older than 30 days. It sweeps
     * every PrepDirRemedies.discard() graveyard folder past that same window too. The caller runs
     * this itself, once its process owns the working root. The sweep deletes, so it must never run
     * before that.
     */
    public void sweepExpiredDisasterDrawers() {
        this.disasterDrawer.sweepExpired(this.cullPrepRoot);
        this.disasterDrawer.sweepExpiredGraveyard(this.graveyardRoot);
    }

    /**
     * Runs a sort job as a cancellable background job.
     *
     * @param scope {@link SortScope} which files to sort
     * @return a {@link JobHandle} of {@link SortSummary} a handle to the running job
     */
    public JobHandle<SortSummary> sort(final SortScope scope) {
        return this.jobRunner.submit(handle -> this.runPhase(SORTING,
                progress -> this.sortEngine.sort(scope, progress, handle::isCancellationRequested)));
    }

    /**
     * Runs a commit job as a cancellable background job.
     *
     * @param scope {@link CommitScope} which files to commit
     * @return a {@link JobHandle} of {@link CommitSummary} a handle to the running job
     */
    public JobHandle<CommitSummary> commit(final CommitScope scope) {
        return this.jobRunner.submit(handle -> this.runPhase(COMMITTING,
                progress -> this.commitEngine.commit(scope, progress, handle::isCancellationRequested)));
    }

    /**
     * Runs a rescue job as a cancellable background job.
     *
     * @param reviewFolder {@link String} the Review folder to promote
     * @return a {@link JobHandle} of {@link RescueSummary} a handle to the running job
     */
    public JobHandle<RescueSummary> rescue(final String reviewFolder) {
        return this.jobRunner.submit(handle -> this.runPhase(RESCUING,
                progress -> this.rescueEngine.rescue(reviewFolder, progress, handle::isCancellationRequested)));
    }

    /**
     * Delegates to CullEngine to run a cull job.
     *
     * @param scope {@link CullScope} which files to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    public JobHandle<CullJobOutcome> cull(final CullScope scope) {
        return this.cullEngine.cull(scope);
    }

    /**
     * Delegates to CurateEngine to run a sort followed by a cull.
     *
     * @param scope {@link SortScope} which files to curate
     * @return a {@link JobHandle} of {@link CurateOutcome} a handle to the running job
     */
    public JobHandle<CurateOutcome> curate(final SortScope scope) {
        return this.curateEngine.curate(scope);
    }

    /**
     * Delegates to CullEngine to resume a waiting cull job.
     *
     * @param prepDir {@link Path} the cull prep directory to resume
     * @param allowPartial boolean whether to proceed with missing shards
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    public JobHandle<CullJobOutcome> resume(final Path prepDir, final boolean allowPartial) {
        return this.cullEngine.resume(prepDir, allowPartial);
    }

    /**
     * Every cull run currently on disk, diagnosed. This is the one source the run-card dashboard and
     * the unresolved-run banner are both meant to render from.
     *
     * <p>Derived by enumerating the cull-prep root and diagnosing each dir, never from a persisted
     * list. Enumerating is what keeps a damaged run visible, which is exactly when it most needs to
     * be. Diagnosis is side-effect-free and never throws, whatever state a dir is in, so one dir
     * nobody can read reports DAMAGED and the rest still render.
     *
     * <p>That makes polling on a timer safe, not cheap. Each pass reads every sidecar, every shard
     * and the move ledger of every run, twice over. A caller refreshing on a timer picks its
     * interval accordingly.
     *
     * <p>Not routed through JobRunner: it only reads, so it does not compete for the single job
     * slot.
     *
     * @return a {@link List} of {@link CullRunSummary} every run found, diagnosed, ordered by scope
     */
    public List<CullRunSummary> cullRuns() {
        return this.prepDirDoctor.runs(this.cullPrepRoot);
    }

    /**
     * Turns one waiting run's auto-resume on, whatever the configured mode is. The two halves of
     * the waiting card's "auto-apply when shards arrive" toggle are this and
     * {@link #stopWatching}. Neither touches the run itself: it stays Waiting, stays listed, and
     * still blocks a re-cull of its scope either way. A run belonging to an automated provider is
     * left alone, since its shards never arrive from outside the app and the toggle is absent from
     * its card.
     *
     * @param prepDir {@link Path} the cull prep directory to watch
     */
    public void startWatching(final Path prepDir) {
        this.cullEngine.armWatch(prepDir);
    }

    /**
     * Turns one waiting run's auto-resume off - {@link #startWatching}'s other half.
     *
     * @param prepDir {@link Path} the cull prep directory to stop watching
     */
    public void stopWatching(final Path prepDir) {
        this.cullEngine.disarmWatch(prepDir);
    }

    /**
     * Runs a troubleshoot pass over prepDir as a background job. Routing it through JobRunner buys
     * the same one-job-at-a-time discipline every other job gets. A reconcile's move-log rewrite can
     * then never race a concurrent apply/cull/commit against the same prep dir.
     *
     * @param prepDir {@link Path} the cull prep directory to troubleshoot
     * @return a {@link JobHandle} of {@link TroubleshootReport} a handle to the running job
     */
    public JobHandle<TroubleshootReport> troubleshoot(final Path prepDir) {
        return this.jobRunner.submit(_ -> this.troubleshooter.troubleshoot(prepDir));
    }

    /**
     * Runs a manual, one-button purge of every completed cull run as a background job. Routing it
     * through JobRunner buys the same one-job-at-a-time discipline every other job gets. A purge can
     * then never race a re-prep of a scope it is in the middle of deleting.
     *
     * @return a {@link JobHandle} of {@link PurgeReport} a handle to the running job
     */
    public JobHandle<PurgeReport> purgeCompleted() {
        return this.jobRunner.submit(_ -> this.prepDirDoctor.purgeCompleted(this.cullPrepRoot));
    }

    /**
     * Gives up on prepDir as a background job. It refuses a COMPLETE run, since purgeCompleted() is
     * that state's own verb. It then retires any watcher polling it and delegates to
     * PrepDirRemedies.discard() for the actual file work. That files everything worth keeping into
     * the graveyard and deletes the montage and tile images. The watcher must be disarmed before the
     * graveyard move starts, or an auto-resume could fire against a prep dir already being
     * dismantled. Routing through JobRunner buys the same one-job-at-a-time discipline every other
     * job gets. A discard can then never race a re-prep of the scope it is giving up on. The
     * last-resort "discard this run and redo" remedy calls this same method.
     *
     * @param prepDir {@link Path} the cull prep directory to discard
     * @return a {@link JobHandle} of {@link DiscardReport} a handle to the running job
     */
    public JobHandle<DiscardReport> discard(final Path prepDir) {
        return this.jobRunner.submit(_ -> {
            if (this.prepDirDoctor.diagnose(prepDir).state() == PrepDirHealth.State.COMPLETE) {
                throw new IllegalStateException("Prep dir " + prepDir
                        + " has already completed - discard refuses a finished run; purge it instead.");
            }
            this.cullEngine.disarmWatch(prepDir);
            return this.runPhase(DISCARDING, progress -> this.prepDirRemedies.discard(prepDir, progress));
        });
    }

    /**
     * Test seam: whether a watcher is currently polling prepDir. Lets a test prove CullEngine's own
     * disarmWatch() claim - that any dispatchAndApply() call retires an existing watcher, not just
     * the watcher's own auto-resume trigger.
     *
     * @param prepDir {@link Path} the cull prep directory to check
     * @return boolean true if a watcher is currently polling it
     */
    boolean isWatchActive(final Path prepDir) {
        return this.cullEngine.isWatchActive(prepDir);
    }

    /**
     * Runs work through PhaseRunner, bracketing it with the given phase label.
     *
     * @param phase {@link String} the phase label for progress reporting
     * @param work a {@link PhaseRunner.PhaseWork} of T the work to run
     * @return T the result of the work
     */
    private <T> T runPhase(final String phase, final PhaseRunner.PhaseWork<T> work) throws Exception {
        return this.phaseRunner.run(phase, work);
    }

    /**
     * Thrown when a fresh cull or curate is refused because an unfinished run already occupies the
     * scope's own prep dir. It carries that run, diagnosed, so a caller routes the user to the
     * right way out without parsing the message. Resume for WAITING or READY, Troubleshoot for
     * BLOCKED or DAMAGED, Discard from any of them.
     *
     * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the call was
     * refused needs no knowledge of this type at all.
     */
    public static sealed class ScopeOccupiedException extends IllegalStateException
            permits CurateConflictException {
        private final transient CullRunSummary occupant;

        /**
         * Creates the exception, rendering the refusal message from the occupying run.
         *
         * @param occupant {@link CullRunSummary} the run already occupying the scope
         */
        ScopeOccupiedException(final CullRunSummary occupant) {
            super("A cull of scope '" + occupant.scope() + "' already occupies " + occupant.prepDir() + ", and is "
                    + occupant.health().state() + " - resume, troubleshoot or discard it before starting a new cull"
                    + " for the same scope.");
            this.occupant = occupant;
        }

        /**
         * Returns the run occupying the scope, diagnosed.
         *
         * @return {@link CullRunSummary} the occupying run
         */
        public CullRunSummary occupant() {
            return this.occupant;
        }
    }

    /**
     * Thrown by {@code curate()} in place of a plain {@link ScopeOccupiedException} whenever the
     * refusal lands after its sort has already moved real files. Two calls reach that point. An
     * auto-resolved {@code OldestYear} scope cannot be checked until its year is known, which is
     * only after the sort. And the claim inside {@code buildFreshAndDispatch} re-asks for every
     * scope shape, so a scope free when {@code curate()} was called but taken during a long sort
     * refuses there too.
     *
     * <p>Both need to carry a partial result forward, which a plain refusal cannot:
     * {@link #sortSummary()} is what the sort stage already produced. A refusal raised before the
     * sort runs stays a plain {@link ScopeOccupiedException}, since nothing has happened to report.
     *
     * <p>A subtype rather than a sibling, so it keeps {@link #occupant()} as well. The refusal is
     * the same refusal either way, and a caller handling one handles both.
     */
    public static final class CurateConflictException extends ScopeOccupiedException {
        private final transient SortSummary sortSummary;

        /**
         * Creates the exception carrying both the occupying run and the partial sort result.
         *
         * @param occupant {@link CullRunSummary} the run already occupying the scope
         * @param sortSummary {@link SortSummary} the sort summary produced before the conflict
         */
        CurateConflictException(final CullRunSummary occupant, final SortSummary sortSummary) {
            super(occupant);
            this.sortSummary = sortSummary;
        }

        /**
         * Returns the sort summary produced before the conflict.
         *
         * @return {@link SortSummary} the partial sort result
         */
        public SortSummary sortSummary() {
            return this.sortSummary;
        }
    }

    /**
     * Thrown when a fresh cull or curate is refused because scope's own prep dir could not be read
     * at all, rather than because a diagnosed run occupies it. Refusing is the same safe direction a
     * {@link ScopeOccupiedException} takes: proceeding would let a fresh prep clear a directory
     * nobody could confirm was actually empty. But nothing here was diagnosed, so no run is
     * fabricated to carry one. This carries the prep dir path and the read failure instead. A
     * caller names what could not be read and offers a retry, rather than routing to a specific
     * state's remedy that was never actually reached.
     *
     * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the call was
     * refused needs no knowledge of this type at all.
     */
    public static final class ScopeUnreadableException extends IllegalStateException {
        private final transient Path prepDir;

        /**
         * Creates the exception, naming the prep dir that could not be read.
         *
         * @param prepDir {@link Path} the prep dir whose occupancy could not be determined
         * @param cause {@link Throwable} the read failure
         */
        ScopeUnreadableException(final Path prepDir, final Throwable cause) {
            super("Scope's prep dir " + prepDir + " could not be read, so whether it is occupied is unknown - "
                    + "retry once whatever is holding it clears.", cause);
            this.prepDir = prepDir;
        }

        /**
         * Returns the prep dir whose occupancy could not be determined.
         *
         * @return {@link Path} the unreadable prep dir
         */
        public Path prepDir() {
            return this.prepDir;
        }
    }
}
