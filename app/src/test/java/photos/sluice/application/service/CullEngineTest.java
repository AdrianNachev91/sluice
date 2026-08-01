package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.assertHoldsFor;
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
import static photos.sluice.application.service.PipelineTestSupport.prepDirRemedies;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.waitUntil;
import static photos.sluice.application.service.PipelineTestSupport.watchCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.watchPipeline;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

class CullEngineTest {

    @Test
    void cullReturnsWaitingWithAnEmptyTallyWhenNoShardsHaveBeenDropped(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        final CullJobOutcome outcome = cullPipeline(root, progress).cull(new CullScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        final WaitingCullJob job = ((CullJobOutcome.Waiting) outcome).job();
        assertThat(job.scope()).isEqualTo("2019");
        assertThat(job.shards()).isEqualTo(new ShardTally(0, 0, 1));
        assertThat(Files.exists(job.prepDir().resolve("index.json"))).isTrue();
    }

    @Test
    void cullBracketsPreppingAndCullingPhasesButNeverReachesApplyingWhenWaiting(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
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
    void cullRefusesToRebuildAScopeThatAlreadyHasAWaitingJob(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2019");
        // The dropped shard must have survived the refused call - proves no rebuild/clear happened.
        assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
    }

    @Test
    void waitingJobsIsEmptyWhenNoCullHasEverRun(@TempDir final Path root) {
        assertThat(cullPipeline(root, new RecordingProgressPort()).waitingJobs()).isEmpty();
    }

    @Test
    void waitingJobsListsAPrepDirStillMissingShards(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();

        final List<WaitingCullJob> waiting = pipeline.waitingJobs();

        assertThat(waiting).hasSize(1);
        assertThat(waiting.getFirst().scope()).isEqualTo("2019");
        assertThat(waiting.getFirst().shards()).isEqualTo(new ShardTally(0, 0, 1));
    }

    @Test
    void resumeAppliesOnceAValidShardIsDropped(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        final var applied = (CullJobOutcome.Applied) outcome;
        assertThat(applied.applyReport().byCategory()).containsEntry("junk", 1);
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    @Test
    void resumeBracketsTheApplyingPhaseOnTheAppliedPath(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var progress = new RecordingProgressPort();
        final var pipeline = cullPipeline(root, progress);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        progress.events.clear();

        pipeline.resume(prepDir, false).join();

        assertThat(progress.events).containsExactly(
                "started:Applying decisions...", "tick:Applying decisions...:1/1", "finished:Applying decisions...");
    }

    // The shard set is complete, so the culling agent has said everything it is going to say.
    // NeverCalledCuller fails the test outright if the resume enters a culler at all. The absent
    // "Culling..." bracket is the second half of the same proof: no phase ran for it either.
    @Test
    void resumeGoesStraightToApplyOnceEveryMontageHasAShard(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var preparing = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) preparing.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        final var progress = new RecordingProgressPort();
        final var resuming = cullPipeline(root, progress, defaultCullSettings(), List.of(new NeverCalledCuller()));

        final CullJobOutcome outcome = resuming.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Culling"));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // The one fresh cull that skips dispatch. A scope with nothing reviewable in it still writes a
    // prep dir, with an empty montage list, so there is genuinely nothing for a culler to judge.
    // NeverCalledCuller proves none is entered; the run still completes rather than parking.
    @Test
    void cullOverAScopeWithNoMontagesAppliesWithoutEnteringACuller(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();
        final var pipeline = cullPipeline(root, progress, defaultCullSettings(), List.of(new NeverCalledCuller()));

        final CullJobOutcome outcome = pipeline.cull(new CullScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Culling"));
    }

    // The findings list crosses a port boundary. A caller still holding the list it passed in must
    // not be able to edit the outcome afterwards.
    @Test
    void blockedCopiesItsFindingsSoALaterMutationCannotReachTheOutcome() {
        final List<Finding> mutable = new ArrayList<>(List.of(new Finding.CorruptShard("montage-001",
                "decisions-001.json")));
        final var job = new WaitingCullJob("2019", Path.of("prep"), new ShardTally(1, 1, 1), Instant.EPOCH);

        final var blocked = new CullJobOutcome.Blocked(job, mutable);
        mutable.clear();

        assertThat(blocked.findings()).containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
    }

    // A complete shard set that apply refuses is the user's move, not the agent's, so it resolves
    // to Blocked rather than throwing out of the job. The findings travel with it, so a run card
    // renders the same list the troubleshoot screen does.
    @Test
    void resumeLandsBlockedCarryingTheFindingsWhenApplyRefusesACompleteShardSet(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        // A file no montage ever showed, and whose basename matches no in-scope file either, so no
        // unique-basename heal can pull it back into scope.
        writeShard(prepDir, "montage-001",
                classificationJson(root.resolve("never-in-scope.jpg"), "junk", "blurry"));

        final CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Blocked.class);
        final var blocked = (CullJobOutcome.Blocked) outcome;
        assertThat(blocked.job().prepDir()).isEqualTo(prepDir);
        assertThat(blocked.findings()).singleElement().isInstanceOf(Finding.FileOutOfScope.class);
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
    }

    // The shape a watcher and a resume could otherwise re-trigger each other on. The shard has
    // arrived and parses, so readiness says go every time it is asked. Only the whole-batch gate
    // can see the decision's file is gone with no move record. Blocked disarms the watch: every
    // montage has a shard, so nothing is left for a poller to notice.
    @Test
    void anApplyRefusalDisarmsTheWatchInsteadOfLeavingAPollerRunning(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.delete(photo);

        final CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Blocked.class);
        assertThat(((CullJobOutcome.Blocked) outcome).findings())
                .singleElement().isInstanceOf(Finding.MissingSource.class);
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    @Test
    void resumeReturnsWaitingAgainWithAnUpdatedTallyWhenAMontageStillLacksAShard(@TempDir final Path root) throws IOException {
        final Path juneDir = sortedPhotosDir(root, "2019", "06");
        final Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(waiting.job().shards()).isEqualTo(new ShardTally(0, 0, 2));
        writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

        final CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        assertThat(((CullJobOutcome.Waiting) outcome).job().shards()).isEqualTo(new ShardTally(1, 1, 2));
        assertThat(Files.exists(a)).isTrue();
    }

    @Test
    void resumeWithAllowPartialAppliesWhatItHasAndLeavesTheMissingMontagesPhotoInPlace(@TempDir final Path root) throws IOException {
        final Path juneDir = sortedPhotosDir(root, "2019", "06");
        final Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final Path b = writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

        final CullJobOutcome outcome = pipeline.resume(prepDir, true).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(a)).isFalse();
        assertThat(Files.exists(b)).isTrue();
    }

    // The design reason Pipeline distinguishes providers at all. An automated provider's shards never
    // arrive externally, so nothing more is coming on its own. Its CullException is a genuine failure
    // and must propagate, not quietly park the job as "waiting" like the external-agent provider's
    // identical checked exception does.
    @Test
    void cullPropagatesAFailureFromAnAutomatedProviderInsteadOfReturningWaiting(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var progress = new RecordingProgressPort();
        final var settings = new FixedSettings("anthropic", List.of(new CullCategory("junk", "objectively worthless " +
                "shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
        final var pipeline = cullPipeline(root, progress, settings, List.of(new ThrowingCuller("anthropic")));

        final var handle = pipeline.cull(new CullScope.Year(2019, null));

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
    void cullCancelledMidDispatchResolvesToWaitingThenResumeCompletes(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var firstShardWritten = new CountDownLatch(1);
        final var releaseCull = new CountDownLatch(1);
        final var settings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH));
        final var pipeline = cullPipeline(root, new RecordingProgressPort(), settings,
                List.of(new BlockingCancellableCuller(firstShardWritten, releaseCull)));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        firstShardWritten.await();
        handle.requestCancellation();
        releaseCull.countDown();
        final CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        final WaitingCullJob job = ((CullJobOutcome.Waiting) outcome).job();
        final Path prepDir = job.prepDir();
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isFalse();
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();

        final CullJobOutcome resumed = pipeline.resume(prepDir, false).join();

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
    void armWatchesForExistingWaitingJobsNeverArmsAWatcherForAnAutomatedProvidersWaitingJob(@TempDir final Path root)
            throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var firstShardWritten = new CountDownLatch(1);
        final var releaseCull = new CountDownLatch(1);
        final var manualSettings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
        final var manualPipeline = cullPipeline(root, new RecordingProgressPort(), manualSettings,
                List.of(new BlockingCancellableCuller(firstShardWritten, releaseCull)));

        final JobHandle<CullJobOutcome> handle = manualPipeline.cull(new CullScope.Year(2019, null));
        firstShardWritten.await();
        handle.requestCancellation();
        releaseCull.countDown();
        final var waiting = (CullJobOutcome.Waiting) handle.join();
        final Path prepDir = waiting.job().prepDir();

        final var watchSettings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH));
        final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchSettings, List.of(),
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
    void cullCancelledMidRenderResolvesToCancelledWithNoPrepDirEverWritten(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var listStarted = new CountDownLatch(1);
        final var releaseList = new CountDownLatch(1);
        final var mediaStore = new BlockingListFiles(listStarted, releaseList);
        final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, defaultCullSettings(),
                List.of(new NeverCalledCuller()));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        listStarted.await();
        handle.requestCancellation();
        releaseList.countDown();
        final CullJobOutcome outcome = handle.join();

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
    void cullCancelledMidApplyResolvesToWaitingWithDecisionsJsonNeverWritten(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var mediaStore = new BlockingMoveTo(moveStarted, releaseMove);
        final var settings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
        final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, settings,
                List.of(new JunkEverythingCuller()));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        final CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        final Path prepDir = ((CullJobOutcome.Waiting) outcome).job().prepDir();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        // Exactly one of the two photos was fully processed (moved + recorded) before the
        // cancellation stopped the loop; scan order between them isn't guaranteed.
        try (final var junked = Files.list(root.resolve("Review/junk"))) {
            assertThat(junked.filter(p -> p.getFileName().toString().startsWith("IMG_")).count()).isEqualTo(1);
        }
        try (final var remaining = Files.list(sortedPhotosDir(root, "2019", "06"))) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    // The manual-mode CullException branch must not arm a watcher when cancellation raced
    // it - an auto-resume moments after a cancel would defy the cancel. Watch mode and the
    // external-agent provider are both genuinely configured here, so an uncancelled run of this
    // same pause would arm a watcher (see cullInWatchModeAutoResumesOnceAValidShardIsDropped below).
    // This proves the simultaneous cancellation is what suppresses it, not just the settings.
    @Test
    void manualModePauseDoesNotArmAWatcherWhenCancellationRacedIt(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new BlockingIncompleteCuller(started, release)), Duration.ofMillis(20));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        started.await();
        handle.requestCancellation();
        release.countDown();
        final CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        final Path prepDir = ((CullJobOutcome.Waiting) outcome).job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    @Test
    void cullInWatchModeAutoResumesOnceAValidShardIsDropped(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();

        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        // Waits on waitingJobs() itself, not just the photo's move. apply() writes decisions.json -
        // what waitingJobs() actually checks for - only after every decision's file is moved.
        // Polling the move alone leaves a real window where the photo is gone but the job still
        // reads as waiting. A CI runner slow/loaded enough to land inside that window flaked here.
        waitUntil(Duration.ofSeconds(2), () -> pipeline.waitingJobs().isEmpty());
        assertThat(Files.exists(photo)).isFalse();
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // Regression: disarmWatch() runs at the top of every dispatchAndApply() call, not just the
    // watcher's own auto-resume trigger. This proves a manual resume() retires an armed watcher
    // on its own. A manual click racing an armed watcher can never leave two pollers on the same
    // job.
    // A long poll interval keeps the watcher itself from racing to auto-resume before the manual
    // resume() below runs. This test is only about the manual path disarming it.
    @Test
    void manualResumeDisarmsAnAlreadyArmedWatcher(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        final CullJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    // Proves watch mode survives a restart: nothing calls cull()/resume() on this Pipeline
    // instance for the job at all. armWatchesForExistingWaitingJobs() (Pipeline's own
    // @PostConstruct, called directly here since this test has no Spring context) has to discover
    // it on disk instead. It arms a watcher purely from waitingJobs(), the same as it would after
    // a real app restart.
    @Test
    void armWatchesForExistingWaitingJobsAutoResumesAJobItNeverStartedItself(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var manualPipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) manualPipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        watchPipeline.armWatchesForExistingWaitingJobs();

        waitUntil(Duration.ofSeconds(2), () -> !Files.exists(photo));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // The per-run watch toggle's on position, against a manual-mode config. Watching one run is the
    // user's own decision about that run, so it does not need the config's blessing. Nothing else
    // in this test arms anything: cull() ran under plain manual settings, which is exactly what the
    // pre-toggle assertion pins.
    @Test
    void startWatchingArmsOneRunEvenWhileTheConfiguredModeIsManual(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isFalse();

        pipeline.startWatching(prepDir);
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        waitUntil(Duration.ofSeconds(2), () -> pipeline.waitingJobs().isEmpty());
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // The toggle's off position. Turning it off costs the run nothing: it is still waiting, still
    // listed, and still refuses a fresh cull of the same scope. Only the polling stops.
    @Test
    void stopWatchingLeavesTheRunWaitingAndStillBlockingAReCullOfItsScope(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();

        pipeline.stopWatching(prepDir);

        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
        assertThat(pipeline.waitingJobs()).singleElement()
                .extracting(WaitingCullJob::prepDir).isEqualTo(prepDir);
        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    // The provider gate holds even for an explicit per-run arm. An automated provider's shards are
    // written by this app itself, so nothing arrives from outside for a poller to notice. A
    // fully-valid tally would only trigger an unasked-for round of paid API calls.
    @Test
    void startWatchingRefusesAnAutomatedProvidersRun(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var firstShardWritten = new CountDownLatch(1);
        final var releaseCull = new CountDownLatch(1);
        final var settings = new FixedSettings("auto-approve",
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), settings,
                List.of(new BlockingCancellableCuller(firstShardWritten, releaseCull)), Duration.ofMillis(20));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        firstShardWritten.await();
        handle.requestCancellation();
        releaseCull.countDown();
        final Path prepDir = ((CullJobOutcome.Waiting) handle.join()).job().prepDir();

        pipeline.startWatching(prepDir);

        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    // A prep dir whose index.json cannot be read at all is skipped, rather than failing the whole
    // scan. waitingJobs() sits on hot paths: startup arming, and every cull()/curate()'s own
    // scope-conflict check. One damaged dir must not be able to take those down with it.
    // Corrupting a sidecar or a shard would not reach this catch. The tally already folds both into
    // its own counts, so only a failed index read gets there.
    @Test
    void waitingJobsSkipsAPrepDirWithAnUnreadableIndexAndStillListsTheHealthyOne(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2020", "06"), "IMG_2.jpg", Instant.parse("2020-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var damaged = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final var healthy = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2020, null)).join();
        Files.writeString(damaged.job().prepDir().resolve("index.json"), "{ not json at all");

        final List<WaitingCullJob> waiting = pipeline.waitingJobs();

        assertThat(waiting).singleElement().extracting(WaitingCullJob::prepDir).isEqualTo(healthy.job().prepDir());
    }

    // The tally's own tolerance, one layer down from the index. Neither an unreadable sidecar nor
    // an unparseable shard may fail the scan either. A shard that is there but cannot be parsed is
    // present and not valid, which is exactly what a run card needs to say to be useful.
    @Test
    void aPresentButUnparseableShardTalliesAsPresentAndInvalidRatherThanFailingTheScan(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        Files.writeString(prepDir.resolve("montage-001.json"), "{ not json at all");
        Files.writeString(prepDir.resolve("decisions-001.json"), "{ not json at all");

        assertThat(pipeline.waitingJobs()).singleElement()
                .extracting(WaitingCullJob::shards).isEqualTo(new ShardTally(1, 0, 1));
    }

    // A shard naming a file this platform cannot make a path out of is the culling agent's own
    // content mistake, so it reads as an unparseable shard. Left as a raw InvalidPathException it
    // would escape every read-failure catch in the tally, killing the poll that raised it and, with
    // it, the watch. The name below carries a NUL character, which no mainstream filesystem accepts.
    @Test
    void aShardNamingAnUnusableFileTalliesAsPresentAndInvalid(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        Files.writeString(prepDir.resolve("decisions-001.json"),
                "{ \"montage\": \"montage-001\", \"decisions\": [ { \"file\": \"bad\\u0000name.jpg\", "
                        + "\"action\": \"junk\", \"reason\": \"blurry\" } ] }");

        assertThat(pipeline.waitingJobs()).singleElement()
                .extracting(WaitingCullJob::shards).isEqualTo(new ShardTally(1, 0, 1));
    }

    // The load-bearing one for readiness. A stray shard is the whole class of problem only the
    // whole-batch gate can see, so nothing a watcher could ask about disk state will ever notice
    // it. Readiness therefore asks whether everything has arrived, not whether it is any good: the
    // run resumes once, apply refuses, and Blocked puts the finding in front of somebody. A
    // readiness check clever enough to see the stray shard itself would be the failure. It would
    // withhold that resume forever, while the per-montage tally reads a healthy 1/1 throughout.
    @Test
    void watchModeResumesAStrayShardIntoBlockedRatherThanPollingOnForever(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        // montage-002 exists in no index, so its shard belongs to no montage at all.
        writeShard(prepDir, "montage-002", classificationJson(photo, "junk", "blurry"));

        // The watcher firing is what retires it, so an inactive watch proves the resume ran.
        waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));

        assertThat(pipeline.waitingJobs()).singleElement()
                .extracting(WaitingCullJob::shards).isEqualTo(new ShardTally(1, 1, 1));
        assertThat(Files.exists(photo)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
    }

    // A shard file exists from the moment the agent opens it for writing. Presence alone would then
    // fire on a half-written one and block the run over a file that was seconds from being fine.
    // Parsing is what tells the two apart. The truncated shard below is what a poll landing
    // mid-write sees.
    @Test
    void watchModeWaitsRatherThanResumingWhileAShardIsStillHalfWritten(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        Files.writeString(prepDir.resolve("decisions-001.json"), "{ \"montage\": \"montage-001\", \"decis");

        // A fired watcher retires itself, so staying armed across a window many poll intervals wide
        // is the proof it never fired. Checked continuously rather than once at the end, so a
        // watcher that fired and stopped mid-window cannot slip through.
        assertHoldsFor(Duration.ofMillis(200), () -> pipeline.isWatchActive(prepDir));
        assertThat(Files.exists(photo)).isTrue();

        // The same shard, now complete, is what the next tick sees - so the wait was the file's
        // state, never a watcher that had quietly died.
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        waitUntil(Duration.ofSeconds(2), () -> pipeline.waitingJobs().isEmpty());
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // A resolved corrupt sidecar takes effect at apply's own gate, which is the only place the
    // disposition ledger is ever read. Watch mode reaches it the ordinary way: every montage has a
    // parseable shard, so the run resumes and the answer applies.
    @Test
    void watchModeAppliesAMontageWhoseCorruptSidecarTheUserResolvedWithApplyAnyway(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        // Manual settings, so cull() arms nothing and the whole fixture can be built with no
        // watcher running against it. The per-run toggle below is what starts the polling.
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.writeString(prepDir.resolve("montage-001.json"), "{ not json at all");
        prepDirRemedies(root).resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.APPLY_ANYWAY,
                "the shard itself is fine");

        pipeline.startWatching(prepDir);

        waitUntil(Duration.ofSeconds(2), () -> pipeline.waitingJobs().isEmpty());
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }
}
