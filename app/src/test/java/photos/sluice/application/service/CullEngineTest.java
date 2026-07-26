package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.BlockingCancellableCuller;
import static photos.sluice.application.service.PipelineTestSupport.BlockingIncompleteCuller;
import static photos.sluice.application.service.PipelineTestSupport.BlockingListFiles;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoveTo;
import static photos.sluice.application.service.PipelineTestSupport.FixedSettings;
import static photos.sluice.application.service.PipelineTestSupport.JunkEverythingCuller;
import static photos.sluice.application.service.PipelineTestSupport.ManualModeCuller;
import static photos.sluice.application.service.PipelineTestSupport.NeverCalledCuller;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.ThrowingCuller;
import static photos.sluice.application.service.PipelineTestSupport.classificationJson;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.defaultCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.waitUntil;
import static photos.sluice.application.service.PipelineTestSupport.watchCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.watchPipeline;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

class CullEngineTest {

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

    // Regression: cull() used to rebuild a scope's prep dir unconditionally, and
    // MontageRenderer.build() clears that dir before writing. Re-running cull() on a scope that
    // already has an unresolved WaitingCullJob would silently destroy any shard already dropped
    // for it.
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

    // Mid-cull cancellation for an automated provider: Cancel is effectively Pause. The
    // signal-honoring fake below writes a shard for the one montage it reaches before the signal
    // trips, then stops without throwing - never a CullException. dispatchAndApply's post-dispatch
    // cancellation check resolves the outcome to Waiting instead of propagating a failure. Watch
    // mode is configured on here too, but this path never calls
    // armWatchIfConfigured() at all. isWatchActive() below is therefore false regardless of
    // provider - the dedicated provider-gate test further down is what actually proves that gate.
    @Test
    void cullCancelledMidDispatchResolvesToWaitingThenResumeCompletes(@TempDir Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        var firstShardWritten = new CountDownLatch(1);
        var releaseCull = new CountDownLatch(1);
        var settings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH, null));
        var pipeline = cullPipeline(root, new RecordingProgressPort(), settings,
                List.of(new BlockingCancellableCuller(firstShardWritten, releaseCull)));

        JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        firstShardWritten.await();
        handle.requestCancellation();
        releaseCull.countDown();
        CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        WaitingCullJob job = ((CullJobOutcome.Waiting) outcome).job();
        Path prepDir = job.prepDir();
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isFalse();
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();

        CullJobOutcome resumed = pipeline.resume(prepDir, false).join();

        assertThat(resumed).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isTrue();
    }

    // Proves the armWatchIfConfigured() provider gate. armWatchesForExistingWaitingJobs()'s
    // startup scan walks every waiting job on disk regardless of which provider produced it.
    // dispatchAndApply()'s own call site is different: it can only reach armWatchIfConfigured() when
    // the provider already matches. So this scan is the one call site the gate actually changes
    // behavior at. Without it, a leftover mode=WATCH setting would arm a phantom watcher for this
    // automated provider's own cancelled prep dir, risking an unasked-for, API-spending auto-resume.
    @Test
    void armWatchesForExistingWaitingJobsNeverArmsAWatcherForAnAutomatedProvidersWaitingJob(@TempDir Path root)
            throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        var firstShardWritten = new CountDownLatch(1);
        var releaseCull = new CountDownLatch(1);
        var manualSettings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
        var manualPipeline = cullPipeline(root, new RecordingProgressPort(), manualSettings,
                List.of(new BlockingCancellableCuller(firstShardWritten, releaseCull)));

        JobHandle<CullJobOutcome> handle = manualPipeline.cull(new CullScope.Year(2019, null));
        firstShardWritten.await();
        handle.requestCancellation();
        releaseCull.countDown();
        var waiting = (CullJobOutcome.Waiting) handle.join();
        Path prepDir = waiting.job().prepDir();

        var watchSettings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH, null));
        var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchSettings, List.of(),
                Duration.ofMillis(20));

        watchPipeline.armWatchesForExistingWaitingJobs();

        assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
    }

    // MontageRenderer.build() (CullMontageRenderer) is itself cancellation-aware: it checks the
    // signal before rendering each candidate, entirely before the prep dir is ever cleared or
    // index.json is written. BlockingListFiles synchronizes the test with the exact moment
    // CullMontageRenderer is scanning Sorted for candidates, mid-render. "Block the slow real
    // call, request cancellation while blocked" is the same technique the sort/curate boundary
    // tests use. A null PrepDir return means nothing is resumable yet. buildFreshAndDispatch()
    // therefore resolves to CullJobOutcome.Cancelled rather than Waiting, proven here by the
    // whole cull-prep dir never existing at all. NeverCalledCuller fails the test outright if
    // dispatch runs at all, proving cancellation stops the job well before that.
    @Test
    void cullCancelledMidRenderResolvesToCancelledWithNoPrepDirEverWritten(@TempDir Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var listStarted = new CountDownLatch(1);
        var releaseList = new CountDownLatch(1);
        var mediaStore = new BlockingListFiles(listStarted, releaseList);
        var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, defaultCullSettings(),
                List.of(new NeverCalledCuller()));

        JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        listStarted.await();
        handle.requestCancellation();
        releaseList.countDown();
        CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Cancelled.class);
        assertThat(Files.exists(root.resolve("logs/cull-prep/2019"))).isFalse();
    }

    // The apply()-side sibling of the render-side test above: ApplyEngine is itself cancellation-
    // aware too, checked once per decision in its move loop. JunkEverythingCuller writes a real
    // "junk" classification for every photo (unlike AutoApproveCuller's all-keeps shard), so there
    // is an actual move loop for BlockingMoveTo to synchronize with. A null ApplyReport means
    // decisions.json was never written, so the prep dir still reads as a waiting job. Unlike the
    // mid-render case above, this one resolves to Waiting, not Cancelled - a resumable prep dir
    // (with its dispatched shards) already exists by this point.
    @Test
    void cullCancelledMidApplyResolvesToWaitingWithDecisionsJsonNeverWritten(@TempDir Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        var moveStarted = new CountDownLatch(1);
        var releaseMove = new CountDownLatch(1);
        var mediaStore = new BlockingMoveTo(moveStarted, releaseMove);
        var settings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
        var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, settings,
                List.of(new JunkEverythingCuller()));

        JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        Path prepDir = ((CullJobOutcome.Waiting) outcome).job().prepDir();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        // Exactly one of the two photos was fully processed (moved + recorded) before the
        // cancellation stopped the loop; scan order between them isn't guaranteed.
        try (var junked = Files.list(root.resolve("Review/junk"))) {
            assertThat(junked.filter(p -> p.getFileName().toString().startsWith("IMG_")).count()).isEqualTo(1);
        }
        try (var remaining = Files.list(sortedPhotosDir(root, "2019", "06"))) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    // The manual-mode CullException branch must not arm a watcher when cancellation raced
    // it - an auto-resume moments after a cancel would defy the cancel. Watch mode and the
    // external-agent provider are both genuinely configured here, so an uncancelled run of this
    // same pause would arm a watcher (see cullInWatchModeAutoResumesOnceAValidShardIsDropped below).
    // This proves the simultaneous cancellation is what suppresses it, not just the settings.
    @Test
    void manualModePauseDoesNotArmAWatcherWhenCancellationRacedIt(@TempDir Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(null),
                List.of(new BlockingIncompleteCuller(started, release)), Duration.ofMillis(20));

        JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        started.await();
        handle.requestCancellation();
        release.countDown();
        CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        Path prepDir = ((CullJobOutcome.Waiting) outcome).job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
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
    // watcher's own auto-resume trigger. This proves a manual resume() retires an armed watcher
    // on its own. A manual click racing an armed watcher can never leave two pollers on the same
    // job.
    // A long poll interval keeps the watcher itself from racing to auto-resume before the manual
    // resume() below runs. This test is only about the manual path disarming it.
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

    // Proves watch mode survives a restart: nothing calls cull()/resume() on this Pipeline
    // instance for the job at all. armWatchesForExistingWaitingJobs() (Pipeline's own
    // @PostConstruct, called directly here since this test has no Spring context) has to discover
    // it on disk instead. It arms a watcher purely from waitingJobs(), the same as it would after
    // a real app restart.
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

    // A short watchTimeout with no shard ever dropped: the watcher must give up on its own, with
    // no auto-resume attempt. Every dropped shard - there are none here - stays untouched, exactly
    // the "drops back to manual, all work preserved" contract from watchTimeout's own doc. Manual
    // resume must still work afterward, proving the job itself was never touched by the timeout.
    @Test
    void watchModeGivesUpAfterTimeoutWithoutTouchingTheWaitingJob(@TempDir Path root) throws IOException {
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(Duration.ofMillis(60)),
                List.of(new ManualModeCuller()), Duration.ofMillis(10));
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();

        // Polls for the real signal: the watcher actually stopping itself once the timeout fires.
        waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
        assertThat(Files.exists(photo)).isTrue();

        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(photo)).isFalse();
    }
}
