package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.CullJobOutcome;
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
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
    private static final String PREPPING = "Building montages...";
    private static final String CULLING = "Culling...";
    private static final String APPLYING = "Applying decisions...";
    private static final String DECISIONS_FILE = "decisions.json";
    private static final String INDEX_FILE = "index.json";

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
    private final ShardValidator shardValidator = new ShardValidator();

    public Pipeline(SortEngine sortEngine, CommitEngine commitEngine, RescueEngine rescueEngine,
            MontageRenderer montageRenderer, CullDispatcher cullDispatcher, ApplyEngine applyEngine,
            CullPrepPort cullPrepPort, CullSettings cullSettings, MediaStore mediaStore, PathsPort pathsPort,
            MontageConfig montageConfig, JobRunner jobRunner, ProgressPort progressPort) {
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

    // Prep always runs fresh: a scope's montages are rebuilt from Sorted every call, and
    // MontageRenderer.build() clears whatever a stale prior run left in the same prep dir first. That
    // would silently destroy any shards already dropped for a still-unresolved WaitingCullJob on the
    // same scope, so this checks for one and fails loud instead - resume or resolve it first. Dispatch
    // itself always runs with allowPartial=false: waiving a missing shard is a resume-time human
    // decision (see dispatchAndApply()), never the default for a first attempt.
    public JobHandle<CullJobOutcome> cull(CullScope scope) {
        String tag = CullScope.tag(scope);
        Optional<WaitingCullJob> existing = waitingJobs().stream().filter(job -> job.scope().equals(tag)).findFirst();
        if (existing.isPresent()) {
            throw new IllegalStateException("A cull for scope '" + tag + "' is already waiting on shards at "
                    + existing.get().prepDir() + " - resume or resolve it before starting a new cull for the same scope.");
        }
        return jobRunner.submit(_ -> {
            PrepDir prep = runPhase(PREPPING, progress -> montageRenderer.build(scope, montageConfig, progress));
            return dispatchAndApply(prep, false);
        });
    }

    // Every cull still waiting on shards, derived live off disk rather than a persisted list (see
    // WaitingCullJob's own doc). A prep dir counts as waiting when it has index.json (prep ran) but no
    // decisions.json yet (apply never completed). Not routed through JobRunner - this only reads, so
    // it doesn't compete for the single job slot. A prep dir whose index.json is transiently
    // unreadable (mid-write by a concurrent cull job) is skipped rather than failing the whole scan -
    // the same tolerance the external-agent design already gives a shard mid-write.
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
    // flow cull() used, this time with the caller's own allowPartial. Resume is safely re-runnable: it
    // stays read-only until the shard set actually validates, so a resume triggered before every shard
    // is dropped just throws right back into Waiting with a freshly recomputed tally.
    public JobHandle<CullJobOutcome> resume(Path prepDir, boolean allowPartial) {
        return jobRunner.submit(_ -> dispatchAndApply(cullPrepPort.readIndex(prepDir), allowPartial));
    }

    // A CullException from the dispatch step means different things depending on the configured
    // provider - see VisionCuller.MANUAL_MODE_PROVIDER_ID's own doc. For that provider it's the
    // expected manual-mode pause: resolved into Waiting, run slot released. For any other (automated)
    // provider it's a genuine failure and propagates. That matches CullJobOutcome.Applied's own doc:
    // an automated provider always resolves there or throws, never lands in Waiting.
    private CullJobOutcome dispatchAndApply(PrepDir prep, boolean allowPartial) throws Exception {
        CullReport cullReport;
        try {
            cullReport = runPhase(CULLING,
                    progress -> cullDispatcher.cull(prep, new CullOptions(allowPartial, null), progress));
        } catch (CullException e) {
            if (!cullSettings.provider().equals(VisionCuller.MANUAL_MODE_PROVIDER_ID)) {
                throw e;
            }
            return new CullJobOutcome.Waiting(buildWaitingJob(prep));
        }
        ApplyReport applyReport = runPhase(APPLYING,
                progress -> applyEngine.apply(prep.prepDir(), new ApplyOptions(allowPartial), progress));
        return new CullJobOutcome.Applied(cullReport, applyReport);
    }

    private WaitingCullJob buildWaitingJob(PrepDir prep) {
        return new WaitingCullJob(prep.scope(), prep.prepDir(), tally(prep), mediaStore.lastModifiedTime(prep.prepDir()));
    }

    // present/valid computed per montage, one shard at a time, rather than through ApplyEngine's own
    // whole-batch validate(). A cross-shard problem (a near-dup group id reused across two montages, a
    // file claimed by two different shards) isn't caught here, so that montage still counts as valid.
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
    // rather than failing the whole tally - the same tolerance montageShardStatus() already gives an
    // unparseable shard below.
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

    // phaseFinished fires in a finally so the phaseStarted/phaseFinished bracket always closes, even
    // when the engine call itself throws - a listener otherwise has no signal the phase ever ended.
    private <T> T runPhase(String phase, PhaseWork<T> work) throws Exception {
        progressPort.phaseStarted(phase);
        try {
            return work.run((current, total) -> progressPort.tick(phase, current, total));
        } finally {
            progressPort.phaseFinished(phase);
        }
    }

    // Function<ProgressCallback, T> can't wrap cullDispatcher.cull()/applyEngine.apply(), both of
    // which declare checked exceptions - declares throws Exception itself instead, the same shape
    // JobWork already uses for the same reason. A lambda that throws nothing still satisfies it.
    @FunctionalInterface
    private interface PhaseWork<T> {
        T run(ProgressCallback progress) throws Exception;
    }
}
