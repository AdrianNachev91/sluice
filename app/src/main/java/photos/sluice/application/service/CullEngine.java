package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.MontageRenderer;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Orchestrates a whole cull job: prep, then dispatch, then apply (see
 * {@link #buildFreshAndDispatch}). It also owns {@link #resume} for a job still sitting on shards,
 * and the scope-occupancy rules that decide whether a fresh run may start at all. The watch-mode
 * lifecycle itself lives in {@link CullWatchers}. The dispatch step is conditional, not a fixed
 * stage: it runs only while a montage still lacks a shard.
 *
 * <p>Not a Spring bean. {@link Pipeline} builds the one instance it needs, the same way it builds
 * the {@link CullWatchers} this engine delegates to. {@link #refuseIfScopeOccupied} and
 * {@link #buildFreshAndDispatch} stay package-private rather than private. {@link CurateEngine}'s
 * own cull stage reuses both directly instead of duplicating them.
 */
final class CullEngine {

    private static final Logger log = LoggerFactory.getLogger(CullEngine.class);

    private static final String PREPPING = "Building montages...";
    private static final String CULLING = "Culling...";
    private static final String APPLYING = "Applying decisions...";

    private final MontageRenderer montageRenderer;
    private final CullDispatcher cullDispatcher;
    private final ApplyEngine applyEngine;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final MediaStore mediaStore;
    private final MontageConfig montageConfig;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final ShardTallyCalculator shardTallyCalculator;
    private final CullWatchers cullWatchers;
    private final PrepDirDoctor prepDirDoctor;
    private final PrepDirRemedies prepDirRemedies;
    private final Path cullPrepRoot;

    /**
     * Wires together every collaborator this engine dispatches cull jobs through.
     *
     * @param montageRenderer {@link MontageRenderer} builds montages from a scope
     * @param cullDispatcher {@link CullDispatcher} runs the cull phase
     * @param applyEngine {@link ApplyEngine} runs the apply phase
     * @param cullPrepPort {@link CullPrepPort} reads/writes prep dir index state
     * @param cullSettings {@link CullSettings} configured provider and watch-mode settings
     * @param mediaStore {@link MediaStore} filesystem access for prep dirs
     * @param pathsPort {@link PathsPort} resolves repo-relative paths
     * @param montageConfig {@link MontageConfig} montage grid configuration
     * @param jobRunner {@link JobRunner} runs cull jobs one at a time
     * @param progressPort {@link ProgressPort} reports phase progress
     * @param applyPlanner {@link ApplyPlanner} the gate a watcher's readiness check runs
     * @param ledgerReader {@link LedgerReader} takes the disposition-ledger snapshot that gate honours
     * @param prepDirDoctor {@link PrepDirDoctor} diagnoses whatever already occupies a scope
     * @param prepDirRemedies {@link PrepDirRemedies} archives a completed run out of the way
     * @param watchPollInterval {@link Duration} how often a watcher re-checks its prep dir
     */
    CullEngine(final MontageRenderer montageRenderer, final CullDispatcher cullDispatcher,
               final ApplyEngine applyEngine,
               final CullPrepPort cullPrepPort, final CullSettings cullSettings, final MediaStore mediaStore,
               final PathsPort pathsPort,
               final MontageConfig montageConfig, final JobRunner jobRunner, final ProgressPort progressPort,
               final ApplyPlanner applyPlanner, final LedgerReader ledgerReader,
               final PrepDirDoctor prepDirDoctor, final PrepDirRemedies prepDirRemedies,
               final Duration watchPollInterval) {
        this.montageRenderer = montageRenderer;
        this.cullDispatcher = cullDispatcher;
        this.applyEngine = applyEngine;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.mediaStore = mediaStore;
        this.montageConfig = montageConfig;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.shardTallyCalculator = new ShardTallyCalculator(cullPrepPort, cullSettings, applyPlanner, ledgerReader);
        this.cullWatchers = new CullWatchers(cullSettings, this.shardTallyCalculator, watchPollInterval,
                prepDir -> this.resume(prepDir, false));
        this.prepDirDoctor = prepDirDoctor;
        this.prepDirRemedies = prepDirRemedies;
        this.cullPrepRoot = pathsPort.logs().resolve("cull-prep");
    }

    /**
     * Re-arms a watcher for every resumable run found on disk. There is no persistent job store (see
     * WaitingCullJob's own doc), so restarting the app would otherwise stop watching every run armed
     * before it. A no-op when mode is MANUAL. Callable directly, not just via Pipeline's own
     * {@code @PostConstruct}, so a test can drive it without a Spring context.
     *
     * <p>Resumable means WAITING or READY: the two states a run can leave without a person. WAITING
     * still expects shards, which is what a watcher watches for. READY has them all already, the
     * ordinary shape of a restart where the agent finished while the app was closed.
     *
     * <p>BLOCKED and DAMAGED are left alone. A blocked run would resume and block again on the same
     * findings, spending a job slot at every launch to reach a verdict only the user can change. A
     * damaged run never reads as ready, so its watcher would poll for good.
     *
     * <p>Also a no-op while a job is running. A prep dir mid-job never diagnoses COMPLETE, since
     * decisions.json is written only near the end of a successful apply. So it can still look like
     * something to arm. JobRunner runs one job at a time, so a busy runner means that job is this
     * dir's own, and arming it would leave a phantom watcher for a job about to resolve by itself.
     * Anything genuinely waiting is picked up on the next run once the app is idle. Not the only
     * path that arms a watcher - see dispatchAndApply()'s own note.
     */
    void armWatchesForResumableRuns() {
        if (this.cullSettings.externalAgent().mode() != WatchMode.WATCH || this.jobRunner.isBusy()) {
            return;
        }
        this.prepDirDoctor.runs(this.cullPrepRoot).stream()
                .filter(run -> run.health().state() == State.WAITING || run.health().state() == State.READY)
                .forEach(run -> this.cullWatchers.armWatchIfConfigured(run.prepDir()));
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
     * running. That is CurateEngine's only option for an auto-resolved scope, per its own comment.
     * The archive of a completed occupant happens only there, on the job's own thread. Only that
     * call can put the resulting graveyard path into the outcome.
     *
     * @param scope {@link CullScope} the media scope to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     */
    JobHandle<CullJobOutcome> cull(final CullScope scope) {
        this.refuseIfScopeOccupied(scope);
        return this.jobRunner.submit(handle -> this.buildFreshAndDispatch(scope, handle::isCancellationRequested));
    }

    /**
     * Re-reads an existing prep dir (no montages regenerated) and picks up where the run left off,
     * this time with the caller's own allowPartial. Resume is safely re-runnable. It stays
     * read-only until the shard set actually validates.
     *
     * <p>What it does next depends only on which shards are on disk. A montage still without one
     * means the run is genuinely unfinished, so dispatch runs again. A resume triggered too early
     * then lands right back in Waiting with a freshly recomputed tally. A full shard set means only
     * apply is left, so no culler is entered at all - see dispatchAndApply()'s own doc.
     *
     * @param prepDir {@link Path} the existing prep dir to resume
     * @param allowPartial boolean whether a partial shard set is acceptable
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     */
    JobHandle<CullJobOutcome> resume(final Path prepDir, final boolean allowPartial) {
        return this.jobRunner.submit(handle ->
                this.dispatchAndApply(this.cullPrepPort.readIndex(prepDir), allowPartial,
                        handle::isCancellationRequested, null));
    }

    /**
     * Delegates to {@link CullWatchers}. Test seam: whether a watcher is currently polling prepDir.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean whether a watcher is currently active for it
     */
    boolean isWatchActive(final Path prepDir) {
        return this.cullWatchers.isWatchActive(prepDir);
    }

    /**
     * Delegates to {@link CullWatchers#armWatch}. {@link Pipeline#startWatching}'s own route in.
     *
     * @param prepDir {@link Path} the prep dir to watch
     */
    void armWatch(final Path prepDir) {
        this.cullWatchers.armWatch(prepDir);
    }

    /**
     * Delegates to {@link CullWatchers#disarmWatch}. {@link Pipeline#stopWatching} and
     * {@link Pipeline#discard} both route in here.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     */
    void disarmWatch(final Path prepDir) {
        this.cullWatchers.disarmWatch(prepDir);
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
     * Frees scope's prep dir for a fresh run, and reports where a completed occupant was filed.
     *
     * <p>A COMPLETE run is archived into the graveyard rather than overwritten, and no confirmation
     * is asked. Curate resolves its own scope mid-job, so no dialog could fire there anyway, and a
     * monthly curate would pay that toll every month. Nothing is destroyed: the archive keeps the
     * same 30-day recovery window every other graveyard entry gets.
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
        // Disarmed before the graveyard move starts, never after. A watcher left polling survives
        // the archive and then finds the fresh run at that same path. It would be watching a
        // different run than the one it was armed for. Only a manual startWatching() on an
        // already-COMPLETE run leaves one armed here, since dispatchAndApply() disarms on entry.
        this.disarmWatch(run.prepDir());
        return this.prepDirRemedies.discard(run.prepDir()).graveyard();
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
        final Path prepDir = this.cullPrepRoot.resolve(CullScope.tag(scope));
        return switch (this.occupancyOf(prepDir)) {
            case final Occupancy.Empty ignored -> Optional.empty();
            case final Occupancy.Occupied ignored -> Optional.of(this.prepDirDoctor.summaryOf(prepDir));
            case Occupancy.Unreadable(final RuntimeException cause) ->
                    throw new Pipeline.ScopeUnreadableException(prepDir, cause);
        };
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
     * Builds scope's prep dir fresh, then dispatches and applies it. Shared by cull() and
     * CurateEngine's cull stage; resume() re-enters at dispatchAndApply() directly instead, since it
     * must never rebuild an existing prep dir. See cull()'s own doc for why the occupancy question
     * is asked here too, not only at its synchronous pre-submit call site.
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
                progress -> Optional.ofNullable(this.montageRenderer.build(scope, this.montageConfig, progress,
                        cancellation)));
        // Empty means the renderer itself stopped mid-render, clearing whatever it had written and
        // leaving no prep dir at all - nothing resumable exists. The renderer is the completion
        // authority here: this branches purely on its return value, never on re-checking disk state.
        if (prep.isEmpty()) {
            return new CullJobOutcome.Cancelled(archivedPriorRun);
        }
        // Prep just finished and wrote index.json, so a cancellation seen right here resolves
        // cleanly to Waiting too - a 0/N tally, nothing dispatched yet. No watcher is armed: an
        // auto-resume moments after a cancel would defy it.
        if (cancellation.isCancelled()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep.get()), archivedPriorRun);
        }
        return this.dispatchAndApply(prep.get(), false, cancellation, archivedPriorRun);
    }

    /**
     * Dispatch runs only while some montage still lacks a shard. Once every montage has one, the
     * agent has said everything it is going to say. Re-asking buys the same answer back, at the
     * cost of a fresh round of API calls or a redundant validation pass. So a resume with a full
     * shard set goes straight to apply, and apply's own gate becomes the single validator.
     *
     * <p>A refused apply therefore resolves to Blocked rather than propagating. Blocked is the
     * user's move: the shard set is complete, so nothing is left to wait for. No watcher is armed
     * for it, and the disarmWatch() below has already retired any that was polling.
     *
     * <p>A CullException from the dispatch step means different things depending on the configured
     * provider - see VisionCuller.MANUAL_MODE_PROVIDER_ID's own doc. For that provider it's the
     * expected manual-mode pause: resolved into Waiting, run slot released. For any other (automated)
     * provider it's a genuine failure and propagates - it never throws CullException to signal a
     * cancellation. An automated provider's cull() still lands in Waiting on cancellation, but via
     * the cancellation.isCancelled() check further down, after dispatch returns normally rather than
     * through this catch block.
     *
     * <p>disarmWatch() runs unconditionally up front, regardless of whether this call landed here from
     * cull(), a user's manual resume(), or a watcher's own auto-resume. Whatever watcher was polling
     * this prep dir is retired the moment any resume attempt actually runs. That means a manual
     * click racing an armed watcher can never leave two pollers running for the same job. A fresh
     * watcher gets (re-)armed below only if the outcome is Waiting again.
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
        if (this.everyMontageHasAShard(prep)) {
            cullReport = new CullReport(0, prep.entries().size(), 0, 0);
        } else {
            try {
                cullReport = this.phaseRunner.run(CULLING,
                        progress -> this.cullDispatcher.cull(prep, new CullOptions(allowPartial, null), progress,
                                cancellation));
            } catch (final CullException e) {
                if (!this.cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
                    throw e;
                }
                // The exception names which montages are still missing a shard. Nothing downstream
                // reads that list - the outcome carries a tally, not montage names - so this is the
                // only place it can be seen at all. A watch mode that never converges is diagnosed
                // from here.
                log.info("Cull for {} is waiting on shards: {}", prep.scope(), e.getMessage());
                final WaitingCullJob job = this.buildWaitingJob(prep);
                // Not armed when this CullException is itself the manual-mode pause racing a
                // cancellation: an auto-resume moments after a cancel would defy it. A plain manual
                // pause (no cancellation involved) still arms as before.
                if (!cancellation.isCancelled()) {
                    this.cullWatchers.armWatchIfConfigured(job.prepDir());
                }
                return new CullJobOutcome.Waiting(job, archivedPriorRun);
            }
        }
        // The one boundary check this method has for an automated provider: dispatch just
        // returned, either because it finished or because it stopped early on a cancellation. This
        // doubles as the pre-APPLYING check. It also covers the external-agent flow: its dispatch
        // never sees a cancellation mid-call, but a cancellation requested right after it still
        // lands here before apply moves anything.
        if (cancellation.isCancelled()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep), archivedPriorRun);
        }
        final Optional<ApplyReport> applyReport;
        try {
            applyReport = this.phaseRunner.run(APPLYING,
                    progress -> Optional.ofNullable(
                            this.applyEngine.apply(prep.prepDir(), new ApplyOptions(allowPartial), progress,
                                    cancellation)));
        } catch (final ApplyException e) {
            return new CullJobOutcome.Blocked(this.buildWaitingJob(prep), e.findings(), archivedPriorRun);
        }
        // Empty means apply() itself stopped mid-loop and skipped its finalizers, so
        // decisions.json was never written. The prep dir still reads as a waiting job, the same
        // authority rule the renderer's own empty return follows above. No watcher is armed here
        // either, for the same reason the pre-APPLYING check above doesn't: an auto-resume
        // moments after a cancel would defy it.
        if (applyReport.isEmpty()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep), archivedPriorRun);
        }
        return new CullJobOutcome.Applied(cullReport, applyReport.get(), archivedPriorRun);
    }

    /**
     * Whether every montage in prep already has a shard file on disk. A plain existence check per
     * montage, never a parse. Whether those shards are any good is apply's own gate to decide. A
     * shard that turns out unreadable is a finding there, not a reason to re-dispatch.
     *
     * <p>A scope with no montages at all counts as fully sharded. There is nothing for a culler to
     * judge, so dispatching would only produce an empty report.
     *
     * @param prep {@link PrepDir} the prep dir to check
     * @return boolean true if no montage is missing its shard
     */
    private boolean everyMontageHasAShard(final PrepDir prep) {
        return prep.entries().stream().allMatch(montage -> this.cullPrepPort.hasShard(prep.prepDir(), montage));
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
                this.mediaStore.lastModifiedTime(prep.prepDir()));
    }
}
