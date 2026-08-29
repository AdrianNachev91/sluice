package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.ChoiceAnswer;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.rescue.RescueSummary;

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
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.FailingMoves;
import static photos.sluice.application.service.PipelineTestSupport.AutoApproveCuller;
import static photos.sluice.application.service.PipelineTestSupport.ManualModeCuller;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.autoApproveCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.classificationJson;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.padded;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.watchCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.watchPipeline;
import static photos.sluice.application.service.PipelineTestSupport.writeFile;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

class PipelineTest {

    @Test
    void theInboxTallyCountsMediaAndLeavesEverythingWhereItFoundIt(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        final Path photo = inboxOf(root).resolve("20210315_photo.jpg");
        writeFile(photo, padded("keeper"));
        writeFile(inboxOf(root).resolve("notes.txt"), padded("not media"));

        assertThat(pipeline(root, progress).inboxTally().files()).isEqualTo(1);
        assertThat(photo).exists();
        assertThat(progress.events).isEmpty();
    }

    @Test
    void theSortedTallyReportsAYearThatHasBeenStagedIntoIt(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(sortedPhotosDir(root, "2019", "06").resolve("a.jpg"), padded("staged"));

        assertThat(pipeline(root, progress).sortedTally().years())
                .singleElement()
                .satisfies(year -> {
                    assertThat(year.year()).isEqualTo(2019);
                    assertThat(year.photos()).isEqualTo(1);
                });
    }

    @Test
    void aScopeCostsNothingToSiftThroughAProviderThatCallsNoModel(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();

        assertThat(cullPipeline(root, progress).estimateFor(100).totalTokens()).isZero();
    }

    @Test
    void aScopeSiftedThroughAProviderThatCallsAModelIsSizedOnItsPhotoCount(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();
        final Pipeline pipeline =
                cullPipeline(root, progress, autoApproveCullSettings(), List.of(new AutoApproveCuller()));

        final long hundred = pipeline.estimateFor(100).totalTokens();

        assertThat(hundred).isPositive();
        assertThat(pipeline.estimateFor(200).totalTokens()).isEqualTo(hundred * 2);
    }

    @Test
    void aProviderThatCallsNoModelIsReportedAsSpendingNothing(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();

        assertThat(cullPipeline(root, progress).configuredProviderSpends()).isFalse();
    }

    @Test
    void aProviderThatCallsAModelIsReportedAsAbleToSpend(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();

        assertThat(cullPipeline(root, progress, autoApproveCullSettings(), List.of(new AutoApproveCuller()))
                .configuredProviderSpends()).isTrue();
    }

    @Test
    void sortRunsSortEngineAndReturnsItsSummary(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(inboxOf(root).resolve("20210315_photo.jpg"), padded("keeper"));

        final SortSummary summary = pipeline(root, progress).sort(new SortScope.OldestYear()).join();

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Sorted/Photos/2021/03/20210315_photo.jpg"))).isTrue();
    }

    @Test
    void sortReportsEachOfItsThreeStagesWithACountOfItsOwn(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(inboxOf(root).resolve("20210315_a.jpg"), padded("a"));
        writeFile(inboxOf(root).resolve("20210316_b.jpg"), padded("b"));

        pipeline(root, progress).sort(new SortScope.OldestYear()).join();

        assertThat(progress.events).containsExactly(
                "started:Finding dates...", "tick:Finding dates...:1/2", "tick:Finding dates...:2/2",
                "finished:Finding dates...",
                "started:Checking for duplicates...", "tick:Checking for duplicates...:1/2",
                "tick:Checking for duplicates...:2/2", "finished:Checking for duplicates...",
                "started:Sorting...", "tick:Sorting...:1/2", "tick:Sorting...:2/2", "finished:Sorting...");
    }

