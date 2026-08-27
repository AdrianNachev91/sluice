package photos.sluice.application.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.ShuttingDownException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.SpendLedgerPort;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
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
 * <p>Every entry point that resolves a folder path checks the three roots first. They have to be
 * set, to exist, and not to sit inside each other. Anything else refuses with
 * {@link PathsMisconfiguredException}. That includes the calls that only read, since reading a run
 * still needs to know where the working root is. An install with nothing configured yet therefore
 * meets a typed refusal here rather than a failure deeper down.
 *
 * <p>Depends on the engines' concrete classes rather than their {@code SortUseCase}/
 * {@code CommitUseCase}/{@code RescueUseCase} port-in interfaces. The progress-callback overloads
 * live only on the concrete types, not on those narrower interfaces.
 */
@Component
public class Pipeline {

    private static final String COMMITTING = "Moving to library...";
    private static final String RESCUING = "Rescuing...";
    private static final String DISCARDING = "Discarding...";
    private static final String IMPORTING = "Importing...";

    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. See the package-private
    // constructor overload for how tests override it.
    private static final Duration DEFAULT_WATCH_POLL_INTERVAL = Duration.ofSeconds(2);

    private final SortEngine sortEngine;
    private final CommitEngine commitEngine;
    private final RescueEngine rescueEngine;
    private final ImportEngine importEngine;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final CullEngine cullEngine;
    private final CurateEngine curateEngine;
    private final DisasterDrawer disasterDrawer;
    private final Troubleshooter troubleshooter;
    private final PrepDirDoctor prepDirDoctor;
    private final PrepDirRemedies prepDirRemedies;
    private final PathsPort pathsPort;
    private final RootsGuard rootsGuard;
    private final MediaTallies mediaTallies;

