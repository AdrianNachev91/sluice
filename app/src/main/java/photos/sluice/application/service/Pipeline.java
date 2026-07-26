package photos.sluice.application.service;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
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
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

// Wires the mechanical engines through JobRunner so a driving adapter (the JavaFX UI, a future
// CLI) gets a JobHandle back instead of blocking. Progress is bracketed through ProgressPort
// around each engine call. Depends on the engines' concrete classes rather than their
// SortUseCase/CommitUseCase/RescueUseCase port/in interfaces. The progress-callback overloads
// live only on the concrete types, not on those narrower interfaces.
@Component
public class Pipeline {

    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);

    private static final String SORTING = "Sorting...";
    private static final String COMMITTING = "Committing...";
    private static final String RESCUING = "Rescuing...";
    private static final String PREPPING = "Building montages...";
    private static final String CULLING = "Culling...";
    private static final String APPLYING = "Applying decisions...";
    private static final String DECISIONS_FILE = "decisions.json";
    private static final String INDEX_FILE = "index.json";

    // How often a watch-mode job re-checks its prep dir's shard tally. Not part of CullSettings -
    // unlike mode/watchTimeout, this cadence isn't a documented user-facing knob, just an internal
    // responsiveness/overhead tradeoff. Short enough that a human dropping files never perceives the
    // delay; long enough not to hammer disk or spam re-validation. See the package-private
    // constructor overload for how tests override it.
    private static final Duration DEFAULT_WATCH_POLL_INTERVAL = Duration.ofSeconds(2);

    private final SortEngine sortEngine;
    private final CommitEngine commitEngine;
    private final RescueEngine rescueEngine;
    private final MontageRenderer montageRenderer;
    private final CullDispatcher cullDispatcher;
    private final ApplyEngine applyEngine;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final MediaStore mediaStore;
    private final PathsPort pathsPort;
    private final MontageConfig montageConfig;
    private final JobRunner jobRunner;
    private final ProgressPort progressPort;
    private final Duration watchPollInterval;
    private final ShardValidator shardValidator = new ShardValidator();
    private final Map<Path, CullWatcher> activeWatches = new ConcurrentHashMap<>();

    // Explicit @Autowired: Spring's implicit single-constructor injection only kicks in when a
    // class has exactly one constructor. The package-private test-seam overload below means there
    // are two, so this one has to be named as the one Spring should use.
    @Autowired
    public Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            MontageRenderer montageRenderer, CullDispatcher cullDispatcher, ApplyEngine applyEngine,
            CullPrepPort cullPrepPort, CullSettings cullSettings, MediaStore mediaStore, PathsPort pathsPort,
            MontageConfig montageConfig, JobRunner jobRunner, ProgressPort progressPort) {
        this(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine, cullPrepPort,
                cullSettings, mediaStore, pathsPort, montageConfig, jobRunner, progressPort,
                DEFAULT_WATCH_POLL_INTERVAL);
    }

    // Test seam: production wiring always goes through the public constructor above, which fixes
    // the poll cadence at DEFAULT_WATCH_POLL_INTERVAL. Tests exercising real watch-mode timing pass
    // a much shorter interval here so the behavior proves out in milliseconds, not seconds, without
    // resorting to a mock clock.
    Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            MontageRenderer montageRenderer, CullDispatcher cullDispatcher, ApplyEngine applyEngine,
            CullPrepPort cullPrepPort, CullSettings cullSettings, MediaStore mediaStore, PathsPort pathsPort,
            MontageConfig montageConfig, JobRunner jobRunner, ProgressPort progressPort, Duration watchPollInterval) {
        this.sortEngine = sortEngine;
        this.commitEngine = commitEngine;
        this.rescueEngine = rescueEngine;
        this.montageRenderer = montageRenderer;
        this.cullDispatcher = cullDispatcher;
        this.applyEngine = applyEngine;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.mediaStore = mediaStore;
        this.pathsPort = pathsPort;
        this.montageConfig = montageConfig;
        this.jobRunner = jobRunner;
        this.progressPort = progressPort;
        this.watchPollInterval = watchPollInterval;
    }

    // Re-arms a watcher for every still-waiting job found on disk, so watch mode survives an app
    // restart the same way WAITING_FOR_SHARDS itself does. There is no persistent job store - see
    // WaitingCullJob's own doc. Without this, restarting the app would silently stop watching every
    // job that was armed before the restart. A no-op when mode is MANUAL. Public and callable
    // directly (not just via @PostConstruct) so a test can drive it without a Spring context.
    //
    // Also a no-op while a job is currently running. waitingJobs() counts a prep dir as waiting the
    // moment index.json exists and decisions.json doesn't yet. That's also true of a prep dir
    // mid-CULLING/mid-APPLYING right now - dispatchAndApply() only writes decisions.json near the
    // very end of a successful apply. JobRunner only ever runs one job at a time, so a busy runner
    // could only mean the one job in flight is that prep dir's own. Arming here anyway would risk a
    // phantom watcher for a job about to resolve to Applied on its own, with nothing left to disarm
    // it afterward. Any prep dir that's genuinely still waiting gets picked up the next time this
    // runs once the app is idle again. This is also not the only path that arms a watcher - see
    // dispatchAndApply()'s own note.
    @PostConstruct
    public void armWatchesForExistingWaitingJobs() {
        if (cullSettings.externalAgent().mode() != WatchMode.WATCH || jobRunner.isBusy()) {
            return;
        }
        waitingJobs().forEach(this::armWatchIfConfigured);
    }

    public JobHandle<SortSummary> sort(SortScope scope) {
        return jobRunner.submit(handle -> runPhase(SORTING,
                progress -> sortEngine.sort(scope, progress, handle::isCancellationRequested)));
    }

    public JobHandle<CommitSummary> commit(CommitScope scope) {
        return jobRunner.submit(handle -> runPhase(COMMITTING,
                progress -> commitEngine.commit(scope, progress, handle::isCancellationRequested)));
    }

    public JobHandle<RescueSummary> rescue(String reviewFolder) {
        return jobRunner.submit(handle -> runPhase(RESCUING,
                progress -> rescueEngine.rescue(reviewFolder, progress, handle::isCancellationRequested)));
    }

    // Prep always runs fresh: a scope's montages are rebuilt from Sorted every call.
    // MontageRenderer.build() clears whatever a stale prior run left in the same prep dir first.
    // That would silently destroy any shards already dropped for a still-unresolved WaitingCullJob
    // on the same scope. checkNoWaitingJobFor() guards against that and fails loud instead - resume
    // or resolve it first.
    //
    // Checked here too, synchronously before submit(), for the earliest possible fail-fast.
    // buildFreshAndDispatch() below checks the same thing again once actually running - that's
    // curate()'s only option for an auto-resolved scope; see its own comment.
    public JobHandle<CullJobOutcome> cull(CullScope scope) {
        checkNoWaitingJobFor(scope);
        return jobRunner.submit(handle -> buildFreshAndDispatch(scope, handle::isCancellationRequested));
    }

    // Sort scope, then cull whatever that sort just populated, as one job. Sequential Java calls
    // inside this one JobWork - never two chained submit() calls (JobHandle's own doc explains why
    // no job depends on another's future).
    //
    // The target CullScope mirrors scope directly wherever that's knowable up front: an explicit
    // Year maps straight across, and OldestN carries the same n through to CullScope.OldestN. Sort
    // itself is never narrowed to fit cull's shape - it always runs its own normal, complete job.
    //
    // An OldestN sort can still land files across more than one year. Cull's own OldestN ordering
    // is by raw mtime, not resolved date (see CullScope's own doc) - exactly what a standalone
    // cull() call already does with that scope. Nothing new here.
    //
    // Only OldestYear can't be mapped ahead of time - its year isn't decided until the sort itself
    // resolves it. knownCullScope() returns null for it; the real mapping happens after the sort
    // runs, from SortSummary.yearsSorted().
    //
    // A known target CullScope gets the same synchronous, pre-submit checkNoWaitingJobFor()
    // cull() gets - failing before the sort even starts. OldestYear can't be checked that early.
    // Its only guard is the same check running again once its year is resolved, after the sort has
    // already moved real files. That failure can't be a plain IllegalStateException like the
    // pre-submit one is - the caller would lose the SortSummary describing what already moved. See
    // CurateConflictException's own doc for how that's carried forward instead.
    //
    // isCancellationRequested() is checked here at the sort/cull boundary. It's also checked
    // inside SortEngine's own dating and routing passes via its CancellationSignal overload.
    // The cull stage's own render/dispatch/apply passes check it too, via buildFreshAndDispatch()'s
    // and dispatchAndApply()'s own checks. A large sort or cull responds promptly throughout, not
    // only at this one stage boundary.
    public JobHandle<CurateOutcome> curate(SortScope scope) {
        CullScope known = knownCullScope(scope);
        if (known != null) {
            checkNoWaitingJobFor(known);
        }
        return jobRunner.submit(handle -> {
            SortSummary sortSummary = runPhase(SORTING,
                    progress -> sortEngine.sort(scope, progress, handle::isCancellationRequested));
            if (handle.isCancellationRequested()) {
                return new CurateOutcome(sortSummary, null);
            }
            CullScope cullScope = known != null ? known : oldestYearCullScope(sortSummary);
            if (cullScope == null) {
                return new CurateOutcome(sortSummary, null);
            }
            if (known == null) {
                // Only OldestYear reaches here without having already passed this same check
                // synchronously before the sort ran - the one case that can't be checked that
                // early. Wrapped narrowly around just this call, not the dispatch/apply that
                // follows. That way a genuine cull failure downstream (a misconfigured provider,
                // for example) is never mislabeled as this conflict.
                try {
                    checkNoWaitingJobFor(cullScope);
                } catch (IllegalStateException conflict) {
                    // The sort has already moved real files by this point. CurateConflictException
                    // carries the SortSummary forward so the caller isn't left blind about what
                    // already happened.
                    throw new CurateConflictException(conflict.getMessage(), sortSummary);
                }
            }
            return new CurateOutcome(sortSummary,
                    buildFreshAndDispatch(cullScope, handle::isCancellationRequested));
        });
    }

    // Thrown by curate() instead of a plain IllegalStateException when an auto-resolved OldestYear
    // scope's checkNoWaitingJobFor() conflict surfaces after its sort has already moved real files.
    // Every other checkNoWaitingJobFor() failure happens before anything runs. Only this one needs
    // to carry a partial result forward - sortSummary() is what the sort stage already produced.
    public static final class CurateConflictException extends IllegalStateException {
        private final transient SortSummary sortSummary;

        CurateConflictException(String message, SortSummary sortSummary) {
            super(message);
            this.sortSummary = sortSummary;
        }

        public SortSummary sortSummary() {
            return sortSummary;
        }
    }

    // The CullScope scope maps to before the sort ever runs. Null only for OldestYear, whose year
    // isn't decided until the sort itself resolves it.
    private static @Nullable CullScope knownCullScope(SortScope scope) {
        return switch (scope) {
            case SortScope.Year(int year, MonthRange months) -> new CullScope.Year(year, monthsFromRange(months));
            case SortScope.OldestN(int n) -> new CullScope.OldestN(n);
            case SortScope.OldestYear() -> null;
        };
    }

    // sortSummary.yearsSorted() is the only place an OldestYear scope's resolved year is ever
    // reported. Guaranteed to hold at most one element (see its own doc), so any element found is
    // "the" year. Empty means nothing reached Sorted this run, so there is nothing left to cull.
    private static @Nullable CullScope oldestYearCullScope(SortSummary sortSummary) {
        return sortSummary.yearsSorted().stream().findAny()
                .<CullScope>map(year -> new CullScope.Year(year, null))
                .orElse(null);
    }

    private static @Nullable List<Integer> monthsFromRange(@Nullable MonthRange months) {
        return months == null ? null : IntStream.rangeClosed(months.from(), months.to()).boxed().toList();
    }

    // Builds scope's prep dir fresh, then dispatches and applies it. Shared by cull() and curate()'s
    // cull stage; resume() re-enters at dispatchAndApply() directly instead, since it must never
    // rebuild an existing prep dir. See checkNoWaitingJobFor()'s own doc for why this check runs
    // here too, not only at cull()'s synchronous pre-submit call site.
    private CullJobOutcome buildFreshAndDispatch(CullScope scope, CancellationSignal cancellation) throws Exception {
        checkNoWaitingJobFor(scope);
        // runPhase/PhaseWork are shared with sort/commit/rescue, which always return non-null -
        // keeping T itself non-null there avoids leaking a spurious "might be null" possibility
        // into those callers. Wrapping the result in Optional here instead keeps that shared
        // contract clean while still letting this call site express a real null case.
        Optional<PrepDir> prep = runPhase(PREPPING,
                progress -> Optional.ofNullable(montageRenderer.build(scope, montageConfig, progress, cancellation)));
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
            return new CullJobOutcome.Waiting(buildWaitingJob(prep.get()));
        }
        return dispatchAndApply(prep.get(), false, cancellation);
    }

    private void checkNoWaitingJobFor(CullScope scope) {
        String tag = CullScope.tag(scope);
        waitingJobs().stream().filter(job -> job.scope().equals(tag)).findFirst().ifPresent(existing -> {
            throw new IllegalStateException("A cull for scope '" + tag + "' is already waiting on shards at "
                    + existing.prepDir() + " - resume or resolve it before starting a new cull for the same scope.");
        });
    }

    // Every cull still waiting on shards, derived live off disk rather than a persisted list (see
    // WaitingCullJob's own doc). A prep dir counts as waiting when it has index.json (prep ran) but no
    // decisions.json yet (apply never completed). Not routed through JobRunner - this only reads, so
    // it doesn't compete for the single job slot. A prep dir whose index.json is transiently
    // unreadable (mid-write by a concurrent cull job) is skipped rather than failing the whole scan.
    // That's the same tolerance the external-agent design already gives a shard mid-write.
    public List<WaitingCullJob> waitingJobs() {
        Path cullPrepRoot = pathsPort.logs().resolve("cull-prep");
        if (!mediaStore.exists(cullPrepRoot)) {
            return List.of();
        }
        return mediaStore.listFiles(cullPrepRoot).stream()
                .filter(file -> file.getFileName().toString().equals(INDEX_FILE))
                .map(Path::getParent)
                .filter(prepDir -> !mediaStore.exists(prepDir.resolve(DECISIONS_FILE)))
                .<WaitingCullJob>mapMulti((prepDir, consumer) -> readWaitingJob(prepDir).ifPresent(consumer))
                .toList();
    }

    private Optional<WaitingCullJob> readWaitingJob(Path prepDir) {
        try {
            return Optional.of(buildWaitingJob(cullPrepPort.readIndex(prepDir)));
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
    }

    // Re-reads an existing prep dir (no montages regenerated) and re-runs the same dispatch-then-apply
    // flow cull() used, this time with the caller's own allowPartial. Resume is safely re-runnable.
    // It stays read-only until the shard set actually validates. A resume triggered before every
    // shard is dropped just throws right back into Waiting with a freshly recomputed tally.
    public JobHandle<CullJobOutcome> resume(Path prepDir, boolean allowPartial) {
        return jobRunner.submit(handle ->
                dispatchAndApply(cullPrepPort.readIndex(prepDir), allowPartial, handle::isCancellationRequested));
    }

    // A CullException from the dispatch step means different things depending on the configured
    // provider - see VisionCuller.MANUAL_MODE_PROVIDER_ID's own doc. For that provider it's the
    // expected manual-mode pause: resolved into Waiting, run slot released. For any other (automated)
    // provider it's a genuine failure and propagates - it never throws CullException to signal a
    // cancellation. An automated provider's cull() still lands in Waiting on cancellation, but via
    // the cancellation.isCancelled() check further down, after dispatch returns normally rather than
    // through this catch block.
    //
    // disarmWatch() runs unconditionally up front, regardless of whether this call landed here from
    // cull(), a user's manual resume(), or a watcher's own auto-resume. Whatever watcher was polling
    // this prep dir is retired the moment any resume attempt actually runs. That means a manual
    // click racing an armed watcher can never leave two pollers running for the same job. A fresh
    // watcher gets (re-)armed below only if the outcome is Waiting again.
    private CullJobOutcome dispatchAndApply(PrepDir prep, boolean allowPartial, CancellationSignal cancellation)
            throws Exception {
        disarmWatch(prep.prepDir());
        CullReport cullReport;
        try {
            cullReport = runPhase(CULLING,
                    progress -> cullDispatcher.cull(prep, new CullOptions(allowPartial, null), progress, cancellation));
        } catch (CullException e) {
            if (!cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
                throw e;
            }
            WaitingCullJob job = buildWaitingJob(prep);
            // Not armed when this CullException is itself the manual-mode pause racing a
            // cancellation: an auto-resume moments after a cancel would defy it. A plain manual
            // pause (no cancellation involved) still arms as before.
            if (!cancellation.isCancelled()) {
                armWatchIfConfigured(job);
            }
            return new CullJobOutcome.Waiting(job);
        }
        // The one boundary check this method has for an automated provider: dispatch just
        // returned, either because it finished or because it stopped early on a cancellation. This
        // doubles as the pre-APPLYING check. It also covers the external-agent flow: its dispatch
        // never sees a cancellation mid-call, but a cancellation requested right after it still
        // lands here before apply moves anything.
        if (cancellation.isCancelled()) {
            return new CullJobOutcome.Waiting(buildWaitingJob(prep));
        }
        Optional<ApplyReport> applyReport = runPhase(APPLYING,
                progress -> Optional.ofNullable(
                        applyEngine.apply(prep.prepDir(), new ApplyOptions(allowPartial), progress, cancellation)));
        // Empty means apply() itself stopped mid-loop and skipped its finalizers, so
        // decisions.json was never written. The prep dir still reads as a waiting job, the same
        // authority rule the renderer's own empty return follows above. No watcher is armed here
        // either, for the same reason the pre-APPLYING check above doesn't: an auto-resume
        // moments after a cancel would defy it.
        if (applyReport.isEmpty()) {
            return new CullJobOutcome.Waiting(buildWaitingJob(prep));
        }
        return new CullJobOutcome.Applied(cullReport, applyReport.get());
    }

    private WaitingCullJob buildWaitingJob(PrepDir prep) {
        return new WaitingCullJob(prep.scope(), prep.prepDir(), tally(prep), mediaStore.lastModifiedTime(prep.prepDir()));
    }

    // Starts polling job's prep dir for an auto-resume, unless mode is MANUAL, the configured
    // provider isn't the external-agent one, or a watcher is already active for it.
    // armWatchesForExistingWaitingJobs() and dispatchAndApply()'s own Waiting branch can both reach
    // here for the same prep dir. The second call is then a no-op rather than a competing second
    // poller.
    //
    // Watch mode is an external-agent feature: it exists to notice when the user's own separate
    // culling agent, running outside this app, drops a shard. The provider check mainly guards
    // armWatchesForExistingWaitingJobs()'s startup scan, which walks every waiting job on disk
    // regardless of which provider produced it. dispatchAndApply()'s own call site can only reach
    // this method when the provider already matches, so the check is redundant there, but harmless.
    // Without the guard, a leftover external-agent.mode=watch setting combined with
    // provider=anthropic would arm a phantom watcher for an automated provider's own interrupted
    // (cancelled) prep dir. A fully-valid tally there would then trigger an unasked-for,
    // API-spending auto-resume the user never opted into.
    private void armWatchIfConfigured(WaitingCullJob job) {
        if (cullSettings.externalAgent().mode() != WatchMode.WATCH
                || !cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
            return;
        }
        Path prepDir = job.prepDir();
        activeWatches.compute(prepDir, (_, existing) -> {
            if (existing != null && existing.isActive()) {
                return existing;
            }
            // Instant.now() here, not job.since() (the prep dir's own mtime). watchTimeout is
            // deliberately "how long this watcher keeps polling in one continuous streak," not
            // "total time since the job first started waiting." A re-arm gets its own full timeout
            // window instead of inheriting a countdown already run down by an earlier streak. A
            // fresh app restart or a shard that turned invalid after looking ready are both re-arms.
            var watcher = new CullWatcher(watchPollInterval, cullSettings.externalAgent().watchTimeout(),
                    () -> isFullyValid(prepDir), () -> tryAutoResume(prepDir), Instant.now());
            watcher.start();
            return watcher;
        });
    }

    private void disarmWatch(Path prepDir) {
        CullWatcher watcher = activeWatches.remove(prepDir);
        if (watcher != null) {
            watcher.stop();
        }
    }

    // Test seam: whether a watcher is currently polling prepDir. Lets a test prove disarmWatch()'s
    // own claim - that any dispatchAndApply() call retires an existing watcher, not just the
    // watcher's own auto-resume trigger. No need to reach into the private activeWatches map.
    boolean isWatchActive(Path prepDir) {
        CullWatcher watcher = activeWatches.get(prepDir);
        return watcher != null && watcher.isActive();
    }

    // Cheap status check a CullWatcher polls repeatedly: does prepDir's tally already show every
    // montage present and valid? Deliberately not the heavier resume()/dispatchAndApply() path. A
    // transiently unreadable index (mid-write by a concurrent process) degrades to "not ready yet"
    // here rather than propagating - the same tolerance readWaitingJob() already gives this case.
    private boolean isFullyValid(Path prepDir) {
        try {
            ShardTally shards = tally(cullPrepPort.readIndex(prepDir));
            return shards.valid() == shards.total();
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    // The heavier action a CullWatcher runs at most once it thinks isFullyValid(). Returns whether
    // it actually got to run. True means resume()'s own jobRunner.submit() succeeded - the watcher's
    // job is then done, win or lose (see dispatchAndApply()'s own re-arm-on-Waiting note). False
    // means the job runner was busy with something else, so the watcher keeps polling and retries.
    // The submitted job runs and completes fully asynchronously; nothing here waits on it. A
    // failure there would otherwise vanish silently, so it's logged here instead. That matches the
    // visibility a manual Resume gets for free from whatever UI/CLI surfaces its own
    // join()/onComplete() failure.
    private boolean tryAutoResume(Path prepDir) {
        JobHandle<CullJobOutcome> handle;
        try {
            handle = resume(prepDir, false);
        } catch (IllegalStateException busy) {
            return false;
        }
        handle.onComplete().whenComplete((_, failure) -> {
            if (failure != null) {
                log.warn("Watch-mode auto-resume for {} failed", prepDir, failure);
            }
        });
        return true;
    }

    // present/valid computed per montage, one shard at a time, rather than through ApplyEngine's
    // own whole-batch validate(). A cross-shard problem (a near-dup group id reused across two
    // montages, a file claimed by two different shards) isn't caught here. That montage still
    // counts as valid.
    // That's an acceptable simplification for a progress-display number - the real gate stays
    // ApplyEngine.apply()'s full-batch validate(), unchanged by this tally.
    private ShardTally tally(PrepDir prep) {
        List<Path> sidecarSrcs = prep.entries().stream()
                .flatMap(montage -> readSidecar(prep, montage).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        List<String> categories = cullSettings.categories().stream().map(CullCategory::name).toList();

        List<MontageShardStatus> statuses = prep.entries().stream()
                .map(montage -> montageShardStatus(prep, montage, sidecarSrcs, categories))
                .toList();
        int present = (int) statuses.stream().filter(MontageShardStatus::present).count();
        int valid = (int) statuses.stream().filter(MontageShardStatus::valid).count();
        return new ShardTally(present, valid, prep.entries().size());
    }

    // A sidecar this app wrote itself during prep should always be readable. A transiently unreadable
    // one (mid-write by a concurrent cull job) degrades to "contributes no in-scope files" here,
    // rather than failing the whole tally. That's the same tolerance montageShardStatus() already
    // gives an unparseable shard below.
    private List<SidecarPhotoEntry> readSidecar(PrepDir prep, String montage) {
        try {
            return cullPrepPort.readSidecar(prep.prepDir(), montage);
        } catch (UncheckedIOException e) {
            return List.of();
        }
    }

    private MontageShardStatus montageShardStatus(PrepDir prep, String montage, List<Path> sidecarSrcs,
            List<String> categories) {
        if (!cullPrepPort.hasShard(prep.prepDir(), montage)) {
            return new MontageShardStatus(false, false);
        }
        try {
            var shardFile = new ShardFile(montage, cullPrepPort.readShard(prep.prepDir(), montage));
            var report = shardValidator.validate(List.of(shardFile), sidecarSrcs, categories, prep.unreviewable());
            return new MontageShardStatus(true, report.problems().isEmpty());
        } catch (UncheckedIOException e) {
            // Present but unparseable, so not valid.
            return new MontageShardStatus(true, false);
        }
    }

    private record MontageShardStatus(boolean present, boolean valid) {
    }

    // phaseFinished fires in a finally so the phaseStarted/phaseFinished bracket always closes,
    // even when the engine call itself throws. A listener otherwise has no signal the phase ever
    // ended.
    private <T> T runPhase(String phase, PhaseWork<T> work) throws Exception {
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
    private interface PhaseWork<T> {
        T run(ProgressCallback progress) throws Exception;
    }
}
