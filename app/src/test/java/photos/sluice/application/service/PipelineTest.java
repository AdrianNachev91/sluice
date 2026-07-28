package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.FailingMoves;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.classificationJson;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.padded;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.writeFile;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

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

    // Proves cancellation reaches SortEngine's own mid-routing check through Pipeline's real
    // handle::isCancellationRequested wiring, not just through a hand-built CancellationSignal -
    // SortEngineTest already covers SortEngine's own cancellation semantics directly. BlockingMoves
    // synchronizes the request with the exact moment the first file's move is in flight, so it lands
    // mid-pass rather than before the pass even starts.
    @Test
    void sortStopsMidRoutingWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir Path root) throws Exception {
        writeFile(inboxOf(root).resolve("20210101_a.jpg"), padded("a"));
        writeFile(inboxOf(root).resolve("20210102_b.jpg"), padded("b"));
        var moveStarted = new CountDownLatch(1);
        var releaseMove = new CountDownLatch(1);
        var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        JobHandle<SortSummary> handle = pipeline.sort(new SortScope.OldestYear());
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        SortSummary summary = handle.join();

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        try (var sorted = Files.list(root.resolve("Sorted/Photos/2021/01"))) {
            assertThat(sorted.count()).isEqualTo(1);
        }
        try (var remaining = Files.list(inboxOf(root))) {
            assertThat(remaining.count()).isEqualTo(1);
        }
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
    void commitStopsMidMoveLoopWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir Path root) throws Exception {
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/07/b.jpg"), "b");
        var moveStarted = new CountDownLatch(1);
        var releaseMove = new CountDownLatch(1);
        var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        JobHandle<CommitSummary> handle = pipeline.commit(new CommitScope.All());
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        CommitSummary summary = handle.join();

        assertThat(summary.committed()).isEqualTo(1);
        try (var remaining = Files.walk(root.resolve("Sorted")).filter(Files::isRegularFile)) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    @Test
    void rescueStopsMidMoveLoopWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir Path root) throws Exception {
        writeFile(root.resolve("Review/2019-06/a.jpg"), "a");
        writeFile(root.resolve("Review/2019-06/b.jpg"), "b");
        Path reasonsFile = root.resolve("Review/2019-06/_reasons.txt");
        writeFile(reasonsFile, "b.jpg - low-res");
        var moveStarted = new CountDownLatch(1);
        var releaseMove = new CountDownLatch(1);
        var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        JobHandle<RescueSummary> handle = pipeline.rescue("2019-06");
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        RescueSummary summary = handle.join();

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).isEmpty();
        // The dissolve gate keeps the folder: the pass never reached every entry, so this marker
        // must survive even though nothing it did reach was skipped.
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();
        assertThat(Files.exists(reasonsFile)).isTrue();
    }

    // sweepExpiredDisasterDrawers() is Pipeline's own @PostConstruct, called directly here since
    // this test has no Spring context - the same pattern CullEngineTest's own
    // armWatchesForExistingWaitingJobs() tests already use.
    @Test
    void sweepExpiredDisasterDrawersDeletesOnlyRetentionExpiredEntries(@TempDir Path root) throws IOException {
        Path drawer = root.resolve("logs/cull-prep/2019-06/disasters");
        Path oldEntry = drawer.resolve("2019-01-01_00-00-00-move-records-log.log");
        writeFile(oldEntry, "old");
        Path freshEntry = drawer.resolve("2099-01-01_00-00-00-move-records-log.log");
        writeFile(freshEntry, "fresh");

        pipeline(root, new RecordingProgressPort()).sweepExpiredDisasterDrawers();

        assertThat(Files.exists(oldEntry)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
    }

    // Pipeline.sweepExpiredDisasterDrawers() (the @PostConstruct hook) sweeps both per-prep-dir
    // drawers and ApplyEngine.discard()'s global graveyard folders. DisasterDrawerTest already
    // covers sweepExpiredGraveyard()'s own logic in full, so this only needs one expired and one
    // fresh graveyard folder to prove the wiring reaches it too.
    @Test
    void sweepExpiredDisasterDrawersAlsoSweepsTheDiscardGraveyard(@TempDir Path root) throws IOException {
        Path oldGraveyard = root.resolve("logs/disasters/scope1-2019-01-01_00-00-00");
        writeFile(oldGraveyard.resolve("index.json"), "{}");
        Path freshEntry = root.resolve("logs/disasters/scope1-2099-01-01_00-00-00/index.json");
        writeFile(freshEntry, "{}");

        pipeline(root, new RecordingProgressPort()).sweepExpiredDisasterDrawers();

        assertThat(Files.exists(oldGraveyard)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
    }

    // Proves troubleshoot() actually runs through JobRunner rather than calling Troubleshooter
    // directly - TroubleshooterTest already covers the diagnose/reconcile/report logic itself in
    // full, so this only needs one real prep dir to prove the wiring returns its report.
    @Test
    void troubleshootRunsAsABackgroundJobAndReturnsTheReport(@TempDir Path root) throws IOException {
        var progress = new RecordingProgressPort();
        Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        var pipeline = cullPipeline(root, progress);
        var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        pipeline.resume(prepDir, false).join();

        TroubleshootReport report = pipeline.troubleshoot(prepDir).join();

        assertThat(report.before().state()).isEqualTo(State.COMPLETE);
        assertThat(report.reconcile()).isNull();
    }
}
