package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.BlockingListFiles;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.curatePipeline;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.writeInboxPhoto;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;

class CurateEngineTest {

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
    void curateSortsThenCullsInOneJobEndToEnd(@TempDir final Path root) throws IOException {
        final Path photo = writeInboxPhoto(root, "20190601_photo.jpg");
        final var progress = new RecordingProgressPort();

        final CurateOutcome outcome = curatePipeline(root, progress).curate(new SortScope.Year(2019, null)).join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(Files.exists(photo)).isFalse();
        final Path sorted = root.resolve("Sorted/Photos/2019/06/20190601_photo.jpg");
        assertThat(Files.exists(sorted)).isTrue();
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        final var applied = (CullJobOutcome.Applied) Objects.requireNonNull(outcome.cullOutcome());
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
    void curateWithOldestYearScopeResolvesAndCullsTheYearTheSortPicked(@TempDir final Path root) throws IOException {
        writeInboxPhoto(root, "20190601_photo.jpg");

        final CurateOutcome outcome =
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
    void curateSkipsCullWhenAnOldestYearSortFindsNothingToSort(@TempDir final Path root) throws IOException {
        Files.createDirectories(inboxOf(root));

        final CurateOutcome outcome =
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
    void curateWithOldestNScopeSortsAcrossYearsAndCullsTheSameCount(@TempDir final Path root) throws IOException {
        writeInboxPhoto(root, "20180601_a.jpg", 1);
        writeInboxPhoto(root, "20190601_b.jpg", 2);

        final CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestN(2)).join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(2);
        assertThat(outcome.sortSummary().yearsSorted()).containsExactlyInAnyOrder(2018, 2019);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        final var applied = (CullJobOutcome.Applied) Objects.requireNonNull(outcome.cullOutcome());
        assertThat(applied.applyReport().reviewed()).isEqualTo(2);
        assertThat(Files.exists(root.resolve("logs/cull-prep/oldest-2/index.json"))).isTrue();
    }

    // Mirrors curateRefusesAnExplicitYearScopeAlreadyWaitingOnShards below, for the other scope shape
    // whose CullScope is known before curate() ever submits a job.
    @Test
    void curateRefusesAnOldestNScopeAlreadyWaitingOnShards(@TempDir final Path root) throws IOException {
        Files.createDirectories(inboxOf(root));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.OldestN(1)).join();

        assertThatThrownBy(() -> pipeline.curate(new SortScope.OldestN(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oldest-1");
    }

    // The other half of monthsFromRange()'s translation. Every other curate() test passes null
    // months, so this is the only coverage for an actual MonthRange narrowing down to a specific
    // CullScope.Year(months) list.
    @Test
    void curateWithAnExplicitMonthRangeNarrowsTheCullScopeToThoseMonths(@TempDir final Path root) throws IOException {
        writeInboxPhoto(root, "20190601_june.jpg");
        writeInboxPhoto(root, "20190815_august.jpg", 3);

        final CurateOutcome outcome = curatePipeline(root, new RecordingProgressPort())
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
    void curateWithAnExplicitYearScopeCullsThatYearEvenWhenThisRunSortedNothingNew(@TempDir final Path root)
            throws IOException {
        Files.createDirectories(inboxOf(root));
        final Path existing =
                writePhoto(sortedPhotosDir(root, "2019", "06"), "already-sorted.jpg", Instant.parse("2019-06-01T10:00" +
                        ":00Z"));

        final CurateOutcome outcome =
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
    void curateRefusesAnExplicitYearScopeAlreadyWaitingOnShards(@TempDir final Path root) throws IOException {
        Files.createDirectories(inboxOf(root));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();

        assertThatThrownBy(() -> pipeline.curate(new SortScope.Year(2019, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2019");
    }

    // An OldestYear scope can't get the synchronous pre-sort refusal above - its year isn't known
    // until the sort resolves it. So this same conflict can only surface after the sort has
    // already moved real files. The caller must not lose track of what moved just because the
    // cull stage was refused. Pipeline.CurateConflictException carries the SortSummary forward for
    // exactly that.
    @Test
    void curateWrapsAPostSortConflictInCurateConflictExceptionCarryingTheSortSummary(@TempDir final Path root)
            throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "already-there.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path newPhoto = writeInboxPhoto(root, "20190815_new.jpg");

        final var handle = curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear());

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
    void curateSkipsTheCullStageWhenCancellationIsRequestedBetweenStages(@TempDir final Path root) throws Exception {
        writeInboxPhoto(root, "20190601_photo.jpg");
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = curatePipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted,
                releaseMove));

        final JobHandle<CurateOutcome> handle = pipeline.curate(new SortScope.Year(2019, null));
        moveStarted.await();
        handle.requestCancellation();
        releaseMove.countDown();
        final CurateOutcome outcome = handle.join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(outcome.cullOutcome()).isNull();
        assertThat(Files.exists(root.resolve("logs/cull-prep"))).isFalse();
    }

    // Reaches CullJobOutcome.Cancelled through curate()'s own buildFreshAndDispatch() call, not
    // just a standalone cull(). Both funnel through the same method, but this proves the shared
    // path really is reached from curate() too. SortEngine doesn't call MediaStore.listFiles, so
    // BlockingListFiles only blocks once the sort stage has already finished and the cull stage's
    // render pass starts scanning Sorted for candidates.
    @Test
    void curateCancelledMidRenderDuringItsCullStageResolvesToCancelled(@TempDir final Path root) throws Exception {
        writeInboxPhoto(root, "20190601_photo.jpg");
        final var listStarted = new CountDownLatch(1);
        final var releaseList = new CountDownLatch(1);
        final var pipeline = curatePipeline(root, new RecordingProgressPort(), new BlockingListFiles(listStarted,
                releaseList));

        final JobHandle<CurateOutcome> handle = pipeline.curate(new SortScope.Year(2019, null));
        listStarted.await();
        handle.requestCancellation();
        releaseList.countDown();
        final CurateOutcome outcome = handle.join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Cancelled.class);
        assertThat(Files.exists(root.resolve("logs/cull-prep/2019"))).isFalse();
    }
}
