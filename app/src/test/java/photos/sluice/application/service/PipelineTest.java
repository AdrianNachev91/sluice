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
import photos.sluice.application.port.in.CurateOutcome;
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
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
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

    // Proves the real sort-then-cull round trip, not two separately-mocked halves. A real photo
    // genuinely leaves Inbox for Sorted, and the SAME job then culls the year it just landed in, all
    // the way to Applied.
    //
    // AutoApproveCuller stands in for a real automated provider (Anthropic/OpenAI/Ollama). It writes
    // its own valid shard for every montage in one call, the way a real automated culler would
    // after resolving its own judgements. curate() runs prep, dispatch, and apply inside one
    // submit() call, with no gap to hand-drop a shard into - unlike the ManualModeCuller tests
    // above, which need one.
    @Test
    void curateSortsThenCullsInOneJobEndToEnd(@TempDir Path root) throws IOException {
        Path photo = writeInboxPhoto(root, "20190601_photo.jpg");
        var progress = new RecordingProgressPort();

        CurateOutcome outcome = curatePipeline(root, progress).curate(new SortScope.Year(2019, null)).join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(Files.exists(photo)).isFalse();
        Path sorted = root.resolve("Sorted/Photos/2019/06/20190601_photo.jpg");
        assertThat(Files.exists(sorted)).isTrue();
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        var applied = (CullJobOutcome.Applied) Objects.requireNonNull(outcome.cullOutcome());
        assertThat(applied.applyReport().reviewed()).isEqualTo(1);
        // Untouched: AutoApproveCuller's shard carries no decision for it, so it's implicitly kept.
        assertThat(Files.exists(sorted)).isTrue();
        assertThat(progress.events).containsExactly(
                "started:Sorting...", "tick:Sorting...:1/1", "finished:Sorting...",
                "started:Building montages...", "tick:Building montages...:1/1", "finished:Building montages...",
                "started:Culling...", "finished:Culling...",
                "started:Applying decisions...", "finished:Applying decisions...");
    }

    @Test
    void curateWithOldestYearScopeResolvesAndCullsTheYearTheSortPicked(@TempDir Path root) throws IOException {
        writeInboxPhoto(root, "20190601_photo.jpg");

        CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear()).join();

        assertThat(outcome.sortSummary().yearsSorted()).containsExactly(2019);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(root.resolve("logs/cull-prep/2019/index.json"))).isTrue();
    }

    // An OldestYear scope's target year only exists once the sort resolves it. An empty (or
    // fully-empty-after-routing) Inbox never resolves one, so there is nothing for the cull stage to
    // even target. Proven by the absence of a cull-prep dir at all, not just a null cullOutcome.
    // That shows the cull stage never ran, rather than running over some empty default scope.
    @Test
    void curateSkipsCullWhenAnOldestYearSortFindsNothingToSort(@TempDir Path root) throws IOException {
        Files.createDirectories(inboxOf(root));

        CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear()).join();

        assertThat(outcome.sortSummary().processed()).isZero();
        assertThat(outcome.cullOutcome()).isNull();
        assertThat(Files.exists(root.resolve("logs/cull-prep"))).isFalse();
    }

    // Sort is never restricted to fit cull's one-scope shape. An OldestN sort still runs its normal,
    // complete job, and can genuinely land files across more than one year. Proven here by two
    // photos in different years both getting sorted. Only the cull stage mirrors the same n back
    // through CullScope.OldestN - the same mtime-ordered scope a standalone cull() call would use.
    @Test
    void curateWithOldestNScopeSortsAcrossYearsAndCullsTheSameCount(@TempDir Path root) throws IOException {
        writeInboxPhoto(root, "20180601_a.jpg", 1);
        writeInboxPhoto(root, "20190601_b.jpg", 2);

        CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestN(2)).join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(2);
        assertThat(outcome.sortSummary().yearsSorted()).containsExactlyInAnyOrder(2018, 2019);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        var applied = (CullJobOutcome.Applied) Objects.requireNonNull(outcome.cullOutcome());
        assertThat(applied.applyReport().reviewed()).isEqualTo(2);
        assertThat(Files.exists(root.resolve("logs/cull-prep/oldest-2/index.json"))).isTrue();
    }

    // Mirrors curateRefusesAnExplicitYearScopeAlreadyWaitingOnShards below, for the other scope shape
    // whose CullScope is known before curate() ever submits a job.
    @Test
    void curateRefusesAnOldestNScopeAlreadyWaitingOnShards(@TempDir Path root) throws IOException {
        Files.createDirectories(inboxOf(root));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.OldestN(1)).join();

        assertThatThrownBy(() -> pipeline.curate(new SortScope.OldestN(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oldest-1");
    }

    // The other half of monthsFromRange()'s translation. Every other curate() test passes null
    // months, so this is the only coverage for an actual MonthRange narrowing down to a specific
    // CullScope.Year(months) list.
    @Test
    void curateWithAnExplicitMonthRangeNarrowsTheCullScopeToThoseMonths(@TempDir Path root) throws IOException {
        writeInboxPhoto(root, "20190601_june.jpg");
        writeInboxPhoto(root, "20190815_august.jpg", 3);

        CurateOutcome outcome = curatePipeline(root, new RecordingProgressPort())
                .curate(new SortScope.Year(2019, new MonthRange(6, 6)))
                .join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/20190601_june.jpg"))).isTrue();
        // August is out of the requested month range, so it's still sitting in Inbox, unsorted.
        assertThat(Files.exists(root.resolve("Inbox/20190815_august.jpg"))).isTrue();
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(root.resolve("logs/cull-prep/2019-06/index.json"))).isTrue();
    }

    // An explicit Year scope names its target unconditionally. curate() culls it once sorted
    // regardless of whether this particular run added anything new there. Unlike OldestYear, which
    // has no year to cull at all if its own sort found nothing. Here the sort itself finds nothing
    // new (Inbox is empty), yet a photo already sitting in Sorted from an earlier, uncommitted run
    // still gets culled.
    @Test
    void curateWithAnExplicitYearScopeCullsThatYearEvenWhenThisRunSortedNothingNew(@TempDir Path root)
            throws IOException {
        Files.createDirectories(inboxOf(root));
        Path existing =
                writePhoto(sortedPhotosDir(root, "2019", "06"), "already-sorted.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.Year(2019, null)).join();

        assertThat(outcome.sortSummary().processed()).isZero();
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(existing)).isTrue();
    }

    // Mirrors cull()'s own "refuses to rebuild a scope with an unresolved WaitingCullJob" contract.
    // An explicit Year scope's target CullScope is known before curate() ever submits a job, so it
    // gets the same synchronous, pre-sort fail-fast. Proven here by the sort never running at all:
    // the pre-existing Sorted photo is still there, untouched, and no second prep dir was written.
    @Test
    void curateRefusesAnExplicitYearScopeAlreadyWaitingOnShards(@TempDir Path root) throws IOException {
        Files.createDirectories(inboxOf(root));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();

        assertThatThrownBy(() -> pipeline.curate(new SortScope.Year(2019, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2019");
    }

    // An OldestYear scope can't get the synchronous pre-sort refusal above - its year isn't known
    // until the sort resolves it. So this same conflict can only surface after the sort has already
    // moved real files, and the caller must not lose track of what moved just because the cull stage
    // was refused. Pipeline.CurateConflictException carries the SortSummary forward for exactly that.
    @Test
    void curateWrapsAPostSortConflictInCurateConflictExceptionCarryingTheSortSummary(@TempDir Path root)
            throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "already-there.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();
        Path newPhoto = writeInboxPhoto(root, "20190815_new.jpg");

        var handle = curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear());

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .extracting(Throwable::getCause)
                .isInstanceOfSatisfying(Pipeline.CurateConflictException.class,
                        conflict -> assertThat(conflict.sortSummary().photosSorted()).isEqualTo(1));
        // The sort's own effect survives the refused cull stage - the new photo really did move.
        assertThat(Files.exists(newPhoto)).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/08/20190815_new.jpg"))).isTrue();
    }

    // Proves the cancellation wiring between curate()'s two stages: cooperative, checked only at the
    // boundary between them, never mid-engine-call.
    //
    // BlockingMoves lets the test synchronize with the exact moment SortEngine is mid-move. It can
    // then request cancellation before curate()'s post-sort check runs - a real observable signal,
    // not a guessed sleep. The sort itself still completes in full; its own single move() call is
    // never interrupted, only delayed. Only the cull stage that would have followed it is skipped.
    @Test
    void curateSkipsTheCullStageWhenCancellationIsRequestedBetweenStages(@TempDir Path root) throws Exception {
        writeInboxPhoto(root, "20190601_photo.jpg");
        var moveStarted = new CountDownLatch(1);
        var releaseMove = new CountDownLatch(1);
        var pipeline = curatePipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        JobHandle<CurateOutcome> handle = pipeline.curate(new SortScope.Year(2019, null));
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        CurateOutcome outcome = handle.join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(outcome.cullOutcome()).isNull();
        assertThat(Files.exists(root.resolve("logs/cull-prep"))).isFalse();
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

    // curate() tests go through this name, wiring AutoApproveCuller as the configured provider.
    // curate() runs prep/dispatch/apply in one call, with no gap to hand-drop a shard into the way
    // the manual-mode cull() tests above do.
    private static Pipeline curatePipeline(Path root, RecordingProgressPort progress) {
        return curatePipeline(root, progress, new NioMediaStore());
    }

    private static Pipeline curatePipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, autoApproveCullSettings(), List.of(new AutoApproveCuller()));
    }

    private static CullSettings autoApproveCullSettings() {
        return new FixedSettings("auto-approve", List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
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

    // Inbox-side sibling of writePhoto() above: that one writes straight into Sorted, where
    // SortEngine's own low-res gate never runs again, so a small solid-color JPEG is fine there. A
    // photo that needs to survive an actual sort pass has to clear that gate for real. A solid fill
    // compresses to only a few KB, well under LowResGate's 50KB floor. Filling every pixel with
    // random noise instead defeats JPEG compression, so the file clears the floor easily. name must
    // carry a FilenameSource-recognized date (e.g. "20190601_photo.jpg") since these fixtures have
    // no EXIF or Takeout JSON.
    private static Path writeInboxPhoto(Path root, String name) throws IOException {
        return writeInboxPhoto(root, name, 42);
    }

    // seed varies the noise, so two calls in the same test never produce byte-identical files that
    // ByteIdenticalDedup would then collapse into one.
    private static Path writeInboxPhoto(Path root, String name, long seed) throws IOException {
        Path file = inboxOf(root).resolve(name);
        Files.createDirectories(file.getParent());
        var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var random = new Random(seed);
        for (int y = 0; y < PHOTO_HEIGHT; y++) {
            for (int x = 0; x < PHOTO_WIDTH; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ImageIO.write(image, "jpg", file.toFile());
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

    // Wraps the real NioMediaStore but blocks the first move() call between two latches. A test can
    // synchronize with the exact moment SortEngine is mid-move this way. That's real observable
    // proof it hasn't returned yet, not a guessed sleep long enough to "probably" still be running.
    private static final class BlockingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CountDownLatch moveStarted;
        private final CountDownLatch releaseMove;

        BlockingMoves(CountDownLatch moveStarted, CountDownLatch releaseMove) {
            this.moveStarted = moveStarted;
            this.releaseMove = releaseMove;
        }

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
            moveStarted.countDown();
            try {
                releaseMove.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return delegate.move(source, destDir);
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

    // Stands in for a real automated provider (Anthropic/OpenAI/Ollama) that always succeeds on its
    // first try. It writes its own valid shard for every montage in one call, the way a real
    // automated culler would after resolving its own judgements. No test needs to hand-drop one
    // mid-run the way ManualModeCuller's tests do above.
    //
    // Unconditional, not gated on hasShard() the way ManualModeCuller is. buildFreshAndDispatch()
    // always rebuilds the prep dir fresh right before dispatch runs, so a montage here can never
    // already carry a shard.
    //
    // An empty decisions array is still a valid shard. ShardValidator has no "every photo needs a
    // decision" rule, so every photo in scope is simply left in place, implicitly kept.
    private static final class AutoApproveCuller implements VisionCuller {
        @Override
        public String id() {
            return "auto-approve";
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) {
            for (String montage : prep.entries()) {
                try {
                    writeShard(prep.prepDir(), montage);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new CullReport(prep.entries().size(), 0, 0, 0);
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
