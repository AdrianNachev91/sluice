package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.application.port.out.SpendLedgerPort;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.LaunchPrompt;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.paths.Containment;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Orchestrates a whole cull job: prep, then dispatch, then apply (see
 * {@link #buildFreshAndDispatch}). It also owns {@link #resume} for a job still sitting on shards,
 * and the rules that decide whether a fresh run may start at all. A watcher's own lifecycle
 * lives in {@link CullWatchers}. The dispatch step is conditional, not a fixed stage: it runs only
 * while a montage still lacks a shard.
 */
final class CullEngine {

    private static final Logger log = LoggerFactory.getLogger(CullEngine.class);

    private static final String PREPPING = "Reading photos...";
    private static final String CULLING = "Sifting...";
    private static final String APPLYING = "Applying decisions...";

    // What a sift reports, in order.
    static final List<String> FRESH_PHASES = List.of(PREPPING, CULLING, APPLYING);

    // A resume enters a prep dir that has already been read, so it never reports the first of them.
    static final List<String> RESUME_PHASES = List.of(CULLING, APPLYING);

    private final MontageRenderer montageRenderer;
    private final CullDispatcher cullDispatcher;
    private final ApplyEngine applyEngine;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final MediaStore mediaStore;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final ShardTallyCalculator shardTallyCalculator;
    private final CullWatchers cullWatchers;
    private final PrepDirDoctor prepDirDoctor;
    private final PrepDirRemedies prepDirRemedies;
    private final PathsPort pathsPort;
    private final RootsGuard rootsGuard;
    private final SpendLedgerPort spendLedger;
    private final SpendEstimator spendEstimator;
    private final SecretStore secretStore;

    /**
     * Wires together every collaborator this engine dispatches cull jobs through.
     *
     * @param montageRenderer {@link MontageRenderer} builds montages from a scope
     * @param cullDispatcher {@link CullDispatcher} runs the cull phase
     * @param applyEngine {@link ApplyEngine} runs the apply phase
     * @param cullPrepPort {@link CullPrepPort} reads/writes prep dir index state
     * @param cullSettings {@link CullSettings} configured provider and montage-grid settings
     * @param mediaStore {@link MediaStore} filesystem access for prep dirs
     * @param pathsPort {@link PathsPort} resolves repo-relative paths
     * @param jobRunner {@link JobRunner} runs cull jobs one at a time
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses whatever already occupies a scope
     * @param prepDirRemedies {@link PrepDirRemedies} archives a completed run out of the way
     * @param rootsGuard {@link RootsGuard} refuses a resume whose folder roots are not usable
     * @param spendLedger {@link SpendLedgerPort} records what each run consumed
     * @param secretStore {@link SecretStore} says whether the configured provider's credential is held
     * @param watchPollInterval {@link Duration} how often a watcher re-checks its prep dir
     * @param runChanges {@link RunChanges} told whenever a watch moves a run with nobody watching
     */
    CullEngine(final MontageRenderer montageRenderer, final CullDispatcher cullDispatcher,
               final ApplyEngine applyEngine,
               final CullPrepPort cullPrepPort, final CullSettings cullSettings, final MediaStore mediaStore,
               final PathsPort pathsPort,
               final JobRunner jobRunner, final ProgressPort progressPort,
               final ApplyPlanner applyPlanner, final LedgerReader ledgerReader,
               final PrepDirDoctor prepDirDoctor, final PrepDirRemedies prepDirRemedies,
               final RootsGuard rootsGuard, final SpendLedgerPort spendLedger,
               final SecretStore secretStore,
               final Duration watchPollInterval, final RunChanges runChanges) {
        this.secretStore = secretStore;
        this.spendLedger = spendLedger;
        this.spendEstimator = new SpendEstimator(spendLedger);
        this.rootsGuard = rootsGuard;
        this.montageRenderer = montageRenderer;
        this.cullDispatcher = cullDispatcher;
        this.applyEngine = applyEngine;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.mediaStore = mediaStore;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.shardTallyCalculator = new ShardTallyCalculator(cullPrepPort, applyPlanner, ledgerReader);
        this.cullWatchers = new CullWatchers(cullDispatcher::configuredProviderIs,
                this.shardTallyCalculator, watchPollInterval,
                prepDir -> this.resume(prepDir, false), runChanges);
        this.prepDirDoctor = prepDirDoctor;
        this.prepDirRemedies = prepDirRemedies;
        this.pathsPort = pathsPort;
    }

    /**
     * Re-arms a watcher for every resumable run found on disk. There is no persistent job store (see
     * WaitingCullJob's own doc), so restarting the app would otherwise stop watching every run armed
     * before it. Pipeline calls this explicitly, on behalf of a driving adapter that stays open
     * long enough for a watcher to be worth arming.
     *
     * <p>Resumable means WAITING or READY: the two states a run can leave without a person. WAITING
     * still expects shards, which is what a watcher watches for. READY has them all already, the
     * ordinary shape of a restart where the agent finished while the app was closed.
     *
     * <p>BLOCKED and DAMAGED are left alone. A blocked run would resume and block again on the same
     * findings. That spends a job slot at every launch to reach a verdict only the user can change.
     * A damaged run never reads as ready, so its watcher would poll for good.
     *
     * <p>Also a no-op while a job is running. A prep dir mid-job never diagnoses COMPLETE, since
     * decisions.json is written only near the end of a successful apply. So it can still look like
     * something to arm. JobRunner runs one job at a time, so a busy runner means that job is this
     * dir's own. Arming it would leave a phantom watcher for a job about to resolve by itself.
     * Anything genuinely waiting is picked up on the next run once the app is idle.
     */
    void armWatchesForResumableRuns() {
        if (this.jobRunner.isBusy()) {
            return;
        }
        // A root nobody could list arms nothing, which is what an empty one does too. Said out
        // loud because the two are the same action for opposite reasons, and only one of them
        // means there was nothing to arm.
        if (this.prepDirDoctor.runs(this.cullPrepRoot()) instanceof CullRuns.Listed(final List<CullRunSummary> runs)) {
            runs.stream()
                    .filter(run -> run.health().state() == State.WAITING || run.health().state() == State.READY)
                    .forEach(run -> this.cullWatchers.armWatch(run.prepDir()));
        }
    }

    /**
     * Prep always runs fresh: a scope's montages are rebuilt from Sorted every call.
     * MontageRenderer.build() clears whatever a prior run left in the same prep dir first. That
     * would destroy everything the prior run still held. Shards an agent was paid to produce, a
     * move-record log, the answers a user gave a troubleshoot screen. refuseIfScopeOccupied()
     * guards against that and fails loud instead.
     *
     * <p>Refused here, synchronously before submit(), for the earliest possible fail-fast.
     * claimScope() inside buildFreshAndDispatch() below asks the same question again once actually
     * running. The archive of a completed occupant happens only there, on the job's own thread.
     * Only that call can put the resulting graveyard path into the outcome.
     *
     * @param scope {@link CullScope} the media scope to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     */
    JobHandle<CullJobOutcome> cull(final CullScope scope) {
        this.refuseIfTheProviderHasNoCredential();
        this.refuseIfScopeOccupied(scope);
        this.refuseIfScopeOverlaps(scope);
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(FRESH_PHASES);
            return this.buildFreshAndDispatch(scope, handle.stopSignal());
        });
    }

    /**
     * Re-reads an existing prep dir (no montages regenerated) and picks up where the run left off,
     * this time with the caller's own allowPartial. Resume is safely re-runnable. It stays
     * read-only until the shard set actually validates.
     *
     * <p>What it does next depends only on which shards are on disk. A montage still without one
     * means the run is genuinely unfinished, so dispatch runs again. A resume triggered too early
     * then lands right back in Waiting with a freshly recomputed tally. A full shard set means only
     * apply is left, so no culler is entered at all.
     *
     * <p>The roots check runs here rather than only on {@link Pipeline}'s own way in. A watcher's
     * auto-resume calls this method directly, so a check at the facade alone would be walked past
     * by the one caller nobody is watching. Apply moves files, and an unusable working root gets
     * silently recreated the moment a move resolves a path under it.
     *
     * <p>Taking the job slot can mean waiting, and the roots can move during that wait. So where
     * the prep dir sits is asked again inside the job, once this call is certain to be the next one
     * to run. Asking only before the wait would answer about roots that a settings save then
     * replaces. That admits a run belonging to a folder the app has since moved off.
     *
     * @param prepDir {@link Path} the existing prep dir to resume
     * @param allowPartial boolean whether a partial shard set is acceptable
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     * @throws PathsMisconfiguredException if the folder roots are unset, missing, or overlapping.
     *         Thrown from this call for roots already unusable, and delivered on the returned
     *         handle for roots that became so while this call waited for the job slot
     */
    JobHandle<CullJobOutcome> resume(final Path prepDir, final boolean allowPartial) {
        this.rootsGuard.requireUsable();
        return this.jobRunner.submit(handle -> {
            this.phaseRunner.planned(RESUME_PHASES);
            this.refuseRunOutsideTheWorkingRoot(prepDir);
            return this.dispatchAndApply(this.cullPrepPort.readIndex(prepDir), allowPartial,
                    handle.stopSignal(), null);
        });
    }

    /**
     * The instructions for the agent a reader drives themselves, for one waiting run.
     *
     * @param prepDir {@link Path} the run to write instructions for
     * @return {@link String} the text to hand an agent
     */
    String launchPromptFor(final Path prepDir) {
        return LaunchPrompt.forRun(this.cullPrepPort.readIndex(prepDir));
    }

    /**
     * The instructions for writing a run's rejected answers again.
     *
     * @param prepDir {@link Path} the run
     * @param findings a {@link List} of {@link Finding} what the diagnosis blamed
     * @return {@link String} the text to hand an agent
     */
    String redoPromptFor(final Path prepDir, final List<Finding> findings) {
        return LaunchPrompt.forRedo(this.cullPrepPort.readIndex(prepDir), findings);
    }

    /**
     * Delegates to {@link CullWatchers}: whether a watcher is currently polling prepDir.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean whether a watcher is currently active for it
     */
    boolean isWatchActive(final Path prepDir) {
        return this.cullWatchers.isWatchActive(prepDir);
    }

    /**
     * Stops whatever watcher is polling one prep dir.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     */
    void disarmWatch(final Path prepDir) {
        this.cullWatchers.disarmWatch(prepDir);
    }

    /**
     * Delegates to {@link CullWatchers#disarmAll}. {@link Pipeline#stopAllWatching}'s own route in.
     */
    void disarmAllWatches() {
        this.cullWatchers.disarmAll();
    }

    /**
     * Whether a run on the configured provider can spend anything.
     *
     * <p>Read off the provider's type rather than off a forecast. A forecast is a provider counting
     * one real request, and a caller asking this has no request to count. A provider of type
     * {@link ProviderType#API} is one this app calls a model through, which is what spending means
     * here.
     *
     * <p>The two questions coincide today rather than by construction. Every registered API
     * provider bills, and the one MANUAL provider forecasts {@code NoSpend}. A locally-run model
     * would be the first to separate them: this app would call it, so it is API, and it would cost
     * nothing. A screen would then show a token figure and a money disclaimer for a free run.
     *
     * <p>The fix at that point is a third {@link ProviderType} rather than a defaulted question on
     * the port. A new constant makes the provider declare which it is. A default would guess for
     * it, and guess wrong for exactly the provider that prompted the change.
     * {@link VisionCuller#type} refuses a default for the same reason.
     *
     * @return boolean true when a run on the configured provider can spend
     */
    boolean configuredProviderSpends() {
        return this.cullDispatcher.configuredProviderIs(ProviderType.API);
    }

    /**
     * What sifting this many photos is expected to consume, asked before anything is prepared.
     *
     * <p>A provider that cannot spend, per {@link #configuredProviderSpends}, estimates zero of
     * both token counts rather than declining to answer.
     *
     * @param photos how many photos the scope holds
     * @return {@link SpendEstimate} what a sift over them is expected to consume
     */
    SpendEstimate estimateFor(final int photos) {
        return this.spendEstimator.estimateBeforePreparing(photos,
                this.configuredProviderSpends(),
                this.cullSettings.providerSettings().model(),
                this.cullSettings.montage());
    }

    /**
     * Throws if the configured provider needs a credential and no tier holds one.
     *
     * <p>Asked before a run is submitted, because everything a sift does before it needs the key is
     * wasted without it. Prep decodes every photo in the scope and writes a montage set, and
     * {@link #claimScope} takes the scope before that. A run that discovers the missing key at
     * dispatch has already spent both. It also leaves a claimed scope behind, which its own next
     * attempt is then refused for.
     *
     * <p>A provider that authenticates with nothing names no credential, so this asks nothing of
     * the free path. {@link SecretStore#status} rather than a read: whether a key is held is the
     * whole question, and an engine has no use for the value.
     *
     * <p>Not asked on a resume. A resume runs no prep, and its dispatch refuses before it makes a
     * request. A run whose shards are all in enters no culler at all, and refusing that one would
     * strand work already paid for behind a key it does not need.
     *
     * @throws MissingCredentialException if the configured provider needs a credential and none is held
     * @throws SecretStoreException if a tier cannot say what it holds
     */
    void refuseIfTheProviderHasNoCredential() {
        final SecretId credential = this.cullDispatcher.configuredCredential();
        if (credential != null && this.secretStore.status(credential) instanceof SecretStatus.Absent) {
            throw new MissingCredentialException(credential,
                    "No credential is stored for the '" + credential.provider() + "' vision provider");
        }
    }

    /**
     * Throws if scope's own prep dir is occupied by a run that is not finished.
     *
     * <p>Occupancy is presence, not readability. Any prep dir holding at least one file occupies
     * its scope. A guard phrased around a readable index would miss the dirs most worth
     * protecting: the damaged ones, which can say nothing about themselves. Whatever a scope's
     * prep dir still holds, a fresh run over the top of it would wipe.
     *
     * <p>Every state but COMPLETE refuses. What differs by state is the way out, not whether a
     * refusal happens - cull-engine.md's table maps each one. READY is the one worth naming here: a
     * full shard set that has not applied yet is unspent work, one resume from landing.
     *
     * <p>A COMPLETE occupant is not refused here. {@link #claimScope} is what moves it aside.
     *
     * @param scope {@link CullScope} the scope to check
     */
    void refuseIfScopeOccupied(final CullScope scope) {
        this.occupantOf(scope)
                .filter(occupant -> occupant.health().state() != State.COMPLETE)
                .ifPresent(occupant -> {
                    throw new Pipeline.ScopeOccupiedException(occupant);
                });
    }

    /**
     * Throws if scope's timeframe runs across unfinished sifts without being any of them.
     *
     * <p>The other half of the guard above, and the half a prep dir's own claim cannot make. A
     * claim is on an exact tag, so an unfinished sift of June 2019 leaves the whole of 2019 free to
     * start. That one builds its own folder beside it and pays a second time for sheets the first
     * already holds. Both then hold decisions over the same photos, and whichever applies second
     * finds its files already moved.
     *
     * <p>Only a year scope can overlap. An {@code OldestN} names no timeframe, so nothing it covers
     * can be worked out from its tag, and it neither blocks nor is blocked.
     *
     * <p>A run whose own tag names no year is passed over rather than treated as overlapping. It
     * cannot be shown to share a month, and refusing on what cannot be established would block a
     * scope over nothing.
     *
     * <p>Reads the runs freshly rather than from a snapshot. The desktop makes the same check
     * against its last reading to grey Start early, and that copy can be a moment stale. This one
     * is the guarantee.
     *
     * @param scope {@link CullScope} the scope about to be sifted
     */
    void refuseIfScopeOverlaps(final CullScope scope) {
        if (!(scope instanceof final CullScope.Year chosen)) {
            return;
        }
        final String exact = CullScope.tag(chosen);
        final List<CullRunSummary> across = this.unfinishedRuns().stream()
                .filter(run -> !run.scope().equals(exact))
                .filter(run -> chosen.overlaps(CullScope.yearScopeOf(run.scope())))
                .toList();
        if (!across.isEmpty()) {
            throw new Pipeline.ScopeOverlapsException(chosen, across);
        }
    }


    /**
     * Builds scope's prep dir fresh, then dispatches and applies it.
     *
     * <p>claimScope() runs before anything else, and every outcome below carries what it returned.
     * The archive happens ahead of montage rendering, so every way this method can end is reachable
     * with a prior run already filed away. An outcome that dropped that fact would leave a user's
     * completed record looking like it disappeared.
     *
     * @param scope {@link CullScope} the media scope to cull
     * @param cancellation {@link CancellationSignal} signals whether cancellation has been requested
     * @return {@link CullJobOutcome} the outcome of this cull attempt
     */
    CullJobOutcome buildFreshAndDispatch(final CullScope scope, final CancellationSignal cancellation) throws Exception {
        final Path archivedPriorRun = this.claimScope(scope);
        // phaseRunner.run/PhaseWork are shared with sort/commit/rescue, which always return non-null -
        // keeping T itself non-null there avoids leaking a spurious "might be null" possibility
        // into those callers. Wrapping the result in Optional here instead keeps that shared
        // contract clean while still letting this call site express a real null case.
        final Optional<PrepDir> prep = this.phaseRunner.run(PREPPING,
                progress -> Optional.ofNullable(this.montageRenderer.build(scope, this.cullSettings.montage(),
                        progress, cancellation)));
        // Empty means the renderer itself stopped mid-render, clearing whatever it had written and
        // leaving no prep dir at all - nothing resumable exists. The renderer is the completion
        // authority here: this branches purely on its return value, never on re-checking disk state.
        if (prep.isEmpty()) {
            final var cancelled = new CullJobOutcome.Cancelled(this.nothingSpent(0), archivedPriorRun);
            this.recordSpend(CullScope.tag(scope), cancelled.cullReport(), RunEnding.CANCELLED);
            return cancelled;
        }
        // Prep just finished and wrote index.json, so a cancellation seen right here resolves
        // cleanly to Waiting too - a 0/N tally, nothing dispatched yet. No watcher is armed: an
        // auto-resume moments after a cancel would defy it.
        if (cancellation.isCancelled()) {
            final var waiting = new CullJobOutcome.Waiting(this.buildWaitingJob(prep.get()),
                    WaitingReason.CANCELLED, this.nothingSpent(prep.get().entries().size()), archivedPriorRun);
            this.recordSpend(prep.get().scope(), waiting.cullReport(), RunEnding.CANCELLED);
            return waiting;
        }
        return this.dispatchAndApply(prep.get(), false, cancellation, archivedPriorRun);
    }

    /**
     * Where cull runs are prepared, worked out on every call. Resolving it once at construction
     * would pin this engine to the folder the app happened to start in. A saved working root would
     * then reach every other engine and not this one.
     *
     * @return {@link Path} the sift-prep root under the working root
     */
    private Path cullPrepRoot() {
        return this.pathsPort.cullPrep();
    }

    /**
     * Refuses an address whose prep dir does not sit under the sift-prep root in force.
     *
     * <p>Checked again here rather than trusted from the caller's own check. Every caller submits a
     * job that can queue or wait before it runs. A root-moving save reaching in during that gap
     * leaves the caller's own check answering for roots that have since changed.
     *
     * <p>The roots are re-checked first, and not only for that reason. A save that cleared the
     * working root leaves nothing to resolve a sift-prep root from. Asking where the prep dir sits
     * has no answer at all until that case is ruled out.
     *
     * <p>Refusing costs the run nothing. Nothing has been read or moved at this point, and the run
     * stays on disk exactly as it was. Pointing the working root back at its folder makes it
     * reachable again.
     *
     * @param prepDir {@link Path} the prep dir this call was asked for
     * @throws PathsMisconfiguredException if the folder roots stopped being usable during the wait
     * @throws Pipeline.RunOutsideWorkingRootException if it sits outside the sift-prep root in force
     */
    void refuseRunOutsideTheWorkingRoot(final Path prepDir) {
        this.rootsGuard.requireUsable();
        if (!Containment.strictlyUnder(this.cullPrepRoot(), prepDir)) {
            throw new Pipeline.RunOutsideWorkingRootException(prepDir);
        }
    }

    /**
     * Frees scope's prep dir for a fresh run, and reports where a completed occupant was filed.
     *
     * <p>A COMPLETE run is archived into the graveyard rather than overwritten, and no confirmation
     * is asked. What such a run holds is a record of decisions already applied rather than work
     * still owed. Nothing is destroyed either: the archive keeps the same 30-day recovery window
     * every other graveyard entry gets. An occupant that has not finished is the other case, and
     * that one is refused outright rather than archived.
     *
     * @param scope {@link CullScope} the scope to free
     * @return {@link Path} the graveyard directory a completed occupant was archived into, or null
     *         if the scope was already free
     */
    private @Nullable Path claimScope(final CullScope scope) {
        final Optional<CullRunSummary> occupant = this.occupantOf(scope);
        if (occupant.isEmpty()) {
            return null;
        }
        final CullRunSummary run = occupant.get();
        if (run.health().state() != State.COMPLETE) {
            throw new Pipeline.ScopeOccupiedException(run);
        }
        log.info("Archiving the completed cull of {} before re-culling the same scope", run.scope());
        this.disarmWatch(run.prepDir());
        return this.prepDirRemedies.discard(run.prepDir()).graveyard();
    }

    /**
     * Every run on disk that has not finished.
     *
     * <p>A sift-prep root nobody could list answers none rather than throwing. A failure here would
     * otherwise refuse a scope on the strength of a read that established nothing. The exact-tag
     * guard reads the one prep dir it cares about, and refuses on its own terms.
     *
     * @return a {@link List} of {@link CullRunSummary} the unfinished runs
     */
    private List<CullRunSummary> unfinishedRuns() {
        if (this.prepDirDoctor.runs(this.cullPrepRoot()) instanceof CullRuns.Listed(final List<CullRunSummary> runs)) {
            return runs.stream().filter(run -> run.health().state() != State.COMPLETE).toList();
        }
        return List.of();
    }

    /**
     * The run currently occupying scope's own prep dir, diagnosed, or empty if nothing is there.
     *
     * <p>An empty directory is not an occupant. Nothing in it can be lost, and prep writes straight
     * into it.
     *
     * @param scope {@link CullScope} the scope to look up
     * @return an {@link Optional} {@link CullRunSummary} the occupying run, diagnosed
     * @throws Pipeline.ScopeUnreadableException if the prep dir's own occupancy could not be determined
     */
    private Optional<CullRunSummary> occupantOf(final CullScope scope) {
        final Path prepDir = this.cullPrepRoot().resolve(CullScope.tag(scope));
        return switch (this.occupancyOf(prepDir)) {
            case final Occupancy.Empty ignored -> Optional.empty();
            case final Occupancy.Occupied ignored -> Optional.of(this.prepDirDoctor.summaryOf(prepDir));
            case Occupancy.Unreadable(final RuntimeException cause) ->
                    throw new Pipeline.ScopeUnreadableException(prepDir, cause);
        };
    }

    /**
     * {@link #occupancyOf}'s own answer: a prep dir holds a file, holds none, or its own occupancy
     * could not even be determined. The third case is why this is not a boolean.
     */
    private sealed interface Occupancy {

        /**
         * The prep dir holds no file at all, and so nothing anybody could lose.
         */
        record Empty() implements Occupancy {
        }

        /**
         * The prep dir holds at least one file.
         */
        record Occupied() implements Occupancy {
        }

        /**
         * The prep dir's own occupancy could not be determined.
         *
         * @param cause {@link RuntimeException} the read failure
         */
        record Unreadable(RuntimeException cause) implements Occupancy {
        }
    }

    /**
     * Whether prepDir holds a file, holds none, or could not be read at all.
     *
     * <p>A boolean has no way to say "I do not know". {@link Occupancy.Unreadable} gives that third
     * answer its own vocabulary, distinct from occupied. {@link #occupantOf} can then refuse over an
     * unreadable dir without fabricating a diagnosis or a remedy to justify it. Conflating the two
     * would route a dropped network mount to DAMAGED's locked Discard, the same as a genuinely stuck
     * run. If the failure clears between the refusal and the discard, that destroys a healthy run.
     *
     * <p>Not knowing what is in prepDir is not the same as knowing it is empty, and the two possible
     * mistakes cost wildly different amounts. A needless refusal costs one confusing message.
     * Proceeding clears the dir. So an unreadable prep dir is never treated as empty here - refusing
     * is the one safe direction, whichever of the two unresolved cases caused it.
     *
     * <p>Guarded by a catch-all over both port calls. {@link MediaStore} constrains nothing about
     * what a read may throw, and the safe answer is the same whatever came back.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return {@link Occupancy} whether prepDir is empty, occupied, or could not be read
     */
    private Occupancy occupancyOf(final Path prepDir) {
        try {
            final boolean empty = !this.mediaStore.exists(prepDir) || this.mediaStore.listFiles(prepDir).isEmpty();
            return empty ? new Occupancy.Empty() : new Occupancy.Occupied();
        } catch (final RuntimeException e) {
            log.warn("Could not tell whether {} is occupied", prepDir, e);
            return new Occupancy.Unreadable(e);
        }
    }

    /**
     * Dispatch runs only while some montage still lacks a shard. Once every montage has one, the
     * agent has said everything it is going to say. Re-asking buys the same answer back, at the
     * cost of a fresh round of API calls or a redundant validation pass. So a resume with a full
     * shard set goes straight to apply, and apply's own gate becomes the single validator.
     *
     * <p>A refused apply therefore resolves to Blocked rather than propagating. Blocked is the
     * user's move: the shard set is complete, so nothing is left to wait for.
     *
     * <p>A CullException from the dispatch step means different things depending on the configured
     * provider's own ProviderType. From a MANUAL one it's the expected pause: resolved into Waiting,
     * run slot released. From an API one it's a genuine failure and propagates - it never throws
     * CullException to signal a cancellation. An API provider's cull() still lands in Waiting on
     * cancellation, but via the cancellation.isCancelled() check further down, after dispatch
     * returns normally rather than through this catch block.
     *
     * <p>A manual click racing an armed watcher can never leave two pollers on one job: the disarm
     * runs before anything else, on every path in. A fresh watcher is armed below only if the
     * outcome is Waiting again.
     *
     * @param prep {@link PrepDir} the prep dir to dispatch and apply
     * @param allowPartial boolean whether a partial shard set is acceptable
     * @param cancellation {@link CancellationSignal} signals whether cancellation has been requested
     * @param archivedPriorRun {@link Path} where a completed run of this scope was archived on the
     *         way in, or null. Always null on the resume path, which enters an existing prep dir
     *         rather than claiming a scope.
     * @return {@link CullJobOutcome} the outcome of this dispatch-and-apply attempt
     */
    private CullJobOutcome dispatchAndApply(final PrepDir prep, final boolean allowPartial,
                                            final CancellationSignal cancellation,
                                            final @Nullable Path archivedPriorRun)
            throws Exception {
        this.disarmWatch(prep.prepDir());
        final CullReport cullReport;
        final int montagesToDispatchFor = this.montagesWithoutAShard(prep);
        if (montagesToDispatchFor == 0) {
            cullReport = this.nothingSpent(prep.entries().size());
        } else {
            final CullOptions options =
                    new CullOptions(allowPartial, null, this.ceilingFor(prep, montagesToDispatchFor));
            try {
                cullReport = this.phaseRunner.run(CULLING,
                        progress -> this.cullDispatcher.cull(prep, options, progress, cancellation));
            } catch (final CullException e) {
                if (!this.cullDispatcher.configuredProviderIs(ProviderType.MANUAL)) {
                    this.recordSpend(prep.scope(), this.abandonedSpend(e, prep.entries().size()),
                            RunEnding.FAILED);
                    throw e;
                }
                // The exception names which montages are still missing a shard. Nothing downstream
                // reads that list - the outcome carries a tally, not montage names - so this is the
                // only place it can be seen at all. A watch that never converges is diagnosed from
                // here.
                log.info("Cull for {} is waiting on shards: {}", prep.scope(), e.getMessage());
                final WaitingCullJob job = this.buildWaitingJob(prep);
                // Not armed where this CullException is itself the provider's pause racing a
                // cancellation: an auto-resume moments after a cancel would defy it.
                if (!cancellation.isCancelled()) {
                    this.cullWatchers.armWatch(job.prepDir());
                }
                return this.recorded(prep, new CullJobOutcome.Waiting(job, WaitingReason.SHARDS_OUTSTANDING,
                        this.abandonedSpend(e, prep.entries().size()), archivedPriorRun));
            }
        }
        // Asked before the cancellation check below, because the two can both be true and only one
        // of them is about money. A run told to stop after it had already hit its ceiling still hit
        // its ceiling, and that is the half the user needs to see.
        if (cullReport.stoppedAtCeiling()) {
            return this.recorded(prep, new CullJobOutcome.Waiting(this.buildWaitingJob(prep),
                    WaitingReason.CEILING_REACHED, cullReport, archivedPriorRun));
        }
        // The one boundary check this method has for an automated provider: dispatch just
        // returned, either because it finished or because it stopped early on a cancellation. This
        // doubles as the pre-APPLYING check. It also covers the external-agent flow: its dispatch
        // never sees a cancellation mid-call, but a cancellation requested right after it still
        // lands here before apply moves anything.
        if (cancellation.isCancelled()) {
            return this.recorded(prep, new CullJobOutcome.Waiting(this.buildWaitingJob(prep),
                    WaitingReason.CANCELLED, cullReport, archivedPriorRun));
        }
        final Optional<ApplyReport> applyReport;
        try {
            applyReport = this.phaseRunner.run(APPLYING,
                    progress -> Optional.ofNullable(
                            this.applyEngine.apply(prep.prepDir(), new ApplyOptions(allowPartial), progress,
                                    cancellation)));
        } catch (final ApplyException e) {
            return this.recorded(prep, new CullJobOutcome.Blocked(this.buildWaitingJob(prep), e.findings(),
                    cullReport, archivedPriorRun));
        } catch (final RuntimeException e) {
            // Apply moves files, so it can fail on the filesystem at any point, and by here the
            // vision pass has already been billed for. Its report is right there in cullReport, so
            // the one thing that must not happen is losing it on the way out.
            this.recordSpend(prep.scope(), cullReport, RunEnding.FAILED);
            throw e;
        }
        // Empty means apply() itself stopped mid-loop and skipped its finalizers, so
        // decisions.json was never written. The prep dir still reads as a waiting job, the same
        // authority rule the renderer's own empty return follows above. No watcher is armed here
        // either, for the same reason the pre-APPLYING check above doesn't: an auto-resume
        // moments after a cancel would defy it.
        return this.recorded(prep, applyReport
                .<CullJobOutcome>map(applied -> new CullJobOutcome.Applied(cullReport, applied, archivedPriorRun, null))
                .orElseGet(() -> new CullJobOutcome.Waiting(this.buildWaitingJob(prep),
                        WaitingReason.CANCELLED, cullReport, archivedPriorRun)));
    }

    /**
     * Records what the run consumed and hands the outcome straight back.
     *
     * <p>Every way this method can end with an outcome goes through here. The two that leave by
     * throwing record on their own way out, since there is no outcome to read a report from.
     * Wrapping the caller instead would put the recording somewhere the report is out of scope,
     * which is how the counts get lost.
     *
     * @param prep {@link PrepDir} the run's prep directory
     * @param outcome {@link CullJobOutcome} the outcome to record and return
     * @return {@link CullJobOutcome} that same outcome
     */
    private CullJobOutcome recorded(final PrepDir prep, final CullJobOutcome outcome) {
        final boolean recorded = this.recordSpend(prep.scope(), outcome.cullReport(), endingOf(outcome));
        if (outcome instanceof final CullJobOutcome.Applied applied) {
            return new CullJobOutcome.Applied(applied.cullReport(), applied.applyReport(),
                    applied.archivedPriorRun(),
                    recorded ? this.tokensAcrossEveryLeg(prep.scope()) : null);
        }
        return outcome;
    }

    /**
     * Every token this run spent, over all of its calls, or null where the ledger could not be read.
     *
     * <p>Read back out of the ledger rather than accumulated in memory. Nothing outlives a run:
     * each call is its own job, and the one that finishes carries only what it consumed itself.
     *
     * @param scope {@link String} the run's scope tag
     * @return {@link Long} the tokens, or null where the ledger could not be read
     */
    private @Nullable Long tokensAcrossEveryLeg(final String scope) {
        try {
            final List<SpendLedgerEntry> inTimeframe = this.spendLedger.read().stream()
                    .filter(entry -> entry.scope().equals(scope))
                    .toList();
            return inTimeframe.stream().skip(runStart(inTimeframe))
                    .mapToLong(entry -> entry.inputTokens() + entry.outputTokens())
                    .sum();
        } catch (final RuntimeException e) {
            log.warn("Could not read back what the sift of {} spent", scope, e);
            return null;
        }
    }

    /**
     * Where this run's own lines begin, among every line one scope has ever produced.
     *
     * <p>Just after the newest earlier line that freed the scope. Freeing it is what lets a fresh
     * sift have it, so anything older than that belongs to a run this one replaced. Applying frees
     * it and so does giving up on one, which is why the question sits on {@link RunEnding} rather
     * than on a list of endings kept here.
     *
     * <p>The newest line of all is this run's own and is never the boundary, which is what lets
     * this be called immediately after writing it.
     *
     * @param inTimeframe a {@link List} of {@link SpendLedgerEntry} one scope's lines, oldest first
     * @return int the index this run's first line sits at
     */
    private static int runStart(final List<SpendLedgerEntry> inTimeframe) {
        final List<SpendLedgerEntry> earlier = inTimeframe.subList(0, Math.max(0, inTimeframe.size() - 1));
        return IntStream.range(0, earlier.size())
                .filter(i -> earlier.get(i).ending().freedTheScope())
                .max().orElse(-1) + 1;
    }

    /**
     * Records that a run was given up on, so the scope it held reads as free from here down.
     *
     * <p>The giving up spent nothing, and the run's own calls are already on their own lines. What
     * this line carries is the ending. A sum over one scope stops at it, and without it the next
     * run of that scope is billed for the one the reader threw away.
     *
     * <p>Reported rather than raised, for the reason {@code recordSpend} is. The run is gone by the
     * time this is called, so a failure here can only cost the record.
     *
     * @param scope {@link String} the discarded run's scope tag
     */
    void recordDiscard(final String scope) {
        this.recordSpend(scope, CullReport.nothingSpent(this.cullSettings.provider(), 0), RunEnding.DISCARDED);
    }

    /**
     * How many montages in prep have no shard file on disk. A plain existence check per montage,
     * never a parse. Whether those shards are any good is apply's own gate to decide. A shard that
     * turns out unreadable is a finding there, not a reason to re-dispatch.
     *
     * <p>Zero means there is nothing for a culler to judge, so dispatching would only produce an
     * empty report. A scope with no montages at all answers zero for the same reason.
     *
     * <p>Anything above zero is what the spend ceiling is sized against. It bounds the work this
     * call will pay for rather than matching it. A montage whose sidecar cannot be read has no
     * shard, and is skipped rather than dispatched for.
     *
     * @param prep {@link PrepDir} the prep dir to check
     * @return the number of montages still needing judgement
     */
    private int montagesWithoutAShard(final PrepDir prep) {
        return (int) prep.entries().stream()
                .filter(montage -> !this.cullPrepPort.hasShard(prep.prepDir(), montage))
                .count();
    }

    /**
     * Builds the waiting-job snapshot for a prep dir that isn't fully resolved yet.
     *
     * @param prep {@link PrepDir} the prep dir to snapshot
     * @return {@link WaitingCullJob} the waiting job for that prep dir
     */
    private WaitingCullJob buildWaitingJob(final PrepDir prep) {
        return new WaitingCullJob(
                prep.scope(), prep.prepDir(), this.shardTallyCalculator.tally(prep),
                this.lastModifiedOrEpoch(prep.prepDir()));
    }

    /**
     * A report for a stage of a run that reached no model at all.
     *
     * @param montagesSkipped how many montages went unjudged
     * @return {@link CullReport} a zero report against the configured provider
     */
    private CullReport nothingSpent(final int montagesSkipped) {
        return CullReport.nothingSpent(this.cullSettings.provider(), montagesSkipped);
    }

    /**
     * A report for a dispatch that threw, which is the provider's own where it built one.
     *
     * <p>A provider that counts nothing throws without a report, and its run is recorded as having
     * judged nothing. A provider that counts hands over what it had reached. The line then says how
     * many montages it judged, and what those cost, before it gave up.
     *
     * @param failure {@link CullException} what the dispatch threw
     * @param montages how many montages the run held, for the case where nothing was counted
     * @return {@link CullReport} the abandoned run's own report
     */
    private CullReport abandonedSpend(final CullException failure, final int montages) {
        final CullReport report = failure.report();
        return report == null ? this.nothingSpent(montages) : report;
    }

    /**
     * The ceiling this run may not spend past, or null when its provider spends nothing.
     *
     * <p>Worked out here rather than by the provider, because the half that cannot be counted comes
     * from what runs on this install have cost. The provider knows only its own request.
     *
     * <p>The two arms are sized on different counts, and the difference is the point.
     *
     * <p>The token arm charges per montage attempted. Its budget divides the estimate by the count
     * the estimate was built over, which reduces it to one montage's expected cost. So the two
     * counts have to be the same number. A wider estimate over a narrower divisor would loosen the
     * budget in proportion.
     *
     * <p>The call arm is a backstop against a defect, so it is sized on the whole index. Its only
     * property is that a correct run cannot reach it, and the montages still owing a shard cannot
     * carry that property. A shard file present but unreadable counts as done here, and is
     * dispatched for anyway. The culler judges a shard by reading it, while this counts by
     * existence. A bound covering the whole index cannot be crossed by a loop that walks it.
     *
     * @param prep {@link PrepDir} the prepared run
     * @param montagesToDispatchFor how many of its montages still owe a shard file, and the count
     *        the estimate must be built over
     * @return {@link SpendCeiling} the run's ceiling, or null
     */
    private @Nullable SpendCeiling ceilingFor(final PrepDir prep, final int montagesToDispatchFor) {
        final SpendEstimate estimate = this.spendEstimator.estimate(montagesToDispatchFor,
                this.cullDispatcher.forecast(prep), this.cullSettings.providerSettings().model(),
                this.cullSettings.montage());
        return this.spendEstimator.ceilingFor(prep.entries().size(), montagesToDispatchFor, estimate);
    }

    /**
     * Records a run that ended with an outcome, reporting a failure rather than raising one.
     *
     * <p>The ledger is a record of the run, never part of doing it. Everything it describes has
     * already happened by the time this is called, so a failure here can only lose the record. A
     * user who cannot be told what a sift cost is better off than one whose sift died telling them.
     *
     * <p>Building the entry sits inside that guard as well as writing it. A provider is free to
     * answer a report the ledger's own boundary check refuses, and refusing it has to cost the
     * record rather than the run.
     *
     * @param scope {@link String} the run's scope tag
     * @param report {@link CullReport} what the run judged and consumed
     * @param ending {@link RunEnding} how it ended
     * @return boolean true where the line reached the ledger
     */
    private boolean recordSpend(final String scope, final CullReport report, final RunEnding ending) {
        try {
            final MontageConfig grid = this.cullSettings.montage();
            this.spendLedger.append(new SpendLedgerEntry(Instant.now(), scope, report.spend().providerId(),
                    report.spend().modelId(), grid.tileSize(), grid.tilesPerRow(), report.montagesCulled(),
                    report.montagesSkipped(), report.apiCalls(), report.spend().inputTokens(),
                    report.spend().outputTokens(), ending));
            return true;
        } catch (final RuntimeException e) {
            log.warn("Could not record what the sift of {} spent", scope, e);
            return false;
        }
    }

    /**
     * How the ledger names the way an outcome ended.
     *
     * @param outcome {@link CullJobOutcome} the outcome to classify
     * @return {@link RunEnding} the ledger's own name for it
     */
    private static RunEnding endingOf(final CullJobOutcome outcome) {
        return switch (outcome) {
            case final CullJobOutcome.Applied ignored -> RunEnding.APPLIED;
            case final CullJobOutcome.Blocked ignored -> RunEnding.BLOCKED;
            case final CullJobOutcome.Cancelled ignored -> RunEnding.CANCELLED;
            case CullJobOutcome.Waiting(_, final WaitingReason reason, _, _) -> switch (reason) {
                case CANCELLED -> RunEnding.CANCELLED;
                case SHARDS_OUTSTANDING -> RunEnding.SHARDS_OUTSTANDING;
                case CEILING_REACHED -> RunEnding.CEILING_REACHED;
            };
        };
    }

    /**
     * prepDirPath's mtime, or the epoch if it cannot be read.
     *
     * <p>This snapshot sits on a live cull job's resolution path - cancellation, a provider's own
     * pause, a blocked apply, an empty apply return. Throwing here would replace that outcome with
     * a crash instead of the Waiting or Blocked result it should have been. The epoch reads as "as
     * old as anything", the same degrade {@link PrepDirDoctor#diagnose} uses for the same failure.
     *
     * @param prepDirPath {@link Path} the prep directory to check
     * @return {@link Instant} the last-modified instant, or {@link Instant#EPOCH} if unreadable
     */
    private Instant lastModifiedOrEpoch(final Path prepDirPath) {
        try {
            return this.mediaStore.lastModifiedTime(prepDirPath);
        } catch (final RuntimeException e) {
            log.warn("Could not read the mtime of {}, ageing it as the epoch", prepDirPath, e);
            return Instant.EPOCH;
        }
    }
}
