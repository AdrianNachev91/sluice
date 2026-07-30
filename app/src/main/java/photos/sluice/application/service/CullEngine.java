package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;
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
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a whole cull job: prep, then dispatch, then apply (see
 * {@link #buildFreshAndDispatch}). It also owns {@link #waitingJobs()} and {@link #resume} for a
 * job still sitting on shards, and the watch-mode auto-resume that polls for those shards to
 * land.
 *
 * <p>Not a Spring bean. {@link Pipeline} builds the one instance it needs, the same way it builds
 * a {@link CullWatcher} per prep dir. {@link #checkNoWaitingJobFor} and
 * {@link #buildFreshAndDispatch} stay package-private rather than private, since
 * {@link CurateEngine}'s own cull stage reuses both directly instead of duplicating them.
 */
final class CullEngine {

    private static final Logger log = LoggerFactory.getLogger(CullEngine.class);

    private static final String PREPPING = "Building montages...";
    private static final String CULLING = "Culling...";
    private static final String APPLYING = "Applying decisions...";
    private static final String DECISIONS_FILE = "decisions.json";
    private static final String INDEX_FILE = "index.json";

    private final MontageRenderer montageRenderer;
    private final CullDispatcher cullDispatcher;
    private final ApplyEngine applyEngine;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final MediaStore mediaStore;
    private final PathsPort pathsPort;
    private final MontageConfig montageConfig;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final ShardTallyCalculator shardTallyCalculator;
    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode/watchTimeout, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. Pipeline's own package-private
    // constructor overload is what lets a test override this.
    private final Duration watchPollInterval;
    private final Map<Path, CullWatcher> activeWatches = new ConcurrentHashMap<>();

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
     * @param watchPollInterval {@link Duration} how often a watcher re-checks its prep dir
     */
    CullEngine(final MontageRenderer montageRenderer, final CullDispatcher cullDispatcher,
               final ApplyEngine applyEngine,
               final CullPrepPort cullPrepPort, final CullSettings cullSettings, final MediaStore mediaStore,
               final PathsPort pathsPort,
               final MontageConfig montageConfig, final JobRunner jobRunner, final ProgressPort progressPort,
               final Duration watchPollInterval) {
        this.montageRenderer = montageRenderer;
        this.cullDispatcher = cullDispatcher;
        this.applyEngine = applyEngine;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.mediaStore = mediaStore;
        this.pathsPort = pathsPort;
        this.montageConfig = montageConfig;
        this.jobRunner = jobRunner;
        this.phaseRunner = new PhaseRunner(progressPort);
        this.shardTallyCalculator = new ShardTallyCalculator(cullPrepPort, cullSettings);
        this.watchPollInterval = watchPollInterval;
    }

    /**
     * Re-arms a watcher for every still-waiting job found on disk, so watch mode survives an app
     * restart the same way WAITING_FOR_SHARDS itself does. There is no persistent job store - see
     * WaitingCullJob's own doc. Without this, restarting the app would silently stop watching every
     * job that was armed before the restart. A no-op when mode is MANUAL. Callable directly (not
     * just via Pipeline's own @PostConstruct) so a test can drive it without a Spring context.
     *
     * <p>Also a no-op while a job is currently running. waitingJobs() counts a prep dir as waiting the
     * moment index.json exists and decisions.json doesn't yet. That's also true of a prep dir
     * mid-CULLING/mid-APPLYING right now - dispatchAndApply() only writes decisions.json near the
     * very end of a successful apply. JobRunner only ever runs one job at a time, so a busy runner
     * could only mean the one job in flight is that prep dir's own. Arming here anyway would risk a
     * phantom watcher for a job about to resolve to Applied on its own, with nothing left to disarm
     * it afterward. Any prep dir that's genuinely still waiting gets picked up the next time this
     * runs once the app is idle again. This is also not the only path that arms a watcher - see
     * dispatchAndApply()'s own note.
     */
    void armWatchesForExistingWaitingJobs() {
        if (this.cullSettings.externalAgent().mode() != WatchMode.WATCH || this.jobRunner.isBusy()) {
            return;
        }
        this.waitingJobs().forEach(this::armWatchIfConfigured);
    }

    /**
     * Prep always runs fresh: a scope's montages are rebuilt from Sorted every call.
     * MontageRenderer.build() clears whatever a stale prior run left in the same prep dir first.
     * That would silently destroy any shards already dropped for a still-unresolved WaitingCullJob
     * on the same scope. checkNoWaitingJobFor() guards against that and fails loud instead - resume
     * or resolve it first.
     *
     * <p>Checked here too, synchronously before submit(), for the earliest possible fail-fast.
     * buildFreshAndDispatch() below checks the same thing again once actually running - that's
     * CurateEngine's only option for an auto-resolved scope; see its own comment.
     *
     * @param scope {@link CullScope} the media scope to cull
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     */
    JobHandle<CullJobOutcome> cull(final CullScope scope) {
        this.checkNoWaitingJobFor(scope);
        return this.jobRunner.submit(handle -> this.buildFreshAndDispatch(scope, handle::isCancellationRequested));
    }

    /**
     * Re-reads an existing prep dir (no montages regenerated) and re-runs the same dispatch-then-apply
     * flow cull() used, this time with the caller's own allowPartial. Resume is safely re-runnable.
     * It stays read-only until the shard set actually validates. A resume triggered before every
     * shard is dropped just throws right back into Waiting with a freshly recomputed tally.
     *
     * @param prepDir {@link Path} the existing prep dir to resume
     * @param allowPartial boolean whether a partial shard set is acceptable
     * @return a {@link JobHandle} of {@link CullJobOutcome} a handle to the running or waiting cull job
     */
    JobHandle<CullJobOutcome> resume(final Path prepDir, final boolean allowPartial) {
        return this.jobRunner.submit(handle ->
                this.dispatchAndApply(this.cullPrepPort.readIndex(prepDir), allowPartial,
                        handle::isCancellationRequested));
    }

    /**
     * Every cull still waiting on shards, derived live off disk rather than a persisted list (see
     * WaitingCullJob's own doc). A prep dir counts as waiting when it has index.json (prep ran) but no
     * decisions.json yet (apply never completed). Not routed through JobRunner - this only reads, so
     * it doesn't compete for the single job slot. A prep dir whose index.json is transiently
     * unreadable (mid-write by a concurrent cull job) is skipped rather than failing the whole scan.
     * That's the same tolerance the external-agent design already gives a shard mid-write.
     *
     * @return a {@link List} of {@link WaitingCullJob} every cull job still waiting on shards
     */
    List<WaitingCullJob> waitingJobs() {
        final Path cullPrepRoot = this.pathsPort.logs().resolve("cull-prep");
        if (!this.mediaStore.exists(cullPrepRoot)) {
            return List.of();
        }
        return this.mediaStore.listFiles(cullPrepRoot).stream()
                .filter(file -> file.getFileName().toString().equals(INDEX_FILE))
                .map(Path::getParent)
                .filter(prepDir -> !this.mediaStore.exists(prepDir.resolve(DECISIONS_FILE)))
                .<WaitingCullJob>mapMulti((prepDir, consumer) -> this.readWaitingJob(prepDir).ifPresent(consumer))
                .toList();
    }

    /**
     * Test seam: whether a watcher is currently polling prepDir. Lets a test prove disarmWatch()'s
     * own claim - that any dispatchAndApply() call retires an existing watcher, not just the
     * watcher's own auto-resume trigger. No need to reach into the private activeWatches map.
     *
     * @param prepDir {@link Path} the prep dir to check
     * @return boolean whether a watcher is currently active for it
     */
    boolean isWatchActive(final Path prepDir) {
        final CullWatcher watcher = this.activeWatches.get(prepDir);
        return watcher != null && watcher.isActive();
    }

    /**
     * Throws if a cull job for scope is already waiting on shards.
     *
     * @param scope {@link CullScope} the scope to check
     */
    void checkNoWaitingJobFor(final CullScope scope) {
        final String tag = CullScope.tag(scope);
        this.waitingJobs().stream().filter(job -> job.scope().equals(tag)).findFirst().ifPresent(existing -> {
            throw new IllegalStateException("A cull for scope '" + tag + "' is already waiting on shards at "
                    + existing.prepDir() + " - resume or resolve it before starting a new cull for the same scope.");
        });
    }

    /**
     * Builds scope's prep dir fresh, then dispatches and applies it. Shared by cull() and
     * CurateEngine's cull stage; resume() re-enters at dispatchAndApply() directly instead, since it
     * must never rebuild an existing prep dir. See checkNoWaitingJobFor()'s own doc for why this
     * check runs here too, not only at cull()'s synchronous pre-submit call site.
     *
     * @param scope {@link CullScope} the media scope to cull
     * @param cancellation {@link CancellationSignal} signals whether cancellation has been requested
     * @return {@link CullJobOutcome} the outcome of this cull attempt
     */
    CullJobOutcome buildFreshAndDispatch(final CullScope scope, final CancellationSignal cancellation) throws Exception {
        this.checkNoWaitingJobFor(scope);
        // phaseRunner.run/PhaseWork are shared with sort/commit/rescue, which always return non-null -
        // keeping T itself non-null there avoids leaking a spurious "might be null" possibility
        // into those callers. Wrapping the result in Optional here instead keeps that shared
        // contract clean while still letting this call site express a real null case.
        final Optional<PrepDir> prep = this.phaseRunner.run(PREPPING,
                progress -> Optional.ofNullable(this.montageRenderer.build(scope, this.montageConfig, progress,
                        cancellation)));
        // Empty means the renderer itself stopped mid-render, before index.json was ever written -
        // nothing resumable exists yet. The renderer is the completion authority here: this
        // branches purely on its return value, never on re-checking disk state.
        if (prep.isEmpty()) {
            return new CullJobOutcome.Cancelled();
        }
        // Prep just finished and wrote index.json, so a cancellation seen right here resolves
        // cleanly to Waiting too - a 0/N tally, nothing dispatched yet. No watcher is armed: an
        // auto-resume moments after a cancel would defy it.
        if (cancellation.isCancelled()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep.get()));
        }
        return this.dispatchAndApply(prep.get(), false, cancellation);
    }

    /**
     * Reads prepDir's index and builds the waiting job it represents, if readable.
     *
     * @param prepDir {@link Path} the prep dir to read
     * @return an {@link Optional} {@link WaitingCullJob} the waiting job, or empty if the index is unreadable
     */
    private Optional<WaitingCullJob> readWaitingJob(final Path prepDir) {
        try {
            return Optional.of(this.buildWaitingJob(this.cullPrepPort.readIndex(prepDir)));
        } catch (final UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /**
     * A CullException from the dispatch step means different things depending on the configured
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
     * @return {@link CullJobOutcome} the outcome of this dispatch-and-apply attempt
     */
    private CullJobOutcome dispatchAndApply(final PrepDir prep, final boolean allowPartial,
                                            final CancellationSignal cancellation)
            throws Exception {
        this.disarmWatch(prep.prepDir());
        final CullReport cullReport;
        try {
            cullReport = this.phaseRunner.run(CULLING,
                    progress -> this.cullDispatcher.cull(prep, new CullOptions(allowPartial, null), progress,
                            cancellation));
        } catch (final CullException e) {
            if (!this.cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
                throw e;
            }
            final WaitingCullJob job = this.buildWaitingJob(prep);
            // Not armed when this CullException is itself the manual-mode pause racing a
            // cancellation: an auto-resume moments after a cancel would defy it. A plain manual
            // pause (no cancellation involved) still arms as before.
            if (!cancellation.isCancelled()) {
                this.armWatchIfConfigured(job);
            }
            return new CullJobOutcome.Waiting(job);
        }
        // The one boundary check this method has for an automated provider: dispatch just
        // returned, either because it finished or because it stopped early on a cancellation. This
        // doubles as the pre-APPLYING check. It also covers the external-agent flow: its dispatch
        // never sees a cancellation mid-call, but a cancellation requested right after it still
        // lands here before apply moves anything.
        if (cancellation.isCancelled()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep));
        }
        final Optional<ApplyReport> applyReport = this.phaseRunner.run(APPLYING,
                progress -> Optional.ofNullable(
                        this.applyEngine.apply(prep.prepDir(), new ApplyOptions(allowPartial), progress,
                                cancellation)));
        // Empty means apply() itself stopped mid-loop and skipped its finalizers, so
        // decisions.json was never written. The prep dir still reads as a waiting job, the same
        // authority rule the renderer's own empty return follows above. No watcher is armed here
        // either, for the same reason the pre-APPLYING check above doesn't: an auto-resume
        // moments after a cancel would defy it.
        if (applyReport.isEmpty()) {
            return new CullJobOutcome.Waiting(this.buildWaitingJob(prep));
        }
        return new CullJobOutcome.Applied(cullReport, applyReport.get());
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

    /**
     * Starts polling job's prep dir for an auto-resume, unless mode is MANUAL, the configured
     * provider isn't the external-agent one, or a watcher is already active for it.
     * armWatchesForExistingWaitingJobs() and dispatchAndApply()'s own Waiting branch can both reach
     * here for the same prep dir. The second call is then a no-op rather than a competing second
     * poller.
     *
     * <p>Watch mode is an external-agent feature: it exists to notice when the user's own separate
     * culling agent, running outside this app, drops a shard. The provider check mainly guards
     * armWatchesForExistingWaitingJobs()'s startup scan, which walks every waiting job on disk
     * regardless of which provider produced it. dispatchAndApply()'s own call site can only reach
     * this method when the provider already matches, so the check is redundant there, but harmless.
     * Without the guard, a leftover external-agent.mode=watch setting combined with
     * provider=anthropic would arm a phantom watcher for an automated provider's own interrupted
     * (cancelled) prep dir. A fully-valid tally there would then trigger an unasked-for,
     * API-spending auto-resume the user never opted into.
     *
     * @param job {@link WaitingCullJob} the waiting job to watch
     */
    private void armWatchIfConfigured(final WaitingCullJob job) {
        if (this.cullSettings.externalAgent().mode() != WatchMode.WATCH
                || !this.cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
            return;
        }
        final Path prepDir = job.prepDir();
        this.activeWatches.compute(prepDir, (_, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            // Instant.now() here, not job.since() (the prep dir's own mtime). watchTimeout is
            // deliberately "how long this watcher keeps polling in one continuous streak," not
            // "total time since the job first started waiting." A re-arm gets its own full timeout
            // window instead of inheriting a countdown already run down by an earlier streak. A
            // fresh app restart or a shard that turned invalid after looking ready are both re-arms.
            final var watcher = new CullWatcher(this.watchPollInterval,
                    this.cullSettings.externalAgent().watchTimeout(),
                    () -> this.shardTallyCalculator.isFullyValid(prepDir), () -> this.tryAutoResume(prepDir),
                    Instant.now());
            watcher.start();
            return watcher;
        });
    }

    /**
     * Stops and removes the active watcher for a prep dir, if one exists. Giving up on a
     * still-waiting job must stop it from ever auto-resuming a prep dir that's about to be filed
     * into the graveyard, so this is package-private rather than private: dispatchAndApply()'s own
     * call site isn't the only place that needs to retire a watcher.
     *
     * @param prepDir {@link Path} the prep dir whose watcher should stop
     */
    void disarmWatch(final Path prepDir) {
        final CullWatcher watcher = this.activeWatches.remove(prepDir);
        if (watcher != null) {
            watcher.stop();
        }
    }

    /**
     * The heavier action a CullWatcher runs at most once it thinks isFullyValid(). Returns whether
     * it actually got to run. True means resume()'s own jobRunner.submit() succeeded - the watcher's
     * job is then done, win or lose (see dispatchAndApply()'s own re-arm-on-Waiting note). False
     * means the job runner was busy with something else, so the watcher keeps polling and retries.
     * The submitted job runs and completes fully asynchronously; nothing here waits on it. A
     * failure there would otherwise vanish silently, so it's logged here instead. That matches the
     * visibility a manual Resume gets for free from whatever UI/CLI surfaces its own
     * join()/onComplete() failure.
     *
     * @param prepDir {@link Path} the prep dir to attempt to resume
     * @return boolean whether the resume attempt was actually submitted
     */
    private boolean tryAutoResume(final Path prepDir) {
        final JobHandle<CullJobOutcome> handle;
        try {
            handle = this.resume(prepDir, false);
        } catch (final IllegalStateException busy) {
            return false;
        }
        handle.onComplete().whenComplete((_, failure) -> {
            if (failure != null) {
                log.warn("Watch-mode auto-resume for {} failed", prepDir, failure);
            }
        });
        return true;
    }
}