    // Proves cancellation reaches SortEngine's own mid-routing check through Pipeline's real
    // handle.stopSignal() wiring, not just through a hand-built CancellationSignal -
    // SortEngineTest already covers SortEngine's own cancellation semantics directly. BlockingMoves
    // synchronizes the request with the exact moment the first file's move is in flight, so it lands
    // mid-pass rather than before the pass even starts.
    @Test
    void sortStopsMidRoutingWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir final Path root) throws Exception {
        writeFile(inboxOf(root).resolve("20210101_a.jpg"), padded("a"));
        writeFile(inboxOf(root).resolve("20210102_b.jpg"), padded("b"));
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        final JobHandle<SortSummary> handle = pipeline.sort(new SortScope.OldestYear());
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        final SortSummary summary = handle.join();

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.photosSorted()).isEqualTo(1);
        try (final var sorted = Files.list(root.resolve("Sorted/Photos/2021/01"))) {
            assertThat(sorted.count()).isEqualTo(1);
        }
        try (final var remaining = Files.list(inboxOf(root))) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    @Test
    void commitRunsCommitEngineAndReturnsItsSummary(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        final CommitSummary summary = pipeline(root, progress).commit(new CommitScope.All()).join();

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Library/Photos/2019/06/a.jpg"))).isTrue();
    }

    @Test
    void commitBracketsProgressEventsAroundTheCommitPhase(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/07/b.jpg"), "b");

        pipeline(root, progress).commit(new CommitScope.All()).join();

        assertThat(progress.events).containsExactly(
                "started:Moving to library...", "tick:Moving to library...:1/2", "tick:Moving to library...:2/2", "finished:Moving to library...");
    }

    @Test
    void rescueRunsRescueEngineAndReturnsItsSummary(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        final RescueSummary summary = pipeline(root, progress).rescue("2019-06").join();

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(root.resolve("Library/Photos/2019/06/IMG_1.jpg"))).isTrue();
    }

    @Test
    void rescueBracketsProgressEventsAroundTheRescuePhase(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
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
    void phaseFinishedFiresEvenWhenTheEngineThrows(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        final var handle = pipeline(root, progress, new FailingMoves()).rescue("2019-06");

        assertThatThrownBy(handle::join).isInstanceOf(CompletionException.class);
        assertThat(progress.events).containsExactly("started:Rescuing...", "finished:Rescuing...");
    }

    @Test
    void commitStopsMidMoveLoopWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir final Path root) throws Exception {
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/07/b.jpg"), "b");
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        final JobHandle<CommitSummary> handle = pipeline.commit(new CommitScope.All());
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        final CommitSummary summary = handle.join();

        assertThat(summary.committed()).isEqualTo(1);
        try (final var remaining = Files.walk(root.resolve("Sorted")).filter(Files::isRegularFile)) {
            assertThat(remaining.count()).isEqualTo(1);
        }
    }

    @Test
    void rescueStopsMidMoveLoopWhenCancellationIsRequestedWhileAFileIsInFlight(@TempDir final Path root) throws Exception {
        writeFile(root.resolve("Review/2019-06/a.jpg"), "a");
        writeFile(root.resolve("Review/2019-06/b.jpg"), "b");
        final Path reasonsFile = root.resolve("Review/2019-06/_reasons.txt");
        writeFile(reasonsFile, "b.jpg - low-res");
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));

        final JobHandle<RescueSummary> handle = pipeline.rescue("2019-06");
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        final RescueSummary summary = handle.join();

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).isEmpty();
        // The dissolve gate keeps the folder: the pass never reached every entry, so this marker
        // must survive even though nothing it did reach was skipped.
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();
        assertThat(Files.exists(reasonsFile)).isTrue();
    }

    @Test
    void sweepExpiredDisasterDrawersDeletesOnlyRetentionExpiredEntries(@TempDir final Path root) throws IOException {
        final Path drawer = root.resolve("logs/sift-prep/2019-06/disasters");
        final Path oldEntry = drawer.resolve("2019-01-01_00-00-00-move-records-log.log");
        writeFile(oldEntry, "old");
        final Path freshEntry = drawer.resolve("2099-01-01_00-00-00-move-records-log.log");
        writeFile(freshEntry, "fresh");

        pipeline(root, new RecordingProgressPort()).sweepExpiredDisasterDrawers();

        assertThat(Files.exists(oldEntry)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
    }

    // Pipeline.sweepExpiredDisasterDrawers() sweeps two places: every per-prep-dir drawer, and the
    // global graveyard folders PrepDirRemedies.discard() writes. DisasterDrawerTest already covers
    // sweepExpiredGraveyard()'s own logic in full, so this only needs one expired and one fresh
    // graveyard folder to prove the wiring reaches it too.
    @Test
    void sweepExpiredDisasterDrawersAlsoSweepsTheDiscardGraveyard(@TempDir final Path root) throws IOException {
        final Path oldGraveyard = root.resolve("logs/archives/scope1-2019-01-01_00-00-00");
        writeFile(oldGraveyard.resolve("index.json"), "{}");
        final Path freshEntry = root.resolve("logs/archives/scope1-2099-01-01_00-00-00/index.json");
        writeFile(freshEntry, "{}");

        pipeline(root, new RecordingProgressPort()).sweepExpiredDisasterDrawers();

        assertThat(Files.exists(oldGraveyard)).isFalse();
        assertThat(Files.exists(freshEntry)).isTrue();
    }

    // Proves troubleshoot() actually runs through JobRunner rather than calling Troubleshooter
    // directly. TroubleshooterTest already covers the diagnose/reconcile/report logic itself in
    // full, so this only needs one real prep dir to prove the wiring returns its report.
    @Test
    void troubleshootRunsAsABackgroundJobAndReturnsTheReport(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, progress);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        pipeline.resume(prepDir, false).join();

        final TroubleshootReport report = pipeline.troubleshoot(prepDir).join();

        assertThat(report.before().state()).isEqualTo(State.COMPLETE);
        assertThat(report.reconcile()).isNull();
    }

    // Proves purgeCompleted() actually runs through JobRunner and reaches PrepDirDoctor, rather
    // than being wired to nothing. PrepDirDoctorTest already covers purgeCompleted()'s own
    // diagnose/delete logic in full, so this only needs one completed run to prove the wiring
    // deletes it.
    @Test
    void purgeCompletedRunsAsABackgroundJobAndDeletesTheCompletedRun(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, progress);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        pipeline.resume(prepDir, false).join();

        final PurgeReport report = pipeline.purgeCompleted().join();

        assertThat(report.purged()).containsExactly(prepDir.getFileName().toString());
        assertThat(Files.exists(prepDir)).isFalse();
    }

    // One case per variant, because answer()'s switch is the whole of its behaviour and an arm
    // wired to the wrong remedy would still compile.
    @Test
    void everyAnswerReachesTheRemedyItNames(@TempDir final Path root) throws IOException {
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        final Path gone = root.resolve("Sorted/Photos/2019/06/gone.jpg");
        final Path overlapping = root.resolve("Sorted/Photos/2019/06/overlapping.jpg");
        final Path stray = prepDir.resolve("decisions-999.json");
        writeFile(stray, "{}");

        pipeline.answer(prepDir, new ChoiceAnswer.SkipMissingSource(gone), AnswerSource.DESKTOP);
        pipeline.answer(prepDir, new ChoiceAnswer.ResolveOverlap(overlapping, OverlapResolution.TRUST_DECISION),
                AnswerSource.DESKTOP);
        pipeline.answer(prepDir, new ChoiceAnswer.ResolveCorruptSidecar("montage-001",
                CorruptSidecarResolution.SET_ASIDE), AnswerSource.DESKTOP);
        pipeline.answer(prepDir, new ChoiceAnswer.SetAsideStrayShard(new Finding.StrayShard("decisions-999.json")),
                AnswerSource.DESKTOP);

        final String choices = Files.readString(prepDir.resolve("choices.log"));
        assertThat(choices).contains(gone.toString(), overlapping.toString(), "montage-001");
        assertThat(choices.lines()).allMatch(line -> line.endsWith("DESKTOP"));
        assertThat(Files.exists(stray)).isFalse();
    }

    @Test
    void anAnswerLandsWhileAJobIsStillRunning(@TempDir final Path root) throws Exception {
        writeFile(inboxOf(root).resolve("20210101_a.jpg"), padded("a"));
        writeFile(inboxOf(root).resolve("20210102_b.jpg"), padded("b"));
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove));
        final Path prepDir = root.resolve("logs/sift-prep/2019");
        writeFile(prepDir.resolve("index.json"), "{}");
        final JobHandle<SortSummary> sorting = pipeline.sort(new SortScope.OldestYear());
        moveStarted.await();

        pipeline.answer(prepDir, new ChoiceAnswer.SkipMissingSource(root.resolve("Sorted/gone.jpg")),
                AnswerSource.DESKTOP);

        assertThat(Files.readString(prepDir.resolve("choices.log")).strip()).endsWith("DESKTOP");
        releaseMove.countDown();
        sorting.join();
    }

    @Test
    void anUnreadableSpendLedgerIsFiledIntoTheArchivesRatherThanDeleted(@TempDir final Path root) throws IOException {
        final Path ledger = root.resolve("logs/spend-ledger.csv");
        writeFile(ledger, "not a ledger line at all");
        final var pipeline = pipeline(root, new RecordingProgressPort());

        final Path filed = pipeline.setAsideUnreadableSpendLedger();

        assertThat(filed).isNotNull().hasParent(root.resolve("logs/archives"));
        assertThat(filed).hasContent("not a ledger line at all");
        assertThat(Files.exists(ledger)).isFalse();
    }

    @Test
    void filingAwayASpendLedgerThatIsNotThereAnswersThatThereWasNone(@TempDir final Path root) {
        assertThat(pipeline(root, new RecordingProgressPort()).setAsideUnreadableSpendLedger()).isNull();
    }

    // The repair exists for a record no parser accepts. Asked about a healthy one it has to leave
    // it alone. This is the user's only history of what past sifts cost, and filing it away
    // degrades every later estimate to the shipped seed.
    @Test
    void aSpendLedgerThatReadsIsLeftWhereItIs(@TempDir final Path root) throws IOException {
        final String recorded = "\"2026-08-22T10:00:00Z\",\"2018\",\"anthropic\",\"claude-sonnet-5\","
                + "\"224\",\"5\",\"28\",\"0\",\"1\",\"148231\",\"24800\",\"APPLIED\"";
        final Path ledger = root.resolve("logs/spend-ledger.csv");
        writeFile(ledger, recorded);

        final Path filed = pipeline(root, new RecordingProgressPort()).setAsideUnreadableSpendLedger();

        assertThat(filed).isNull();
        assertThat(ledger).hasContent(recorded);
    }

    // Proves discard() actually runs through JobRunner and reaches PrepDirRemedies.discard().
    // PrepDirRemediesTest already covers the graveyard-filing/image-deletion logic itself in full,
    // so this only needs a still-waiting prep dir to prove the wiring returns its report.
    @Test
    void discardRunsAsABackgroundJobAndFilesEverythingIntoTheGraveyard(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, progress);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();

        final DiscardReport report = pipeline.discard(prepDir).join();

        assertThat(report.graveyard().getParent()).isEqualTo(root.resolve("logs/archives"));
        assertThat(Files.exists(report.graveyard().resolve("index.json"))).isTrue();
        assertThat(Files.exists(prepDir)).isFalse();
    }

    // Refusing a COMPLETE run is the gate PrepDirRemedies.discard() itself deliberately doesn't apply -
    // purgeCompleted() is that state's own verb, not discard().
    @Test
    void discardRefusesACompletedRun(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, progress);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        pipeline.resume(prepDir, false).join();

        assertThatThrownBy(() -> pipeline.discard(prepDir).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(prepDir)).isTrue();
    }

    // Regression: a still-armed watcher must never fire an auto-resume against a prep dir mid- or
    // post-discard. disarmWatch() itself is proven in isolation by CullEngineTest's own
    // manualResumeDisarmsAnAlreadyArmedWatcher; this proves Pipeline.discard() actually calls it.
    @Test
    void discardDisarmsAnAlreadyArmedWatcher(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();

        pipeline.discard(prepDir).join();

        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }
}
