package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.CurateOutcome;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.PipelineTestSupport.BlockingListFiles;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.FixedSecretStore;
import static photos.sluice.application.service.PipelineTestSupport.MANUAL_PROVIDER_KEY;
import static photos.sluice.application.service.PipelineTestSupport.ManualModeCuller;
import static photos.sluice.application.service.PipelineTestSupport.OutOfScopeCuller;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.autoApproveCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.credentialPipeline;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.curatePipeline;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.writeInboxPhoto;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;

class CurateEngineTest {

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
        // Untouched: AutoApproveCuller's shard keeps it, and a keep moves nothing.
        assertThat(Files.exists(sorted)).isTrue();
        assertThat(progress.events).containsExactly(
                "planned:Finding dates..., Checking for duplicates..., Sorting..., "
                        + "Reading photos..., Sifting..., Applying decisions...",
                "started:Finding dates...", "tick:Finding dates...:1/1", "finished:Finding dates...",
                "started:Checking for duplicates...", "tick:Checking for duplicates...:1/1",
                "finished:Checking for duplicates...",
                "started:Sorting...", "tick:Sorting...:1/1", "finished:Sorting...",
                "started:Reading photos...", "tick:Reading photos...:1/1", "finished:Reading photos...",
                "started:Sifting...", "finished:Sifting...",
                "started:Applying decisions...", "finished:Applying decisions...");
    }

    @Test
    void curateForAProviderWithNoStoredKeyIsRefusedBeforeTheSortMovesAnything(@TempDir final Path root)
            throws IOException {
        final Path photo = writeInboxPhoto(root, "20190601_photo.jpg");
        final var pipeline = credentialPipeline(root, new RecordingProgressPort(),
                List.of(new ManualModeCuller(MANUAL_PROVIDER_KEY)), new FixedSecretStore(null));

        assertThatThrownBy(() -> pipeline.curate(new SortScope.Year(2019, null)))
                .isInstanceOf(MissingCredentialException.class);

        assertThat(Files.exists(photo)).isTrue();
    }

    // The sort summary still describes what already moved, so a refused cull stage must not throw
    // the sort's own result away.
    @Test
    void curateReportsBlockedWithItsFindingsWhenTheCullStagesApplyRefuses(@TempDir final Path root) throws IOException {
        writeInboxPhoto(root, "20190601_photo.jpg");
        final var pipeline = pipeline(root, new RecordingProgressPort(), new NioMediaStore(),
                autoApproveCullSettings(), List.of(new OutOfScopeCuller()));

        final CurateOutcome outcome = pipeline.curate(new SortScope.Year(2019, null)).join();

        assertThat(outcome.sortSummary().photosSorted()).isEqualTo(1);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Blocked.class);
        final var blocked = (CullJobOutcome.Blocked) Objects.requireNonNull(outcome.cullOutcome());
        assertThat(blocked.findings()).singleElement().isInstanceOf(Finding.FileOutOfScope.class);
        // The sorted keeper stays put: a refused apply moves nothing at all.
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/20190601_photo.jpg"))).isTrue();
    }

    @Test
    void curateWithOldestYearScopeResolvesAndCullsTheYearTheSortPicked(@TempDir final Path root) throws IOException {
        writeInboxPhoto(root, "20190601_photo.jpg");

        final CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear()).join();

        assertThat(outcome.sortSummary().yearsSorted()).containsExactly(2019);
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(Files.exists(root.resolve("logs/sift-prep/2019/index.json"))).isTrue();
    }

    // A null cullOutcome alone would also hold for a cull stage that ran over an empty default
    // scope. The absence of a sift-prep dir is what rules that out.
    @Test
    void curateSkipsCullWhenAnOldestYearSortFindsNothingToSort(@TempDir final Path root) throws IOException {
        Files.createDirectories(inboxOf(root));

        final CurateOutcome outcome =
                curatePipeline(root, new RecordingProgressPort()).curate(new SortScope.OldestYear()).join();

        assertThat(outcome.sortSummary().processed()).isZero();
        assertThat(outcome.cullOutcome()).isNull();
        assertThat(Files.exists(root.resolve("logs/sift-prep"))).isFalse();
    }

    // Two photos in different years, so a sort restricted to fit the cull stage's one-scope shape
    // would show here.
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
        assertThat(Files.exists(root.resolve("logs/sift-prep/oldest-2/index.json"))).isTrue();
    }

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
        assertThat(Files.exists(root.resolve("logs/sift-prep/2019-06/index.json"))).isTrue();
    }

    // The Inbox is empty, so the sort finds nothing new. The photo already sitting in Sorted from
    // an earlier run is what the cull stage has to reach.
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

    // The same fixture, for the other scope whose target is known before curate() submits a job.
    @Test
    void curateWithAnExplicitOldestNScopeCullsEvenWhenThisRunSortedNothingNew(@TempDir final Path root)
            throws IOException {
        Files.createDirectories(inboxOf(root));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "already-sorted.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var progress = new RecordingProgressPort();

        final CurateOutcome outcome = curatePipeline(root, progress).curate(new SortScope.OldestN(1)).join();

        assertThat(outcome.sortSummary().processed()).isZero();
        assertThat(outcome.cullOutcome()).isInstanceOf(CullJobOutcome.Applied.class);
        assertThat(progress.events).contains("started:Sifting...");
        assertThat(Files.exists(root.resolve("logs/sift-prep/oldest-1/index.json"))).isTrue();
    }

    // The refusal is synchronous and pre-sort, proven by the sort never running at all: the
    // pre-existing Sorted photo is untouched, and no second prep dir was written.
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

    // An OldestYear scope's year is not known until the sort resolves it, so this conflict can
    // only surface after the sort has already moved real files.
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

    // BlockingMoves synchronizes with the exact moment the sort is mid-move, so cancellation is
    // requested before the post-sort check runs rather than at a guessed moment. The sort itself
    // still completes in full, its own single move() call delayed rather than interrupted.
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
        assertThat(Files.exists(root.resolve("logs/sift-prep"))).isFalse();
    }

    // The sort stage never calls listFiles, so BlockingListFiles only blocks once the sort has
    // finished and the cull stage's render pass starts scanning Sorted for candidates.
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
        assertThat(Files.exists(root.resolve("logs/sift-prep/2019"))).isFalse();
    }
}
