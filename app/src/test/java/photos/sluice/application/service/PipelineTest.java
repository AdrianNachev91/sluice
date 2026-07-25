package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.InboxScanner;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.CullMontageRenderer;
import photos.sluice.adapter.imaging.ImageDimensionsReader;
import photos.sluice.adapter.imaging.MontageBuilder;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.imaging.TileRenderer;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineTest {

    @Test
    void sortRunsSortEngineAndReturnsItsSummary(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(inboxOf(root).resolve("20210315_photo.jpg"), padded("keeper"));

        SortSummary summary = pipeline(root, progress).sort(new SortScope.OldestYear()).join();

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
    }

    @Test
    void sortBracketsProgressEventsAroundTheSortPhase(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(inboxOf(root).resolve("20210315_a.jpg"), padded("a"));
        writeFile(inboxOf(root).resolve("20210316_b.jpg"), padded("b"));

        pipeline(root, progress).sort(new SortScope.OldestYear()).join();

        assertThat(progress.events).containsExactly(
                "started:Sorting...", "tick:Sorting...:1/2", "tick:Sorting...:2/2", "finished:Sorting...");
    }

    @Test
    void commitRunsCommitEngineAndReturnsItsSummary(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        CommitSummary summary = pipeline(root, progress).commit(new CommitScope.All()).join();

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Library/Photos/2019/06/a.jpg"))).isTrue();
    }

    @Test
    void commitBracketsProgressEventsAroundTheCommitPhase(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/07/b.jpg"), "b");

        pipeline(root, progress).commit(new CommitScope.All()).join();

        assertThat(progress.events).containsExactly(
                "started:Committing...", "tick:Committing...:1/2", "tick:Committing...:2/2", "finished:Committing...");
    }

    @Test
    void rescueRunsRescueEngineAndReturnsItsSummary(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        RescueSummary summary = pipeline(root, progress).rescue("2019-06").join();

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Library/Photos/2019/06/IMG_1.jpg"))).isTrue();
    }

    @Test
    void rescueBracketsProgressEventsAroundTheRescuePhase(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "one");
        writeFile(root.resolve("Review/2019-06/IMG_2.jpg"), "two");

        pipeline(root, progress).rescue("2019-06").join();

        assertThat(progress.events).containsExactly(
                "started:Rescuing...", "tick:Rescuing...:1/2", "tick:Rescuing...:2/2", "finished:Rescuing...");
    }

    // The ProgressPort doc says phaseStarted/phaseFinished always bracket a phase. This proves that
    // holds on the failure path too, not only the happy path a normal engine test would exercise.
    // Without it, a job that dies mid-engine-call would leave a listener's progress bar showing
    // "in progress" forever with no signal the phase ever ended.
    @Test
    void phaseFinishedFiresEvenWhenTheEngineThrows(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        var handle = pipeline(root, progress, new FailingMoves()).rescue("2019-06");

        assertThatThrownBy(handle::join).isInstanceOf(CompletionException.class);
        assertThat(progress.events).containsExactly("started:Rescuing...", "finished:Rescuing...");
    }

    @Test
    void cullReturnsWaitingWithAnEmptyTallyWhenNoShardsHaveBeenDropped(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        CullJobOutcome outcome = cullPipeline(root, progress).cull(new CullScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        WaitingCullJob job = ((CullJobOutcome.Waiting) outcome).job();
        assertThat(job.scope()).isEqualTo("2019");
        assertThat(job.shards()).isEqualTo(new ShardTally(0, 0, 1));
        assertThat(Files.exists(job.prepDir().resolve("index.json"))).isTrue();
    }

    @Test
    void cullBracketsPreppingAndCullingPhasesButNeverReachesApplyingWhenWaiting(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        cullPipeline(root, progress).cull(new CullScope.Year(2019, null)).join();

        assertThat(progress.events).containsExactly(
                "started:Building montages...", "tick:Building montages...:1/1", "finished:Building montages...",
                "started:Culling...", "finished:Culling...");
    }

    // Regression: cull() used to rebuild a scope's prep dir unconditionally, and MontageRenderer.
    // build() clears that dir before writing - so re-running cull() on a scope that already has an
    // unresolved WaitingCullJob would silently destroy any shard already dropped for it.
    @Test
    void cullRefusesToRebuildAScopeThatAlreadyHasAWaitingJob(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2019");
        // The dropped shard must have survived the refused call - proves no rebuild/clear happened.
        assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
    }

    @Test
    void waitingJobsIsEmptyWhenNoCullHasEverRun(@TempDir Path root) {
        assertThat(cullPipeline(root, new RecordingProgressPort()).waitingJobs()).isEmpty();
    }

    @Test
    void waitingJobsListsAPrepDirStillMissingShards(@TempDir Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();

        List<WaitingCullJob> waiting = pipeline.waitingJobs();

        assertThat(waiting).hasSize(1);
        assertThat(waiting.getFirst().scope()).isEqualTo("2019");
        assertThat(waiting.getFirst().shards()).isEqualTo(new ShardTally(0, 0, 1));
    }

    @Test
    void resumeAppliesOnceAValidShardIsDropped(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        var applied = (CullJobOutcome.Applied) outcome;
        assertThat(applied.applyReport().byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    @Test
    void resumeBracketsTheApplyingPhaseOnTheAppliedPath(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var progress = new RecordingProgressPort();
        var pipeline = cullPipeline(root, progress);
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        progress.events.clear();

        pipeline.resume(prepDir, false).join();

        assertThat(progress.events).containsExactly(
                "started:Culling...", "finished:Culling...",
                "started:Applying decisions...", "tick:Applying decisions...:1/1", "finished:Applying decisions...");
    }

    @Test
    void resumeReturnsWaitingAgainWithAnUpdatedTallyWhenAMontageStillLacksAShard(@TempDir Path root) throws IOException {
        Path juneDir = sortedPhotosDir(root, "2019", "06");
        Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        assertThat(waiting.job().shards()).isEqualTo(new ShardTally(0, 0, 2));
        writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

        CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        assertThat(((CullJobOutcome.Waiting) outcome).job().shards()).isEqualTo(new ShardTally(1, 1, 2));
        assertThat(Files.exists(a)).isTrue();
    }

    @Test
    void resumeWithAllowPartialAppliesWhatItHasAndLeavesTheMissingMontagesPhotoInPlace(@TempDir Path root) throws IOException {
        Path juneDir = sortedPhotosDir(root, "2019", "06");
        Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        Path b = writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

        CullJobOutcome outcome = pipeline.resume(prepDir, true).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(a)).isFalse();
        assertThat(Files.exists(b)).isTrue();
    }

    // The design reason Pipeline distinguishes providers at all. An automated provider's shards never
    // arrive externally, so nothing more is coming on its own. Its CullException is a genuine failure
    // and must propagate, not quietly park the job as "waiting" like the external-agent provider's
    // identical checked exception does.
    @Test
    void cullPropagatesAFailureFromAnAutomatedProviderInsteadOfReturningWaiting(@TempDir Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var progress = new RecordingProgressPort();
        var settings = new FixedSettings("anthropic", List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
        var pipeline = cullPipeline(root, progress, settings, List.of(new ThrowingCuller("anthropic")));

        var handle = pipeline.cull(new CullScope.Year(2019, null));

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CullException.class);
        assertThat(progress.events).contains("finished:Culling...");
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Applying"));
    }

    @Test
    void cullInWatchModeAutoResumesOnceAValidShardIsDropped(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(null),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();

        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        waitUntil(Duration.ofSeconds(2), () -> !Files.exists(photo));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        assertThat(pipeline.waitingJobs()).isEmpty();
    }

    // Regression: disarmWatch() runs at the top of every dispatchAndApply() call, not just the
    // watcher's own auto-resume trigger - proves a manual resume() retires an armed watcher on its
    // own, so a manual click racing an armed watcher can never leave two pollers on the same job.
    // A long poll interval keeps the watcher itself from racing to auto-resume before the manual
    // resume() below runs - this test is only about the manual path disarming it.
    @Test
    void manualResumeDisarmsAnAlreadyArmedWatcher(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(null),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    // Proves watch mode survives a restart: nothing calls cull()/resume() on this Pipeline instance
    // for the job at all - armWatchesForExistingWaitingJobs() (Pipeline's own @PostConstruct, called
    // directly here since this test has no Spring context) has to discover it on disk and arm a
    // watcher purely from waitingJobs(), the same as it would after a real app restart.
    @Test
    void armWatchesForExistingWaitingJobsAutoResumesAJobItNeverStartedItself(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var manualPipeline = cullPipeline(root, new RecordingProgressPort());
        var waiting = (CullJobOutcome.Waiting) manualPipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(null),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        watchPipeline.armWatchesForExistingWaitingJobs();

        waitUntil(Duration.ofSeconds(2), () -> !Files.exists(photo));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // A short watchTimeout with no shard ever dropped: the watcher must give up on its own (no
    // auto-resume attempt) and leave every dropped shard - there are none here - untouched, exactly
    // the "drops back to manual, all work preserved" contract from watchTimeout's own doc. Manual
    // resume must still work afterward, proving the job itself was never touched by the timeout.
    @Test
    void watchModeGivesUpAfterTimeoutWithoutTouchingTheWaitingJob(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(Duration.ofMillis(60)),
                List.of(new ManualModeCuller()), Duration.ofMillis(10));
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();

        // Polls for the real signal - the watcher actually stopping itself once the timeout fires -
        // instead of guessing a fixed sleep duration long enough to cover it.
        waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
        assertThat(Files.exists(photo)).isTrue();

        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(photo)).isFalse();
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                // The busy-wait this polls for is a real background CullWatcher/JobRunner thread,
                // not something this test can await via a latch or callback.
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static Path inboxOf(Path root) {
        return root.resolve("Inbox");
    }

    private static Path sortedPhotosDir(Path root, String year, String month) {
        return root.resolve("Sorted").resolve("Photos").resolve(year).resolve(month);
    }

    private static Pipeline pipeline(Path root, RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore());
    }

    private static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    // cull()/waitingJobs()/resume() tests always go through this name, wiring the same manual-mode
    // default (a fake external-agent-shaped VisionCuller) unless a test needs to vary the provider.
    private static Pipeline cullPipeline(Path root, RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore(), defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    private static Pipeline cullPipeline(Path root, RecordingProgressPort progress, CullSettings cullSettings,
            List<VisionCuller> cullers) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers);
    }

    // Watch-mode tests go through this name: same wiring, but with a millisecond-scale poll
    // interval (via Pipeline's package-private test constructor) so a real auto-resume proves out
    // fast instead of waiting on the production 2-second cadence.
    private static Pipeline watchPipeline(Path root, RecordingProgressPort progress, CullSettings cullSettings,
            List<VisionCuller> cullers, Duration pollInterval) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers, pollInterval);
    }

    private static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore,
            CullSettings cullSettings, List<VisionCuller> cullers) {
        return pipeline(root, progress, mediaStore, cullSettings, cullers, null);
    }

    // The one full wiring every overload above funnels into - real adapters throughout (matching
    // this project's no-mocks test convention), same as the engines below. CullMontageRenderer's
    // HeifDecoder dependency is stubbed to always miss: none of these fixtures are HEIC/AVIF, and
    // real HEIC/AVIF decode already has its own coverage in TileRendererTest. pollInterval null
    // means "use Pipeline's own production default" - only watchPipeline() ever passes one.
    private static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore,
            CullSettings cullSettings, List<VisionCuller> cullers, @Nullable Duration pollInterval) {
        Path libraryRoot = root.resolve("Library");
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), libraryRoot.toString(), root.resolve("Inbox").toString()));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        var sha256Port = new Sha256Hasher();

        var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        var sortEngine = new SortEngine(pathsConfig, new InboxScanner(), dateResolver, sha256Port, hashIndex,
                new ImageDimensionsReader(), mediaStore);
        var commitEngine = new CommitEngine(pathsConfig, mediaStore, sha256Port, hashIndex);
        var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        var rescueEngine = new RescueEngine(pathsConfig, mediaStore, sha256Port, hashIndex, rescueDateResolver);

        HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        var montageRenderer = new CullMontageRenderer(new TileRenderer(stubHeifDecoder), new MontageBuilder(),
                new SidecarWriter(), new PrepIndexWriter(), mediaStore, pathsConfig);
        var cullPrepPort = new JsonCullPrepStore();
        var cullDispatcher = new CullDispatcher(cullers, cullSettings);
        var applyEngine = new ApplyEngine(pathsConfig, mediaStore, cullPrepPort, cullSettings, sha256Port, hashIndex);
        // tilesPerRow=1 gives one photo per montage, so a test controls exactly which montage a
        // given photo lands in via mtime ordering alone, without depending on batch-size math.
        var montageConfig = new MontageConfig(64, 1);

        if (pollInterval == null) {
            return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                    cullPrepPort, cullSettings, mediaStore, pathsConfig, montageConfig, new JobRunner(), progress);
        }
        return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                cullPrepPort, cullSettings, mediaStore, pathsConfig, montageConfig, new JobRunner(), progress,
                pollInterval);
    }

    private static CullSettings defaultCullSettings() {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
    }

    private static CullSettings watchCullSettings(@Nullable Duration watchTimeout) {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH, watchTimeout));
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // 60,000 bytes clears LowResGate's 50KB threshold, same fixture convention as SortEngineTest -
    // sort's progress-bracket tests aren't testing low-res routing and shouldn't accidentally
    // exercise it.
    private static String padded(String marker) {
        return marker + "x".repeat(60_000);
    }

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable -
    // same fixture convention as CullMontageRendererTest.
    private static final int PHOTO_WIDTH = 800;
    private static final int PHOTO_HEIGHT = 600;

    private static Path writePhoto(Path dir, String name, Instant mtime) throws IOException {
        Files.createDirectories(dir);
        var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, PHOTO_WIDTH, PHOTO_HEIGHT);
        } finally {
            g.dispose();
        }
        Path file = dir.resolve(name);
        ImageIO.write(image, "jpg", file.toFile());
        Files.setLastModifiedTime(file, FileTime.from(mtime));
        return file;
    }

    // Hand-drops a shard the same shape a real external agent would write, matching ApplyEngineTest's
    // own writeShard/classificationJson convention - ShardCodec itself is package-private to
    // adapter.vision and unreachable from here.
    private static void writeShard(Path prepDir, String montage, String... decisionsJson) throws IOException {
        String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    private static String classificationJson(Path file, String category, String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(jsonEscaped(file), category, reason);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static final class RecordingProgressPort implements ProgressPort {
        final List<String> events = new ArrayList<>();

        @Override
        public void phaseStarted(String phase) {
            events.add("started:" + phase);
        }

        @Override
        public void tick(String phase, int current, int total) {
            events.add("tick:" + phase + ":" + current + "/" + total);
        }

        @Override
        public void phaseFinished(String phase) {
            events.add("finished:" + phase);
        }
    }

    // Wraps the real NioMediaStore but always throws on move() - simulates an engine call that dies
    // mid-phase, to prove Pipeline still brackets phaseFinished on the failure path.
    private static final class FailingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            throw new RuntimeException("simulated crash");
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Stands in for the real (package-private, unreachable from here) ExternalAgentCuller. Only a
    // completeness check, gating on hasShard() alone rather than full shard validation. That
    // validation is ShardValidator/ApplyEngine's job, already covered by their own tests - and by
    // Pipeline's own tally(), which runs real ShardValidator logic independently of this fake.
    private static final class ManualModeCuller implements VisionCuller {
        @Override
        public String id() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
            List<String> missing = new ArrayList<>();
            int done = 0;
            for (String montage : prep.entries()) {
                boolean hasShard = Files.exists(prep.prepDir().resolve(
                        montage.replaceFirst("^montage-", "decisions-") + ".json"));
                if (hasShard) {
                    done++;
                } else if (!opts.allowPartial()) {
                    missing.add(montage);
                }
            }
            if (!missing.isEmpty()) {
                throw new CullException("missing shard(s) for: " + missing);
            }
            return new CullReport(done, prep.entries().size() - done, 0, 0);
        }
    }

    // Stands in for an automated provider (Anthropic/OpenAI/Ollama) whose CullException means a
    // genuine failure, never "waiting for more shards" - see VisionCuller.MANUAL_MODE_PROVIDER_ID.
    private record ThrowingCuller(String id) implements VisionCuller {
        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
            throw new CullException("the model could not produce a valid judgement");
        }
    }

    private record FixedSettings(String provider, List<CullCategory> categories, ExternalAgentSettings externalAgent)
            implements CullSettings {
        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }
    }
}
