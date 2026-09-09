package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.in.ReviewListing;
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
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.ChoiceAnswer;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.LaunchPrompt;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;
import photos.sluice.secrets.SecretStore;

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
 * <p>Every entry point that resolves a folder path checks the three roots first, the calls that
 * only read included, and refuses with {@link PathsMisconfiguredException} otherwise.
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

    // How often a waiting job re-checks its prep dir's shard tally. Not a CullSettings knob: it is
    // an internal responsiveness/overhead tradeoff. Short enough that a human dropping files never
    // perceives the delay, long enough not to hammer disk or spam re-validation.
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
    private final SpendLedgerPort spendLedger;
    private final RootsGuard rootsGuard;
    private final MediaTallies mediaTallies;
    private final ReviewFolders reviewFolders;
    private final RunChanges runChanges = new RunChanges();
    private final AutoResumedSifts autoResumedSifts = new AutoResumedSifts();

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
     * @param secretStore {@link SecretStore} says whether the configured provider's credential is held
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
                    final PathValidationUseCase pathValidation, final SpendLedgerPort spendLedger,
                    final SecretStore secretStore) {
        this(sortEngine, commitEngine, rescueEngine, importEngine, montageRenderer, cullDispatcher, applyEngine,
                prepDirRemedies,
                cullPrepPort, cullSettings, mediaStore, pathsPort, jobRunner, progressPort,
                disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner, ledgerReader, pathValidation,
                spendLedger, secretStore, DEFAULT_WATCH_POLL_INTERVAL);
    }

    /**
     * Test seam taking the poll cadence the public constructor above fixes. A much shorter interval
     * proves poll timing out in milliseconds rather than seconds, with no mock clock.
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
     * @param secretStore {@link SecretStore} says whether the configured provider's credential is held
     * @param watchPollInterval {@link Duration} how often a waiting job re-checks its prep dir
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
             final SecretStore secretStore,
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
                prepDirDoctor, prepDirRemedies, this.rootsGuard, spendLedger, secretStore, watchPollInterval,
                this.runChanges, this.autoResumedSifts);
        this.curateEngine = new CurateEngine(sortEngine, jobRunner, this.cullEngine, this.phaseRunner);
        this.disasterDrawer = disasterDrawer;
        this.troubleshooter = troubleshooter;
        this.prepDirDoctor = prepDirDoctor;
        this.prepDirRemedies = prepDirRemedies;
        this.pathsPort = pathsPort;
        this.spendLedger = spendLedger;
        this.mediaTallies = new MediaTallies(mediaStore, pathsPort);
        this.reviewFolders = new ReviewFolders(mediaStore, pathsPort);
    }

    /**
     * Arms a watch on every run that could still be resumed. Called once a process owns the working
     * root.
     */
    public void armWatchesForResumableRuns() {
        this.requireUsableRoots();
        this.cullEngine.armWatchesForResumableRuns();
    }

    /**
     * Sweeps every prep dir's disaster drawer, and every discarded-run graveyard folder, for
     * entries past their retention window. Called once the process owns the working root. The
     * sweep deletes, so it must never run before that.
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
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(SortEngine.PHASES);
            return this.sortEngine.sort(scope, handle.stopSignal());
        });
    }

    /**
     * Runs a commit job as a cancellable background job.
     *
     * @param scope {@link CommitScope} which files to commit
     * @return a {@link JobHandle} of {@link CommitSummary} a handle to the running job
     */
    public JobHandle<CommitSummary> commit(final CommitScope scope) {
        this.requireUsableRoots();
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(List.of(COMMITTING));
            return this.phaseRunner.run(COMMITTING,
                    progress -> this.commitEngine.commit(scope, progress, handle.stopSignal()));
        });
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
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(List.of(IMPORTING));
            return this.phaseRunner.run(IMPORTING,
                    progress -> this.importEngine.importFrom(sources, kind, progress,
                            handle.stopSignal()));
        });
    }

    /**
     * Runs a rescue job as a cancellable background job.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder to move back into Sorted
     * @return a {@link JobHandle} of {@link RescueSummary} a handle to the running job
     */
    public JobHandle<RescueSummary> rescue(final RescueRoot root, final String folder) {
        this.requireUsableRoots();
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(List.of(RESCUING));
            return this.phaseRunner.run(RESCUING,
                    progress -> this.rescueEngine.rescue(root, folder, progress, handle.stopSignal()));
        });
    }

    /**
     * Runs a cull job over one scope.
     *
     * @param scope {@link CullScope} which files to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running job
     */
    public JobHandle<CullJobOutcome> cull(final CullScope scope) {
        this.requireUsableRoots();
        return this.cullEngine.cull(scope);
    }

    /**
     * Runs a sort followed by a cull, as one job.
     *
     * <p>No surface calls this. It is kept whole and tested against the day one does.
     * {@code PipelineSurfaceTest} pins it, so a later deletion is a decision rather than a tidy-up.
     *
     * @param scope {@link SortScope} which files to curate
     * @return a {@link JobHandle} of {@link CurateOutcome} a handle to the running job
     */
    public JobHandle<CurateOutcome> curate(final SortScope scope) {
        this.requireUsableRoots();
        return this.curateEngine.curate(scope);
    }

    /**
     * Resumes a cull job that was waiting on shards.
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
     * Every cull run currently on disk, diagnosed.
     *
     * <p>Derived by enumerating the sift-prep root and diagnosing each dir, never from a persisted
     * list. Enumerating is what keeps a damaged run visible, which is exactly when it most needs to
     * be. Diagnosis is side-effect-free and never throws, whatever state a dir is in, so one dir
     * nobody can read reports DAMAGED and the rest still render.
     *
     * <p>Safe to poll on a timer, not cheap: each pass reads every sidecar, every shard and the move
     * ledger of every run, twice over.
     *
     * <p>Not routed through {@link JobRunner}: it only reads, so it does not compete for the single
     * job slot.
     *
     * <p>A sift-prep root nobody could read answers {@link CullRuns.Unlistable} rather than an empty
     * list, which would read as a reader having no runs.
     *
     * @return {@link CullRuns} every run found, diagnosed and ordered by scope, or that the root
     *     could not be read
     */
    public CullRuns cullRuns() {
        this.requireUsableRoots();
        return this.prepDirDoctor.runs(this.pathsPort.cullPrep());
    }

    /**
     * One run on disk, diagnosed.
     *
     * <p>Reads one prep dir rather than the whole sift-prep root.
     *
     * <p>Never throws whatever state the dir is in, the contract {@link #cullRuns} already carries
     * per run. A dir that could not be read answers DAMAGED with a null tally.
     *
     * <p>Not routed through {@link JobRunner}, for the same reason as {@link #cullRuns}. That is
     * what lets it answer while a sift is running.
     *
     * @param prepDir {@link Path} the run to diagnose
     * @return {@link CullRunSummary} that run's scope, diagnosis, tally and age
     */
    public CullRunSummary cullRun(final Path prepDir) {
        this.requireUsableRoots();
        return this.prepDirDoctor.summaryOf(prepDir);
    }

    /**
     * Where a discarded run's records are filed.
     *
     * <p>{@link DiscardReport} names the run's own dated folder inside this one, which does not
     * exist until the discard has run.
     *
     * <p>Unguarded by {@link #requireUsableRoots}: it resolves a path rather than reaching the disk,
     * so it answers rather than refuses while the roots are unusable.
     *
     * @return {@link Path} the folder holding every discarded run's records
     */
    public Path archivesFolder() {
        return this.pathsPort.graveyard();
    }

    /**
     * Files away a record of past spend that nothing can read, so the next one starts clean.
     *
     * <p>The only repair for a ledger with a line no parser accepts. Until it is moved every
     * estimate falls back to the shipped seed, and nothing the user can press changes that.
     *
     * <p>Moved into the archives folder rather than deleted. It is still the only record of what
     * past runs cost, and a line one version cannot parse may be readable by the next.
     *
     * <p>The history it holds does not come back. The estimate rebuilds its projected half from the
     * first completed run after this.
     *
     * <p>A ledger that reads is left where it is, and the answer says so the same way an absent one
     * does.
     *
     * @return {@link Path} where the ledger was filed, or null where there was nothing to repair
     */
    public @Nullable Path setAsideUnreadableSpendLedger() {
        this.requireUsableRoots();
        if (this.spendLedgerIsReadable()) {
            return null;
        }
        final Path destination = this.pathsPort.graveyard()
                .resolve("spend-ledger-" + DisasterTimestamp.now() + ".csv");
        return this.spendLedger.setAside(destination) ? destination : null;
    }

    /**
     * How much is waiting in the Inbox.
     *
     * <p>A walk of the tree, so its cost grows with what is in there. A caller that would block a
     * window on it runs it off whatever thread paints.
     *
     * <p>Not routed through {@link JobRunner}, for the same reason as {@link #cullRuns}.
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
     * Every folder holding photos somebody still has to look at.
     *
     * <p>Three tree walks, one per root. Carries the same cost and the same reasoning as
     * {@link #inboxTally}.
     *
     * @return {@link ReviewListing} the folders, and any of the three roots that could not be read
     */
    public ReviewListing reviewListing() {
        this.requireUsableRoots();
        return this.reviewFolders.list();
    }

    /**
     * What Sluice wrote beside the photos in one of those folders.
     *
     * <p>Taken folder by folder rather than with the listing above. A note names every photo in its
     * folder. Reading all of them to draw the list would read the whole of what a reader has so far
     * asked to see none of.
     *
     * @param folder {@link Path} a folder {@link #reviewListing} named
     * @return a {@link List} of {@link String} the lines, empty where nothing was written
     * @throws IllegalArgumentException if folder is not under one of the three roots
     */
    public List<String> reviewNotes(final Path folder) {
        this.requireUsableRoots();
        return this.reviewFolders.notesIn(folder);
    }

    /**
     * What sifting this many photos is expected to consume.
     *
     * <p>Takes a count rather than a scope, so a caller recomputes without walking the tree again.
     * The count comes from {@link #sortedTally}, whose rows carry the year and month breakdown a
     * scope narrows to.
     *
     * <p>It still reads the spend ledger on every call, which is one small file rather than a walk.
     * A caller putting this behind every keystroke is doing that much disk work per keystroke.
     *
     * <p>No roots check. The one path it reads is the spend ledger, and an unusable root degrades
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
     * <p>Not the same question as {@link #estimateFor}, which needs a photo count. A sort resolves
     * which photos land under which year, so a curate has no count to ask with until it has run.
     * Reading a zero estimate back is therefore not a route open to every caller.
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
     * Retires every poller this process has armed. No run is started, stopped or altered by it.
     *
     * <p>Every armed watcher polls a prep dir under {@code logs/sift-prep}, which hangs off the
     * working root, so "armed under the old root" and "armed at all" name the same set. One left
     * behind would poll a folder outside the root in force, for as long as the process lives.
     *
     * <p>A library or inbox move strands nothing and must not come here.
     *
     * <p>Retiring a watcher does not reach into a poll already running. A watcher whose thread is
     * mid-attempt when this arrives still finishes that attempt, resume included. What this
     * guarantees is that no further poll starts.
     *
     * <p>No roots check: it resolves no path, and the caller that needs it most is one whose roots
     * have just changed underneath it.
     */
    public void stopAllWatching() {
        this.cullEngine.disarmAllWatches();
    }

    /**
     * Shuts the job runner for good and drains the job that may be running. Answers whether anything
     * of the app's is still reaching files by the time it returns.
     *
     * <p>Called after {@link #stopAllWatching}. A false answer means a job ran past the wait and is
     * still touching files. The caller then keeps hold of the working root rather than letting it
     * reach the next Sluice while this one is still moving things inside it.
     *
     * <p>One-way. Nothing reopens the runner, so anything reaching a job entry point afterwards is
     * refused with {@link ShuttingDownException}.
     *
     * <p>No roots check, for the same reason as {@link #stopAllWatching}. An install whose roots are
     * unusable has to be able to close as cleanly as one whose roots are fine.
     *
     * @param timeout {@link Duration} how long to wait for a running job to stop
     * @return boolean true when no job is still reaching files
     */
    public boolean stopAcceptingJobs(final Duration timeout) {
        return this.jobRunner.shutdown(timeout);
    }

    /**
     * Asks the job in flight to give up on the file it is writing, rather than finish it.
     *
     * <p>An escalation of a stop already asked for. A stop between files is answered in an instant,
     * and one that lands inside a large file waits out the rest of its bytes. This ends that wait,
     * at the cost of the file having to be transferred again later.
     *
     * <p>Nothing half-written survives it: a transfer given up on leaves the source where it was and
     * the destination free.
     *
     * <p>No roots check. A reader stopping a run may not be blocked by an install whose folders have
     * since gone.
     */
    public void abandonFileInFlight() {
        this.jobRunner.abandonInFlight();
    }

    /**
     * Runs a troubleshoot pass over prepDir as a background job. Routing it through JobRunner buys
     * the same one-job-at-a-time discipline every other job gets. A reconcile's move-log rewrite can
     * then never race a concurrent apply/cull/commit against the same prep dir.
     *
     * @param prepDir {@link Path} the cull prep directory to troubleshoot
     * @return a {@link JobHandle} of {@link TroubleshootReport} a handle to the running job
     * @throws RunOutsideWorkingRootException if it sits outside the sift-prep root in force
     */
    public JobHandle<TroubleshootReport> troubleshoot(final Path prepDir) {
        this.requireUsableRoots();
        return this.jobRunner.submit(_ -> {
            this.phaseRunner.planned(List.of());
            this.cullEngine.refuseRunOutsideWorkingRoot(prepDir);
            return this.troubleshooter.troubleshoot(prepDir);
        });
    }

    /**
     * Records one answer a user gave to a damaged run, and carries out whatever it settles.
     *
     * <p>Not a job. Each of these appends one ledger line or renames one shard, and a reader
     * answering a finding must not be made to wait behind a running sift.
     *
     * @param prepDir {@link Path} the run being answered
     * @param answer {@link ChoiceAnswer} what the user chose
     * @param answeredOn {@link AnswerSource} which surface they chose it on
     */
    public void answer(final Path prepDir, final ChoiceAnswer answer, final AnswerSource answeredOn) {
        this.requireUsableRoots();
        switch (answer) {
            case final ChoiceAnswer.SkipMissingSource skip ->
                    this.prepDirRemedies.skipMissingSource(prepDir, skip.source(), answeredOn);
            case final ChoiceAnswer.ResolveOverlap overlap ->
                    this.prepDirRemedies.resolveOverlap(prepDir, overlap.file(), overlap.resolution(), answeredOn);
            case final ChoiceAnswer.ResolveCorruptSidecar sidecar ->
                    this.prepDirRemedies.resolveCorruptSidecar(prepDir, sidecar.montage(), sidecar.resolution(),
                            answeredOn);
            // The only answer that touches a file rather than the ledger, and the only one whose
            // record is the rename itself.
            case final ChoiceAnswer.SetAsideStrayShard stray ->
                    this.prepDirRemedies.setAsideStrayShard(prepDir, stray.strayShard());
        }
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
        return this.jobRunner.submit(_ -> {
            this.phaseRunner.planned(List.of());
            return this.prepDirDoctor.purgeCompleted(this.pathsPort.cullPrep());
        });
    }

    /**
     * Gives up on prepDir as a background job. It refuses a COMPLETE run, since purgeCompleted() is
     * that state's own verb, then retires any watcher polling it and delegates to
     * PrepDirRemedies.discard() for the file work. The watcher must be disarmed before the graveyard
     * move starts, or an auto-resume could fire against a prep dir already being dismantled. Routing
     * through JobRunner buys the same one-job-at-a-time discipline every other job gets, so a
     * discard can never race a re-prep of the scope it is giving up on.
     *
     * @param prepDir {@link Path} the cull prep directory to discard
     * @return a {@link JobHandle} of {@link DiscardReport} a handle to the running job
     * @throws RunOutsideWorkingRootException if it sits outside the sift-prep root in force
     */
    public JobHandle<DiscardReport> discard(final Path prepDir) {
        this.requireUsableRoots();
        return this.jobRunner.submit(_ -> {
            this.phaseRunner.planned(List.of(DISCARDING));
            this.cullEngine.refuseRunOutsideWorkingRoot(prepDir);
            if (this.prepDirDoctor.diagnose(prepDir).state() == PrepDirHealth.State.COMPLETE) {
                throw new RunAlreadyFinishedException(prepDir);
            }
            this.cullEngine.disarmWatch(prepDir);
            final DiscardReport report =
                    this.phaseRunner.run(DISCARDING, progress -> this.prepDirRemedies.discard(prepDir, progress));
            // After the file work, so a discard that threw leaves the run still occupying its scope
            // in the ledger as well as on disk.
            this.cullEngine.recordDiscard(prepDir.getFileName().toString());
            return report;
        });
    }

    /**
     * The instructions a reader hands to the agent they drive themselves, for one waiting run.
     *
     * <p>Names the categories the run was prepped with, so an answer written days later is judged
     * against the set it was asked for.
     *
     * @param prepDir {@link Path} the run to write instructions for
     * @return {@link String} the text to hand an agent
     */
    public String launchPromptFor(final Path prepDir) {
        this.requireUsableRoots();
        return this.cullEngine.launchPromptFor(prepDir);
    }

    /**
     * Sets a run's rejected answers aside and hands back the instructions asking for them again.
     *
     * <p>One call because it is one gesture: the sheets free to be answered afresh, and the words to
     * ask with. Either one on its own is half a remedy.
     *
     * <p>Diagnosed here rather than trusting what the card was drawn from. That reading can be
     * minutes old, and a watch or another window can have moved the run since. Only sheets this
     * reading blames are set aside.
     *
     * <p>Not a job, so it claims no job slot and blocks nothing. What that cannot rule out is a
     * watch resuming the run in the same moment. That resume then meets a sheet with no answer and
     * reports the run as still waiting, which is what it now is.
     *
     * @param prepDir {@link Path} the run
     * @return {@link String} the text to hand an agent
     * @throws RunOutsideWorkingRootException if it sits outside the sift-prep root in force
     * @throws NothingToRedoException if the run's diagnosis blames no sheet
     */
    public String redoRejectedAnswers(final Path prepDir) {
        this.requireUsableRoots();
        this.cullEngine.refuseRunOutsideWorkingRoot(prepDir);
        final List<Finding> findings = this.prepDirDoctor.diagnose(prepDir).findings();
        final List<String> sheets = LaunchPrompt.sheetsToRedo(findings);
        if (sheets.isEmpty()) {
            throw new NothingToRedoException(prepDir);
        }
        final String prompt = this.cullEngine.redoPromptFor(prepDir, findings);
        this.prepDirRemedies.setAsideAnswers(prepDir, sheets);
        return prompt;
    }

    /**
     * Whether a watcher is currently polling one waiting run's prep dir.
     *
     * @param prepDir {@link Path} the cull prep directory to check
     * @return boolean true if a watcher is currently polling it
     */
    public boolean isWatchActive(final Path prepDir) {
        return this.cullEngine.isWatchActive(prepDir);
    }

    /**
     * Asks to be told whenever a run moves with nobody pressing anything.
     *
     * @param listener {@link Runnable} what to run, on whatever thread caused the change. That can
     *     be the one that paints, since a startup scan arms from it. A listener marshals for itself
     *     and does anything slow somewhere else
     */
    public void onRunsChanged(final Runnable listener) {
        this.runChanges.onMoved(listener);
    }

    /**
     * Asks to be told whenever a sift continues with nobody pressing anything.
     *
     * @param listener {@link AutoResumedSifts.Listener} what to run, on the watcher's own polling
     *     thread. A listener marshals for itself
     */
    public void onSiftAutoResumed(final AutoResumedSifts.Listener listener) {
        this.autoResumedSifts.onResumed(listener);
    }

    /**
     * Asks to be told each time the job that was running finishes.
     *
     * <p>Says nothing about which job it was or how it went, and does not promise the runner is
     * still idle by the time the listener runs.
     *
     * @param listener {@link Runnable} what to run, on the finished job's own thread rather than
     *     the caller's
     */
    public void onJobFinished(final Runnable listener) {
        this.jobRunner.onJobFinished(listener);
    }

    /**
     * Whether a job's work is currently executing.
     *
     * <p>Going false is the point at which a job's file work is known to be over. Any effect the
     * job produces lands earlier, so waiting on one of those instead leaves whatever the job does
     * afterwards still running.
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
     * Refuses the call when the folder roots it would reach are not usable.
     *
     * <p>The guard sits here rather than in a screen, because both a screen and a command line pass
     * through this class. A check written into either one would be walked past by the other.
     *
     * @throws PathsMisconfiguredException if any of the three roots is unset, unparsable, missing,
     *     unreadable, or overlapping another
     */
    private void requireUsableRoots() {
        this.rootsGuard.requireUsable();
    }

    /**
     * Whether the spend ledger parses, which is the condition its repair exists for.
     *
     * <p>The ledger fails loud at its own boundary, so reading it is the only way to ask. An absent
     * one reads as empty rather than throwing, and needs no repair either.
     *
     * @return boolean true where the record can be read, false where a line defeats the parser
     */
    private boolean spendLedgerIsReadable() {
        try {
            this.spendLedger.read();
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Thrown when a fresh cull or curate is refused because an unfinished run already occupies the
     * scope's own prep dir. It carries that run, diagnosed, so a caller routes the user to the
     * right way out without parsing the message. Which remedy each state offers:
     * {@code app/docs/design/application/service/cull-engine.md}.
     */
    public static sealed class ScopeOccupiedException extends IllegalStateException
            permits CurateConflictException {
        private final transient CullRunSummary occupant;

        /**
         * Creates the exception, rendering the refusal message from the occupying run.
         *
         * <p>Public, like the refusals in {@code port.in}. A facade whose refusals only it can
         * construct cannot be stood in for.
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
     * Thrown when a fresh sift is refused because its timeframe shares photos with unfinished sifts
     * being any of them. It carries every one of them, diagnosed, so a caller can name them all.
     *
     * <p>Apart from {@link ScopeOccupiedException}, which is the exact-tag case. A prep dir is
     * claimed by the tag its scope comes to, so the whole of 2019 and June 2019 are unrelated keys.
     * Nothing about the claim stops the two running together, and each would sheet and pay for June
     * a second time.
     *
     * <p>Carries a list rather than the first one found. A reader told about one deals with it,
     * comes back, and is refused by the next.
     */
    public static final class ScopeOverlapsException extends IllegalStateException {
        private final transient CullScope.Year chosen;
        private final transient List<CullRunSummary> across;

        /**
         * Creates the exception over the timeframe chosen and the unfinished runs it overlaps.
         *
         * @param chosen {@link CullScope.Year} the timeframe the caller asked to sift
         * @param across a {@link List} of {@link CullRunSummary} the unfinished runs it overlaps
         */
        public ScopeOverlapsException(final CullScope.Year chosen, final List<CullRunSummary> across) {
            super(CullScope.tag(chosen) + " overlaps "
                    + String.join(", ", across.stream().map(CullRunSummary::scope).toList())
                    + (across.size() == 1
                    ? ", which is a sift you have not finished. Finish or discard it first."
                    : ", which are sifts you have not finished. Finish or discard them first."));
            this.chosen = chosen;
            this.across = List.copyOf(across);
        }

        /**
         * Returns the timeframe the caller asked to sift.
         *
         * @return {@link CullScope.Year} the chosen scope
         */
        public CullScope.Year chosen() {
            return this.chosen;
        }

        /**
         * Returns the unfinished runs the chosen timeframe overlaps, diagnosed.
         *
         * @return a {@link List} of {@link CullRunSummary} the runs in the way
         */
        public List<CullRunSummary> across() {
            return this.across;
        }
    }

    /**
     * Thrown by {@code curate()} in place of a plain {@link ScopeOccupiedException} whenever the
     * refusal lands after its sort has already moved real files. An auto-resolved
     * {@code OldestYear} scope cannot be checked until its year is known, which is only after the
     * sort. And a scope free when {@code curate()} was called can be taken during a long one.
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
     * screen being drawn and the button being pressed - a watcher applying an agent's last shard
     * does exactly that, unattended. So this is a refusal a reader meets rather than a state only a
     * caller writing the wrong code could reach.
     *
     * <p>Thrown before any watcher is disarmed and before any file moves, so nothing was lost by
     * asking for the wrong verb.
     */
    public static final class RunAlreadyFinishedException extends IllegalStateException {

        /**
         * Creates the exception, naming the run that had already finished.
         *
         * @param prepDir {@link Path} the run that was asked to be thrown away
         */
        public RunAlreadyFinishedException(final Path prepDir) {
            super("This sift finished before it could be discarded. "
                    + "Clear it with the finished runs instead. Its records are at " + prepDir + ".");
        }
    }

    /**
     * Thrown when asking for a run's answers to be written again is refused, because a fresh
     * reading of it blames no sheet.
     *
     * <p>Three ways there. An earlier press already set those answers aside, and this reading finds
     * a sheet with no shard rather than one with a bad shard. The run was put right between the card
     * being drawn and the button being pressed, by a watch or by another window. Or what is wrong
     * with it is not a sheet's fault at all, and writing one sheet again answers none of that.
     *
     * <p>Carries the prep dir. Its message folds the first two into one, since a reader cannot act
     * on the difference and both leave the run already handled. It names no way on, because the
     * causes do not share one: a waiting run can be carried on and a blocked one cannot. Nothing is
     * set aside before this throws, so nothing was lost either way.
     */
    public static final class NothingToRedoException extends IllegalStateException {

        /**
         * Creates the exception, naming the run.
         *
         * @param prepDir {@link Path} the run whose answers were to be written again
         */
        public NothingToRedoException(final Path prepDir) {
            super("Nothing in " + prepDir + " is waiting to be judged again. It might have already "
                    + "been handled in the meantime, or what is wrong with it may not be a sheet's "
                    + "fault.");
        }
    }

    /**
     * Thrown when a fresh cull or curate is refused because scope's own prep dir could not be read
     * at all, which is a different fault from a diagnosed run occupying it. Refusing is the same
     * safe direction a {@link ScopeOccupiedException} takes: proceeding would let a fresh prep clear
     * a directory nobody could confirm was actually empty. Nothing here was diagnosed, so it carries
     * the prep dir path and the read failure rather than a fabricated run.
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
     * Thrown when work on a run is refused because its prep dir does not sit under the working root
     * now in force. The run belongs to a folder this app has moved off, so working it would touch a
     * tree the current settings do not name.
     *
     * <p>Raised from inside the job rather than at the call, because the folder roots can move while
     * a caller is queueing for the job slot. A check before that wait answers about roots that may
     * be replaced before the job starts.
     */
    public static final class RunOutsideWorkingRootException extends IllegalStateException {
        private final transient Path prepDir;

        /**
         * Creates the exception, naming the prep dir that sits outside the roots in force.
         *
         * <p>Names no verb and offers no discard. Several calls raise this, discarding among them,
         * so a message wording either would be wrong on most of them.
         *
         * @param prepDir {@link Path} the prep dir that could not be worked
         */
        public RunOutsideWorkingRootException(final Path prepDir) {
            super("The sift at " + prepDir + " is not inside the Working root currently saved. "
                    + "Point the Working root back at the folder holding it to work on it again.");
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