    /**
     * Explicit @Autowired: Spring's implicit single-constructor injection only kicks in when a
     * class has exactly one constructor. The package-private test-seam overload below means there
     * are two, so this one has to be named as the one Spring should use.
     *
     * @param sortEngine {@link SortEngine} the sort engine
     * @param commitEngine {@link CommitEngine} the commit engine
     * @param rescueEngine {@link RescueEngine} the rescue engine
     * @param importEngine {@link ImportEngine} brings chosen folders and files into the Inbox
     * @param montageRenderer {@link MontageRenderer} renders cull contact-sheet montages
     * @param cullDispatcher {@link CullDispatcher} dispatches cull decisions to the vision agent
     * @param applyEngine {@link ApplyEngine} applies merged cull decisions
     * @param prepDirRemedies {@link PrepDirRemedies} discards a prep dir the user gave up on
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param disasterDrawer {@link DisasterDrawer} sweeps retention-expired recovery artifacts at startup
     * @param troubleshooter {@link Troubleshooter} runs the single-button prep-dir recovery
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses prep dirs and purges completed runs
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     * @param pathValidation {@link PathValidationUseCase} checks the folder roots before work reaches them
     * @param spendLedger {@link SpendLedgerPort} records what each cull run consumed
     */
    @Autowired
    public Pipeline(final SortEngine sortEngine, final CommitEngine commitEngine, final RescueEngine rescueEngine,
                    final ImportEngine importEngine, final MontageRenderer montageRenderer,
                    final CullDispatcher cullDispatcher, final ApplyEngine applyEngine,
                    final PrepDirRemedies prepDirRemedies, final CullPrepPort cullPrepPort,
                    final CullSettings cullSettings,
                    final MediaStore mediaStore, final PathsPort pathsPort,
                    final JobRunner jobRunner,
                    final ProgressPort progressPort, final DisasterDrawer disasterDrawer,
                    final Troubleshooter troubleshooter, final PrepDirDoctor prepDirDoctor,
                    final ApplyPlanner applyPlanner, final LedgerReader ledgerReader,
                    final PathValidationUseCase pathValidation, final SpendLedgerPort spendLedger) {
        this(sortEngine, commitEngine, rescueEngine, importEngine, montageRenderer, cullDispatcher, applyEngine,
                prepDirRemedies,
                cullPrepPort, cullSettings, mediaStore, pathsPort, jobRunner, progressPort,
                disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner, ledgerReader, pathValidation,
                spendLedger, DEFAULT_WATCH_POLL_INTERVAL);
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
     * @param importEngine {@link ImportEngine} brings chosen folders and files into the Inbox
     * @param montageRenderer {@link MontageRenderer} renders cull contact-sheet montages
     * @param cullDispatcher {@link CullDispatcher} dispatches cull decisions to the vision agent
     * @param applyEngine {@link ApplyEngine} applies merged cull decisions
     * @param prepDirRemedies {@link PrepDirRemedies} discards a prep dir the user gave up on
     * @param cullPrepPort {@link CullPrepPort} prepares cull montages and shards
     * @param cullSettings {@link CullSettings} user-facing cull configuration
     * @param mediaStore {@link MediaStore} moves/copies media files
     * @param pathsPort {@link PathsPort} resolves configured library/inbox paths
     * @param jobRunner {@link JobRunner} runs work as cancellable background jobs
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param disasterDrawer {@link DisasterDrawer} sweeps retention-expired recovery artifacts at startup
     * @param troubleshooter {@link Troubleshooter} runs the single-button prep-dir recovery
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses prep dirs and purges completed runs
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     * @param pathValidation {@link PathValidationUseCase} checks the folder roots before work reaches them
     * @param spendLedger {@link SpendLedgerPort} records what each cull run consumed
     * @param watchPollInterval {@link Duration} how often a watch-mode job re-checks its prep dir
     */
    Pipeline(final SortEngine sortEngine, final CommitEngine commitEngine, final RescueEngine rescueEngine,
             final ImportEngine importEngine, final MontageRenderer montageRenderer,
             final CullDispatcher cullDispatcher, final ApplyEngine applyEngine,
             final PrepDirRemedies prepDirRemedies, final CullPrepPort cullPrepPort, final CullSettings cullSettings,
             final MediaStore mediaStore, final PathsPort pathsPort,
             final JobRunner jobRunner,
             final ProgressPort progressPort, final DisasterDrawer disasterDrawer,
             final Troubleshooter troubleshooter, final PrepDirDoctor prepDirDoctor,
             final ApplyPlanner applyPlanner, final LedgerReader ledgerReader,
             final PathValidationUseCase pathValidation, final SpendLedgerPort spendLedger,
             final Duration watchPollInterval) {
        this.sortEngine = sortEngine;
        this.commitEngine = commitEngine;
        this.rescueEngine = rescueEngine;
        this.importEngine = importEngine;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.rootsGuard = new RootsGuard(pathValidation);
        this.cullEngine = new CullEngine(montageRenderer, cullDispatcher, applyEngine, cullPrepPort, cullSettings,
                mediaStore, pathsPort, jobRunner, progressPort, applyPlanner, ledgerReader,
                prepDirDoctor, prepDirRemedies, this.rootsGuard, spendLedger, watchPollInterval);
        this.curateEngine = new CurateEngine(sortEngine, jobRunner, progressPort, this.cullEngine);
        this.disasterDrawer = disasterDrawer;
        this.troubleshooter = troubleshooter;
        this.prepDirDoctor = prepDirDoctor;
        this.prepDirRemedies = prepDirRemedies;
        this.pathsPort = pathsPort;
        this.mediaTallies = new MediaTallies(mediaStore, pathsPort);
    }

    /**
     * Delegates to CullEngine, which does the real work - see its own doc. A driving adapter that
     * stays open calls this itself, once its process owns the working root. A one-shot caller that
     * only reads never calls it at all, and so never arms pollers it is about to kill.
     */
    public void armWatchesForResumableRuns() {
        this.requireUsableRoots();
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
        this.requireUsableRoots();
        this.disasterDrawer.sweepExpired(this.pathsPort.cullPrep());
        this.disasterDrawer.sweepExpiredGraveyard(this.pathsPort.graveyard());
    }

    /**
     * Runs a sort job as a cancellable background job.
     *
     * @param scope {@link SortScope} which files to sort
     * @return a {@link JobHandle} of {@link SortSummary} a handle to the running job
     */
    public JobHandle<SortSummary> sort(final SortScope scope) {
        this.requireUsableRoots();
        return this.jobRunner.submit(handle ->
                this.sortEngine.sort(scope, handle::isCancellationRequested));
    }

    /**
     * Runs a commit job as a cancellable background job.
     *
     * @param scope {@link CommitScope} which files to commit
     * @return a {@link JobHandle} of {@link CommitSummary} a handle to the running job
     */
    public JobHandle<CommitSummary> commit(final CommitScope scope) {
        this.requireUsableRoots();
        return this.jobRunner.submit(handle -> this.runPhase(COMMITTING,
                progress -> this.commitEngine.commit(scope, progress, handle::isCancellationRequested)));
    }

    /**
     * Runs an import as a cancellable background job.
     *
     * <p>The sources are checked before the job is submitted, so a refusal reaches the caller
     * before a screen changes.
     *
     * @param sources a {@link List} of {@link Path} the folders and files to bring in
     * @param kind {@link ImportKind} whether the originals stay where they are
     * @return a {@link JobHandle} of {@link ImportSummary} a handle to the running job
     * @throws ImportSourceException where a source is gone, or lies inside the Inbox or holds it
     */
    public JobHandle<ImportSummary> importFrom(final List<Path> sources, final ImportKind kind) {
        this.requireUsableRoots();
        this.importEngine.requireImportable(sources);
        return this.jobRunner.submit(handle -> this.runPhase(IMPORTING,
                progress -> this.importEngine.importFrom(sources, kind, progress,
                        handle::isCancellationRequested)));
    }

    /**
     * Runs a rescue job as a cancellable background job.
     *
     * @param reviewFolder {@link String} the Review folder to promote
     * @return a {@link JobHandle} of {@link RescueSummary} a handle to the running job
     */
    public JobHandle<RescueSummary> rescue(final String reviewFolder) {
        this.requireUsableRoots();
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
        this.requireUsableRoots();
        return this.cullEngine.cull(scope);
    }

    /**
     * Delegates to CurateEngine to run a sort followed by a cull.
     *
     * @param scope {@link SortScope} which files to curate
     * @return a {@link JobHandle} of {@link CurateOutcome} a handle to the running job
     */
    public JobHandle<CurateOutcome> curate(final SortScope scope) {
        this.requireUsableRoots();
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
        this.requireUsableRoots();
        return this.cullEngine.resume(prepDir, allowPartial);
    }

    /**
     * Every cull run currently on disk, diagnosed. This is the one source the runs screen and its
     * counted nav entry are both meant to render from.
     *
     * <p>Derived by enumerating the sift-prep root and diagnosing each dir, never from a persisted
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
     * <p>A sift-prep root nobody could read answers {@link CullRuns.Unlistable} rather than an
     * empty list, so no screen renders a failed read as a reader having no runs.
     *
     * @return {@link CullRuns} every run found, diagnosed and ordered by scope, or that the root
     *     could not be read
     */
    public CullRuns cullRuns() {
        this.requireUsableRoots();
        return this.prepDirDoctor.runs(this.pathsPort.cullPrep());
    }

    /**
     * Where a discarded run's records are filed.
     *
     * <p>Named before a discard rather than after it, so the question asked beforehand can say
     * where the records will be. {@link DiscardReport} names the run's own dated folder inside this
     * one, which does not exist until the discard has run.
     *
     * <p>Unguarded by {@link #requireUsableRoots}, because it resolves a path rather than reaching
     * the disk. A caller asking where records would go while the roots are unusable is answered
     * rather than refused.
     *
     * @return {@link Path} the folder holding every discarded run's records
     */
    public Path archivesFolder() {
        return this.pathsPort.graveyard();
    }

    /**
     * How much is waiting in the Inbox.
     *
     * <p>A walk of the tree, so its cost grows with what is in there. A caller that would block a
     * window on it runs it off whatever thread paints.
     *
     * <p>Not routed through {@link JobRunner}: it only reads, so it does not compete for the single
     * job slot. The same reasoning as {@link #cullRuns}.
     *
     * @return {@link InboxTally} what is waiting, and what it comes to on disk
     */
    public InboxTally inboxTally() {
        this.requireUsableRoots();
        return this.mediaTallies.inbox();
    }

    /**
     * What is staged in Sorted, by year.
     *
     * <p>Carries the same cost and the same reasoning as {@link #inboxTally}.
     *
     * @return {@link SortedTally} one row per year holding anything, newest first
     */
    public SortedTally sortedTally() {
        this.requireUsableRoots();
        return this.mediaTallies.sorted();
    }

    /**
     * What sifting this many photos is expected to consume.
     *
     * <p>Takes a count rather than a scope. A screen showing a figure beside a scope somebody is
     * still typing then recomputes it without walking the tree again. The count itself comes from
     * {@link #sortedTally}, whose rows carry the year and month breakdown a scope narrows to.
     *
     * <p>The tree is the expensive read this avoids, and it is not the only read. Each call reads
     * the spend ledger, which is one small file rather than a walk. A caller putting this behind
     * every keystroke is doing that much disk work per keystroke.
     *
     * <p>No roots check. The one path it reads is the spend ledger, where an unusable root degrades
     * the estimate rather than escaping: a failed read falls back to the shipped seed.
     *
     * @param photos how many photos the scope holds
     * @return {@link SpendEstimate} what a sift over them is expected to consume
     */
    public SpendEstimate estimateFor(final int photos) {
        return this.cullEngine.estimateFor(photos);
    }

    /**
     * Whether a run on the configured provider can spend anything.
     *
     * <p>A screen asks this to decide whether to raise the question of cost at all. It is not the
     * same question as {@link #estimateFor}, which needs a photo count. A sort resolves which
     * photos land under which year, so a curate has no count to ask with until it has run. Reading
     * a zero estimate back is therefore not a route open to every caller.
     *
     * <p>No roots check and no disk read. The answer is the configured provider's own type, so a
     * caller may put it behind a keystroke.
     *
     * @return boolean true when a run on the configured provider can spend
     */
    public boolean configuredProviderSpends() {
        return this.cullEngine.configuredProviderSpends();
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
        this.requireUsableRoots();
        this.cullEngine.armWatch(prepDir);
    }

    /**
     * Turns one waiting run's auto-resume off - {@link #startWatching}'s other half.
     *
     * @param prepDir {@link Path} the cull prep directory to stop watching
     */
    public void stopWatching(final Path prepDir) {
        this.requireUsableRoots();
        this.cullEngine.disarmWatch(prepDir);
    }

    /**
     * Retires every poller this process has armed. No run is started, stopped or altered by it.
     *
     * <p>Two callers need it. One has just moved the working root. Every armed watcher polls a prep
     * dir under {@code logs/sift-prep}, which hangs off that root. So for that one move, "armed
     * under the old root" and "armed at all" name the same set. A watcher left behind would poll a folder
     * outside the working root in force, for as long as the process lives. A library or inbox move
     * strands nothing and must not come here, since this would also retire a watch a user turned on
     * by hand.
     *
     * <p>The other is the app closing, where every watcher is stale for the same reason: there will
     * be no process left to poll in. That caller retires them first, so nothing is still deciding to
     * start a job while the next step is settling what is still running.
     *
     * <p>Retiring a watcher does not reach into a poll already running. A watcher whose thread is
     * mid-attempt when this arrives still finishes that attempt, resume included. What this
     * guarantees is that no further poll starts.
     *
     * <p>One of the two facade methods with no roots check, alongside {@link #stopAcceptingJobs}. It
     * resolves no path, and the caller that needs it most is one whose roots have just changed
     * underneath it.
     */
    public void stopAllWatching() {
        this.cullEngine.disarmAllWatches();
    }

    /**
     * Shuts the job runner for good and drains the job that may be running. Answers whether anything
     * of the app's is still executing by the time it returns.
     *
     * <p>What an exit path calls once its window has gone, after {@link #stopAllWatching}. A false
     * answer means a job ran past the wait and is still touching files. The caller then keeps hold of
     * whatever it was about to give back. That is what stops the working root reaching the next
     * Sluice while this one is still moving things inside it.
     *
     * <p>One-way. Nothing reopens the runner, so anything reaching a job entry point afterwards is
     * refused with {@link ShuttingDownException}.
     *
     * <p>The second entry point with no roots check, and for the same kind of reason as
     * {@link #stopAllWatching}. It resolves no path, and an install whose roots are unusable has to
     * be able to close as cleanly as one whose roots are fine.
     *
     * @param timeout {@link Duration} how long to wait for a running job to stop
     * @return boolean true when no job is still executing
     */
    public boolean stopAcceptingJobs(final Duration timeout) {
        return this.jobRunner.shutdown(timeout);
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
        this.requireUsableRoots();
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
        this.requireUsableRoots();
        return this.jobRunner.submit(_ -> this.prepDirDoctor.purgeCompleted(this.pathsPort.cullPrep()));
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
        this.requireUsableRoots();
        return this.jobRunner.submit(_ -> {
            if (this.prepDirDoctor.diagnose(prepDir).state() == PrepDirHealth.State.COMPLETE) {
                throw new RunAlreadyFinishedException(prepDir);
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
     * Whether a job's work is currently executing.
     *
     * <p>A screen holding a start control asks this as it draws. One drawn again while a job it
     * started earlier is still running would otherwise offer a second. The refusal for that arrives
     * only once the button has been pressed.
     *
     * <p>Also a test seam. A test that starts a background job it holds no handle to waits on this
     * going false. That is the only point at which the job's file work is known to be over. Waiting
     * on any effect the job produces instead leaves whatever the job does afterwards racing the
     * test's own teardown.
     *
     * <p>False on its own says nothing, since it is also false before the job ever starts. A caller
     * asking whether some particular work has finished pairs it with a signal that the work
     * happened at all.
     *
     * @return boolean true if a job's work is currently executing
     */
    public boolean isBusy() {
        return this.jobRunner.isBusy();
    }

    /**
     * Refuses the call when the folder roots it would reach are not usable. Every entry point that
     * resolves a path runs this first, including the ones that only read.
     *
     * <p>The guard sits here rather than in a screen, because both a screen and a command line pass
     * through this class. A check written into either one would be walked past by the other. The
     * same reasoning puts the working-root claim one layer up.
     *
     * @throws PathsMisconfiguredException if any of the three roots is unset, missing, or overlapping
     */
    private void requireUsableRoots() {
        this.rootsGuard.requireUsable();
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
         * <p>Public, like the refusals in {@code port.in}. A facade whose refusals only it can
         * construct cannot be stood in for. Every screen that words one is tested against a
         * stand-in rather than a real pipeline.
         *
         * @param occupant {@link CullRunSummary} the run already occupying the scope
         */
        public ScopeOccupiedException(final CullRunSummary occupant) {
            super("A sift for '" + occupant.scope() + "' already occupies " + occupant.prepDir() + ", and is "
                    + occupant.health().state() + " - resume, troubleshoot or discard it before starting another"
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
     * Thrown when a fresh sift is refused because its timeline shares photos with unfinished sifts
     * being any of them. It carries every one of them, diagnosed, so a caller can name them all.
     *
     * <p>Apart from {@link ScopeOccupiedException}, which is the exact-tag case. A prep dir is
     * claimed by the tag its scope comes to, so the whole of 2019 and June 2019 are unrelated keys.
     * Nothing about the claim stops the two running together, and each would sheet and pay for June
     * a second time.
     *
     * <p>Carries a list rather than the first one found. A reader told about one deals with it,
     * comes back, and is refused by the next.
     *
     * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the call was
     * refused needs no knowledge of this type at all.
     */
    public static final class ScopeOverlapsException extends IllegalStateException {
        private final transient CullScope.Year chosen;
        private final transient List<CullRunSummary> across;

        /**
         * Creates the exception over the timeline chosen and the unfinished runs it overlaps.
         *
         * @param chosen {@link CullScope.Year} the timeline the caller asked to sift
         * @param across a {@link List} of {@link CullRunSummary} the unfinished runs it overlaps
         */
        public ScopeOverlapsException(final CullScope.Year chosen, final List<CullRunSummary> across) {
            super(CullScope.tag(chosen) + " overlaps " + across.stream().map(CullRunSummary::scope).toList()
                    + ", which have not finished - finish or discard them before sifting it.");
            this.chosen = chosen;
            this.across = List.copyOf(across);
        }

        /**
         * Returns the timeline the caller asked to sift.
         *
         * @return {@link CullScope.Year} the chosen scope
         */
        public CullScope.Year chosen() {
            return this.chosen;
        }

        /**
         * Returns the unfinished runs the chosen timeline overlaps, diagnosed.
         *
         * @return a {@link List} of {@link CullRunSummary} the runs in the way
         */
        public List<CullRunSummary> across() {
            return this.across;
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
        public CurateConflictException(final CullRunSummary occupant, final SortSummary sortSummary) {
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
     * Thrown when a discard is refused because the run has already finished.
     *
     * <p>Reachable from a screen that offered the discard, because a run can finish between the
     * screen being drawn and the button being pressed. A watcher applying an agent's last shard
     * does exactly that, unattended. So this is a refusal a reader meets rather than a state only a
     * caller writing the wrong code could reach.
     *
     * <p>Carries the prep dir, and its message says what to do instead. Purging is the finished
     * run's own verb, and nothing was lost by asking for the other one.
     *
     * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the call was
     * refused needs no knowledge of this type at all.
     */
    public static final class RunAlreadyFinishedException extends IllegalStateException {

        /**
         * Creates the exception, naming the run that had already finished.
         *
         * @param prepDir {@link Path} the run that was asked to be thrown away
         */
        public RunAlreadyFinishedException(final Path prepDir) {
            super("That sift finished before it could be discarded, so nothing was lost. "
                    + "Clear it with the finished runs instead. Its records are at " + prepDir + ".");
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
        public ScopeUnreadableException(final Path prepDir, final Throwable cause) {
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

    /**
     * Thrown when a resume is refused because its prep dir does not sit under the working root now
     * in force. The run belongs to a folder this app has moved off, so resuming it would work a
     * tree the current settings do not name.
     *
     * <p>Raised from inside the job rather than at the call, because the folder roots can move while
     * a caller is queueing for the job slot. A check before that wait answers about roots that may
     * be replaced before the job starts.
     *
     * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know the call was
     * refused needs no knowledge of this type at all.
     */
    public static final class RunOutsideWorkingRootException extends IllegalStateException {
        private final transient Path prepDir;

        /**
         * Creates the exception, naming the prep dir that sits outside the roots in force.
         *
         * @param prepDir {@link Path} the prep dir that could not be resumed
         */
        public RunOutsideWorkingRootException(final Path prepDir) {
            super("The sift at " + prepDir + " is not inside the working root Sluice is set up with now, "
                    + "so it was not resumed - point the working root back at the folder holding it, "
                    + "or discard the sift.");
            this.prepDir = prepDir;
        }

        /**
         * Returns the prep dir that sits outside the roots in force.
         *
         * @return {@link Path} the prep dir that could not be resumed
         */
        public Path prepDir() {
            return this.prepDir;
        }
    }
}
