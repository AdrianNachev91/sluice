package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.application.port.out.SiftException;
import photos.sluice.domain.sift.AnswerSource;
import photos.sluice.domain.sift.CorruptSidecarResolution;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.SiftScope;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.PrepDirHealth.State;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingSiftJob;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static photos.sluice.application.service.PipelineTestSupport.AutoApproveSieve;
import static photos.sluice.application.service.PipelineTestSupport.BlockingCancellableSieve;
import static photos.sluice.application.service.PipelineTestSupport.BlockingSieve;
import static photos.sluice.application.service.PipelineTestSupport.BlockingIncompleteSieve;
import static photos.sluice.application.service.PipelineTestSupport.BlockingListFiles;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoveTo;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.FailableIndexReads;
import static photos.sluice.application.service.PipelineTestSupport.FailingListingOfPrepDir;
import static photos.sluice.application.service.PipelineTestSupport.FixedSecretStore;
import static photos.sluice.application.service.PipelineTestSupport.FixedSettings;
import static photos.sluice.application.service.PipelineTestSupport.MANUAL_PROVIDER_KEY;
import static photos.sluice.application.service.PipelineTestSupport.ImpossibleCountSieve;
import static photos.sluice.application.service.PipelineTestSupport.JunkEverythingSieve;
import static photos.sluice.application.service.PipelineTestSupport.listed;
import static photos.sluice.application.service.PipelineTestSupport.ManualModeSieve;
import static photos.sluice.application.service.PipelineTestSupport.NeverCalledSieve;
import static photos.sluice.application.service.PipelineTestSupport.PlantOnFirstExists;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.SpendingThenFailingSieve;
import static photos.sluice.application.service.PipelineTestSupport.ThrowingSieve;
import static photos.sluice.application.service.PipelineTestSupport.assertHoldsFor;
import static photos.sluice.application.service.PipelineTestSupport.CeilingStoppedSieve;
import static photos.sluice.application.service.PipelineTestSupport.autoApproveSiftSettings;
import static photos.sluice.application.service.PipelineTestSupport.classificationJson;
import static photos.sluice.application.service.PipelineTestSupport.credentialPipeline;
import static photos.sluice.application.service.PipelineTestSupport.siftPipeline;
import static photos.sluice.application.service.PipelineTestSupport.defaultSiftSettings;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.keepJson;
import static photos.sluice.application.service.PipelineTestSupport.padded;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.prepDirRemedies;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.spendLedgerOf;
import static photos.sluice.application.service.PipelineTestSupport.waitForJobToFinish;
import static photos.sluice.application.service.PipelineTestSupport.waitUntil;
import static photos.sluice.application.service.PipelineTestSupport.watchPipeline;
import static photos.sluice.application.service.PipelineTestSupport.writeAllKeepsShard;
import static photos.sluice.application.service.PipelineTestSupport.writeFile;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

class SiftEngineTest {

    private final List<Pipeline> armed = new ArrayList<>();

    // Retiring a watcher leaves a poll that is already mid-attempt to finish, resume included, so
    // the run's last write can land after the test method returns. Deleting the @TempDir then
    // meets an open handle, and draining the runner is what orders the two.
    @AfterEach
    void stopEveryWatcherThisTestArmed() {
        this.armed.forEach(pipeline -> {
            pipeline.stopAllWatching();
            assertThat(pipeline.stopAcceptingJobs(Duration.ofSeconds(10))).isTrue();
        });
    }

    // How long a test waits on a latch that should never trip. Its one use is paired with a
    // control trip inside the same window, so the number is checked by the test rather than picked
    // to feel safe.
    private static final Duration WINDOW = Duration.ofMillis(500);

    @Test
    void siftReturnsWaitingWithAnEmptyTallyWhenNoShardsHaveBeenDropped(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        final SiftJobOutcome outcome = siftPipeline(root, progress).sift(new SiftScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
        final WaitingSiftJob job = ((SiftJobOutcome.Waiting) outcome).job();
        assertThat(job.scope()).isEqualTo("2019");
        assertThat(job.shards()).isEqualTo(new ShardTally(0, 0, 1));
        assertThat(Files.exists(job.prepDir().resolve("index.json"))).isTrue();
    }

    @Test
    void aRunWaitingOnAnAgentsShardsSaysThatIsWhyItPaused(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort())
                .sift(new SiftScope.Year(2019, null)).join();

        assertThat(((SiftJobOutcome.Waiting) outcome).reason()).isEqualTo(WaitingReason.SHARDS_OUTSTANDING);
    }

    @Test
    void siftBracketsPreppingAndSiftingPhasesButNeverReachesApplyingWhenWaiting(@TempDir final Path root) throws IOException {
        final var progress = new RecordingProgressPort();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

        siftPipeline(root, progress).sift(new SiftScope.Year(2019, null)).join();

        assertThat(progress.events).containsExactly(
                "planned:Reading photos..., Sifting..., Applying decisions...",
                "started:Reading photos...", "tick:Reading photos...:1/1", "finished:Reading photos...",
                "started:Sifting...", "cutShort:Sifting...", "finished:Sifting...");
    }

    @Test
    void siftOverAScopeWithNoMontagesAppliesWithoutEnteringASieve(@TempDir final Path root) {
        final var progress = new RecordingProgressPort();
        final var pipeline = siftPipeline(root, progress, defaultSiftSettings(), List.of(new NeverCalledSieve()));

        final SiftJobOutcome outcome = pipeline.sift(new SiftScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Sifting"));
    }

    @Test
    void blockedCopiesItsFindingsSoALaterMutationCannotReachTheOutcome() {
        final List<Finding> mutable = new ArrayList<>(List.of(new Finding.CorruptShard("montage-001",
                "decisions-001.json")));
        final var job = new WaitingSiftJob("2019", Path.of("prep"), new ShardTally(1, 1, 1), Instant.EPOCH);

        final var blocked = new SiftJobOutcome.Blocked(job, mutable,
                SiftReport.nothingSpent("a-provider", 0), null);
        mutable.clear();

        assertThat(blocked.findings()).containsExactly(new Finding.CorruptShard("montage-001", "decisions-001.json"));
    }

    @Test
    void aRunWhoseEveryShardJudgesNothingIsRefusedRatherThanAppliedAsAllKeepers(@TempDir final Path root)
            throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = siftPipeline(root, new RecordingProgressPort());
        final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001");

        final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

        assertThat(outcome).isInstanceOf(SiftJobOutcome.Blocked.class);
        assertThat(((SiftJobOutcome.Blocked) outcome).findings())
                .singleElement().isInstanceOf(Finding.PhotosNotJudged.class);
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        assertThat(Files.exists(sortedPhotosDir(root, "2019", "06").resolve("IMG_1.jpg"))).isTrue();
    }

    @Test
    void siftPropagatesAFailureFromAnAutomatedProviderInsteadOfReturningWaiting(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var progress = new RecordingProgressPort();
        final var settings = new FixedSettings("anthropic", List.of());
        final var pipeline = siftPipeline(root, progress, settings, List.of(new ThrowingSieve("anthropic")));

        final var handle = pipeline.sift(new SiftScope.Year(2019, null));

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SiftException.class);
        assertThat(progress.events).contains("finished:Sifting...");
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Applying"));
    }

    @Test
    void siftStillReturnsWaitingWhenTheJobsMtimeCannotBeRead(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = pipeline(root, new RecordingProgressPort(), new FailingLastModified(),
                defaultSiftSettings(), List.of(new ManualModeSieve()));

        final SiftJobOutcome outcome = pipeline.sift(new SiftScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
        assertThat(((SiftJobOutcome.Waiting) outcome).job().since()).isEqualTo(Instant.EPOCH);
    }

    @Nested
    class TheSpendCeiling {

        @Test
        void aRunStoppedByItSaysThatIsWhyItPaused(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var sieve = new CeilingStoppedSieve();

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(),
                    autoApproveSiftSettings(), List.of(sieve)).sift(new SiftScope.Year(2019, null)).join();

            assertThat(((SiftJobOutcome.Waiting) outcome).reason()).isEqualTo(WaitingReason.CEILING_REACHED);
            assertThat(outcome.siftReport().spend().inputTokens()).isEqualTo(9_000);
        }

        @Test
        void aRunStoppedByItSaysTheSiftingPhaseGaveUpPartWay(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var progress = new RecordingProgressPort();

            siftPipeline(root, progress, autoApproveSiftSettings(), List.of(new CeilingStoppedSieve()))
                    .sift(new SiftScope.Year(2019, null)).join();

            assertThat(progress.events).containsSubsequence("cutShort:Sifting...", "finished:Sifting...");
        }

        @Test
        void aRunThatSpentUnderItIsNotSaidToHaveGivenUpPartWay(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var progress = new RecordingProgressPort();

            siftPipeline(root, progress, autoApproveSiftSettings(), List.of(new AutoApproveSieve()))
                    .sift(new SiftScope.Year(2019, null)).join();

            assertThat(progress.events).contains("finished:Sifting...")
                    .doesNotContain("cutShort:Sifting...");
        }

        @Test
        void anAutomatedRunIsHandedOneItMayNotSpendPast(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var sieve = new CeilingStoppedSieve();

            siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(), List.of(sieve))
                    .sift(new SiftScope.Year(2019, null)).join();

            assertThat(sieve.receivedCeiling).isNotNull()
                    .extracting(SpendCeiling::maxCalls).isEqualTo(4);
        }

        @Test
        void theCallArmCoversEveryMontageInScopeRatherThanTheOnesStillOwingAShard(@TempDir final Path root)
                throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var waiting = (SiftJobOutcome.Waiting) siftPipeline(root, new RecordingProgressPort())
                    .sift(new SiftScope.Year(2019, null)).join();
            writeAllKeepsShard(waiting.job().prepDir(), "montage-001");
            final var sieve = new CeilingStoppedSieve();

            siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(), List.of(sieve))
                    .resume(waiting.job().prepDir(), false).join();

            assertThat(sieve.receivedCeiling).isNotNull()
                    .extracting(SpendCeiling::maxCalls).isEqualTo(4);
        }

        @Test
        void aRunThroughAProviderThatSpendsNothingIsHandedNone(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var sieve = new AutoApproveSieve();

            siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(), List.of(sieve))
                    .sift(new SiftScope.Year(2019, null)).join();

            assertThat(sieve.receivedOptions).isNotNull()
                    .extracting(SiftOptions::ceiling).isNull();
        }
    }

    @Nested
    class TheSpendLedger {

        @Test
        void aFinishedRunIsRecorded(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

            siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(spendLedgerOf(root).read()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.scope()).isEqualTo("2019");
                        assertThat(entry.ending()).isEqualTo(RunEnding.APPLIED);
                        assertThat(entry.montagesSifted()).isEqualTo(1);
                    });
        }

        @Test
        void aFinishedRunCountsWhatEveryCallOfItSpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            spendLedgerOf(root).append(spent("2019", 9_000, 3_000, RunEnding.CEILING_REACHED));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOfSatisfying(SiftJobOutcome.Applied.class,
                    applied -> assertThat(applied.totalTokens()).isEqualTo(12_000L));
        }

        @Test
        void aFinishedRunLeavesOutWhatARunTheReaderThrewAwaySpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            spendLedgerOf(root).append(spent("2019", 40_000, 8_000, RunEnding.CEILING_REACHED));
            spendLedgerOf(root).append(spent("2019", 0, 0, RunEnding.DISCARDED));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOfSatisfying(SiftJobOutcome.Applied.class,
                    applied -> assertThat(applied.totalTokens()).isZero());
        }

        @Test
        void givingUpOnARunRecordsThatItsScopeIsFree(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final Pipeline pipeline = siftPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();

            pipeline.discard(waiting.job().prepDir()).join();

            assertThat(spendLedgerOf(root).read()).last()
                    .satisfies(entry -> {
                        assertThat(entry.scope()).isEqualTo("2019");
                        assertThat(entry.ending()).isEqualTo(RunEnding.DISCARDED);
                    });
        }

        @Test
        void aFinishedRunLeavesOutWhatAnEarlierRunOfTheSameScopeSpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            spendLedgerOf(root).append(spent("2019", 5_000, 1_000, RunEnding.APPLIED));
            spendLedgerOf(root).append(spent("2019", 9_000, 3_000, RunEnding.CEILING_REACHED));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOfSatisfying(SiftJobOutcome.Applied.class,
                    applied -> assertThat(applied.totalTokens()).isEqualTo(12_000L));
        }

        @Test
        void aFinishedRunLeavesOutWhatAnotherScopeSpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            spendLedgerOf(root).append(spent("2018", 7_000, 2_000, RunEnding.APPLIED));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOfSatisfying(SiftJobOutcome.Applied.class,
                    applied -> assertThat(applied.totalTokens()).isZero());
        }

        @Test
        void aRunStoppedByItsCeilingIsRecordedAsSuchWithWhatItSpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));

            siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new CeilingStoppedSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(spendLedgerOf(root).read()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.ending()).isEqualTo(RunEnding.CEILING_REACHED);
                        assertThat(entry.apiCalls()).isEqualTo(2);
                        assertThat(entry.inputTokens()).isEqualTo(9_000);
                        assertThat(entry.outputTokens()).isEqualTo(3_000);
                        assertThat(entry.modelId()).isEqualTo("a-model");
                    });
        }

        @Test
        void aRunAbandonedMidDispatchIsRecordedWithWhatItHadAlreadySpent(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

            assertThatThrownBy(() -> siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new SpendingThenFailingSieve())).sift(new SiftScope.Year(2019, null)).join())
                    .hasRootCauseInstanceOf(SiftException.class);

            assertThat(spendLedgerOf(root).read()).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.ending()).isEqualTo(RunEnding.FAILED);
                        assertThat(entry.inputTokens()).isEqualTo(4_000);
                        assertThat(entry.outputTokens()).isEqualTo(800);
                    });
        }

        @Test
        void aResumeIsRecordedAsItsOwnRunRatherThanFoldedIntoTheFirst(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeAllKeepsShard(waiting.job().prepDir(), "montage-001");

            pipeline.resume(waiting.job().prepDir(), false).join();

            assertThat(spendLedgerOf(root).read()).extracting(SpendLedgerEntry::ending)
                    .containsExactly(RunEnding.SHARDS_OUTSTANDING, RunEnding.APPLIED);
        }

        @Test
        void aCeilingStopIsReportedAsSuchEvenWhenACancellationLandsOnTopOfIt(@TempDir final Path root)
                throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var stoppedAtCeiling = new CountDownLatch(1);
            final var releaseSift = new CountDownLatch(1);
            final var pipeline = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new BlockingSieve(stoppedAtCeiling, releaseSift, true)));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            stoppedAtCeiling.await();
            handle.requestCancellation();
            releaseSift.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(((SiftJobOutcome.Waiting) outcome).reason()).isEqualTo(WaitingReason.CEILING_REACHED);
            assertThat(spendLedgerOf(root).read()).extracting(SpendLedgerEntry::ending)
                    .containsExactly(RunEnding.CEILING_REACHED);
        }

        @Test
        void aRunWhoseApplyIsRefusedIsRecordedAsBlocked(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001",
                    classificationJson(root.resolve("never-in-scope.jpg"), "junk", "blurry"));

            pipeline.resume(waiting.job().prepDir(), false).join();

            assertThat(spendLedgerOf(root).read()).extracting(SpendLedgerEntry::ending)
                    .containsExactly(RunEnding.SHARDS_OUTSTANDING, RunEnding.BLOCKED);
        }

        @Test
        void aRunCancelledBeforeAnyPrepDirExistedIsStillRecorded(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var listStarted = new CountDownLatch(1);
            final var releaseList = new CountDownLatch(1);
            final var pipeline = pipeline(root, new RecordingProgressPort(),
                    new BlockingListFiles(listStarted, releaseList), defaultSiftSettings(),
                    List.of(new NeverCalledSieve()));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            listStarted.await();
            handle.requestCancellation();
            releaseList.countDown();
            handle.join();

            assertThat(spendLedgerOf(root).read()).extracting(SpendLedgerEntry::ending)
                    .containsExactly(RunEnding.CANCELLED);
        }

        @Test
        void aRunWhoseApplyFailsOnTheFilesystemIsStillRecorded(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeAllKeepsShard(waiting.job().prepDir(), "montage-001");
            Files.createDirectories(waiting.job().prepDir().resolve("decisions.json"));

            assertThatThrownBy(() -> pipeline.resume(waiting.job().prepDir(), false).join())
                    .hasRootCauseInstanceOf(IOException.class);

            assertThat(spendLedgerOf(root).read()).extracting(SpendLedgerEntry::ending)
                    .containsExactly(RunEnding.SHARDS_OUTSTANDING, RunEnding.FAILED);
        }

        @Test
        void oneThatCannotBeWrittenDoesNotCostTheUserTheirRun(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            Files.createDirectories(root.resolve("logs").resolve("spend-ledger.csv"));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new AutoApproveSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
        }

        @Test
        void aReportItRefusesCostsTheRecordRatherThanTheRun(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));

            final SiftJobOutcome outcome = siftPipeline(root, new RecordingProgressPort(), autoApproveSiftSettings(),
                    List.of(new ImpossibleCountSieve())).sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
            assertThat(spendLedgerOf(root).read()).isEmpty();
        }
    }

    @Nested
    class ClaimingTheScope {

        @Test
        void refusesToRebuildOneThatAlreadyHasAWaitingRun(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                            refusal -> assertThat(refusal.occupant().scope()).isEqualTo("2019"));
            assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
        }

        @Test
        void refusesAWholeYearRunningAcrossAnUnfinishedSiftOfItsMonths(@TempDir final Path root)
                throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, List.of(6))).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOfSatisfying(Pipeline.ScopeOverlapsException.class, refusal ->
                            assertThat(refusal.across()).extracting(SiftRunSummary::scope)
                                    .containsExactly("2019-06"));
        }

        @Test
        void refusesAMonthInsideAnUnfinishedSiftOfItsWholeYear(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, List.of(6))))
                    .isInstanceOf(Pipeline.ScopeOverlapsException.class);
        }

        @Test
        void allowsAYearThatSharesNoMonthWithAnUnfinishedSift(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "11"), "IMG_2.jpg", Instant.parse("2019-11-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, List.of(6))).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            assertThatCode(() -> pipeline.sift(new SiftScope.Year(2019, List.of(11))).join())
                    .doesNotThrowAnyException();
        }

        @Test
        void allowsTheOldestFilesBesideAnUnfinishedYear(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            assertThatCode(() -> pipeline.sift(new SiftScope.OldestN(1)).join()).doesNotThrowAnyException();
        }

        @Test
        void refusesOneWhoseRunIsReadyToApply(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.READY);

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                            refusal -> assertThat(refusal.occupant().health().state()).isEqualTo(State.READY));
        }

        @Test
        void refusesOneWhoseIndexIsGoneButWhoseShardsRemain(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            Files.delete(prepDir.resolve("index.json"));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOf(Pipeline.ScopeOccupiedException.class);
            assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        }

        @Test
        void refusesItAsUnreadableRatherThanFabricatingAnOccupantWhenOccupancyCannotBeRead(
                @TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final Path prepDir = root.resolve("logs/sift-prep/2019");
            Files.createDirectories(prepDir);
            Files.writeString(prepDir.resolve("stray.txt"), "something already here");
            final var mediaStore = new FailingListingOfPrepDir(prepDir);
            final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, defaultSiftSettings(),
                    List.of(new ManualModeSieve()));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOfSatisfying(Pipeline.ScopeUnreadableException.class,
                            refusal -> assertThat(refusal.prepDir()).isEqualTo(prepDir));
            assertThat(Files.exists(prepDir.resolve("stray.txt"))).isTrue();
        }

        @Test
        void archivesACompletedRunOfTheSameScopeAndProceeds(@TempDir final Path root) throws IOException {
            final Path first = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(first, "junk", "blurry"));
            pipeline.resume(prepDir, false).join();
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));

            final var second = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();

            final Path graveyard = second.archivedPriorRun();
            assertThat(graveyard).isNotNull();
            assertThat(graveyard.getFileName().toString()).startsWith("2019-");
            assertThat(graveyard.resolve("decisions.json")).exists();
            assertThat(graveyard.resolve("decisions-001.json")).exists();
            assertThat(second.job().shards()).isEqualTo(new ShardTally(0, 0, 1));
            assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        }

        @Test
        void refusesOneWhoseRunCannotBeReadAtAll(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var prepStore = new FailableIndexReads();
            final var pipeline = siftPipeline(root, new RecordingProgressPort(), prepStore);
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
            prepStore.startFailing();

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                            refusal -> assertThat(refusal.occupant().health().state()).isEqualTo(State.DAMAGED));
            assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
        }

        @Test
        void aTransientlyDamagedRunDiagnosesItsRealStateOnceTheReadSucceedsAgain(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var prepStore = new FailableIndexReads();
            final var pipeline = siftPipeline(root, new RecordingProgressPort(), prepStore);
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            prepStore.startFailing();
            // The control: proves this fixture's failing read actually reaches DAMAGED before trusting
            // the READY assertion below to mean the read succeeding, not the absence of caching alone.
            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.DAMAGED);

            prepStore.stopFailing();
            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.READY);
        }

        @Test
        void refusesOneTakenBetweenTheSynchronousCheckAndTheClaim(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final Path prepDir = root.resolve("logs/sift-prep/2019");
            final var planting = new PlantOnFirstExists(prepDir, () -> plantWaitingRun(prepDir, photo));
            final var pipeline = pipeline(root, new RecordingProgressPort(), planting, defaultSiftSettings(),
                    List.of(new ManualModeSieve()));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(Pipeline.ScopeOccupiedException.class);
            assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        }

        @Test
        void aFreeOneReportsNoArchivedPriorRun(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());

            final var outcome = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome.archivedPriorRun()).isNull();
        }

        // The second run needs a photo the first one never saw. Reusing the first run's single photo
        // leaves nothing for the second run to render, so it can complete before cancellation is even
        // requested. That race surfaced as Applied on a loaded CI runner. cancelOnFirstTick fires once
        // real rendering work is on disk, rather than guessing a moment by thread timing.
        @Test
        void aCancelledRunStillReportsWhereItArchivedThePriorRun(@TempDir final Path root) throws IOException {
            final Path dir = sortedPhotosDir(root, "2019", "06");
            final Path first = writePhoto(dir, "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var progress = new RecordingProgressPort();
            final var pipeline = siftPipeline(root, progress);
            final var firstRun = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(firstRun.job().prepDir(), "montage-001", classificationJson(first, "junk", "blurry"));
            pipeline.resume(firstRun.job().prepDir(), false).join();
            writePhoto(dir, "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));

            final var handleReady = new CountDownLatch(1);
            final var cancel = new AtomicReference<Runnable>(() -> {});
            progress.cancelOnFirstTick(() -> {
                try {
                    handleReady.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                cancel.get().run();
            });

            final var handle = pipeline.sift(new SiftScope.Year(2019, null));
            cancel.set(handle::requestCancellation);
            handleReady.countDown();
            final SiftJobOutcome outcome = handle.join();

            // A cancellation landing after rendering finished resolves to Waiting rather than
            // Cancelled. Every variant carries the archive, so the claim is about that, not about
            // which outcome won.
            final Path graveyard = outcome.archivedPriorRun();
            assertThat(outcome).isInstanceOfAny(SiftJobOutcome.Cancelled.class, SiftJobOutcome.Waiting.class);
            assertThat(graveyard).isNotNull();
            assertThat(graveyard.resolve("decisions.json")).exists();
        }

        // Two photos at one montage each, so the render really does write montage-001 before stopping.
        // A single photo would leave either no directory or an empty one, and an empty dir never
        // occupies a scope anyway. The assertion would then hold with the cleanup deleted.
        @Test
        void aCancelledRenderLeavesItFreeForAnotherSift(@TempDir final Path root) throws IOException {
            final Path dir = sortedPhotosDir(root, "2019", "06");
            writePhoto(dir, "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(dir, "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var progress = new RecordingProgressPort();
            // Cancelled the moment montage-001's write is reported, so the stop lands in the only
            // window where a partial prep dir exists. The tick can fire before sift() has returned the
            // handle, so the hook waits on the latch for it rather than reading a null.
            final var handleReady = new CountDownLatch(1);
            final var cancel = new AtomicReference<Runnable>(() -> {});
            progress.cancelOnFirstTick(() -> {
                try {
                    handleReady.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                cancel.get().run();
            });
            final var pipeline = siftPipeline(root, progress);

            final var handle = pipeline.sift(new SiftScope.Year(2019, null));
            cancel.set(handle::requestCancellation);
            handleReady.countDown();
            final SiftJobOutcome first = handle.join();

            assertThat(first).isInstanceOf(SiftJobOutcome.Cancelled.class);
            assertThat(root.resolve("logs/sift-prep/2019")).doesNotExist();
            assertThat(pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .isInstanceOf(SiftJobOutcome.Waiting.class);
        }
    }

    @Nested
    class TheProvidersKey {

        @Test
        void aRunForAProviderWithNoneStoredIsRefusedBeforeAnyMontageIsBuilt(@TempDir final Path root)
                throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = credentialPipeline(root, new RecordingProgressPort(),
                    List.of(new ManualModeSieve(MANUAL_PROVIDER_KEY)), new FixedSecretStore(null));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOf(MissingCredentialException.class);

            assertThat(root.resolve("logs/sift-prep/2019")).doesNotExist();
        }

        @Test
        void aRunForAProviderWhoseKeyIsHeldRunsOn(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = credentialPipeline(root, new RecordingProgressPort(),
                    List.of(new ManualModeSieve(MANUAL_PROVIDER_KEY)), new FixedSecretStore("a-key"));

            final SiftJobOutcome outcome = pipeline.sift(new SiftScope.Year(2019, null)).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            assertThat(root.resolve("logs/sift-prep/2019")).exists();
        }

        // Both faults hold at once here, and only one sentence is shown. Nothing but the sequence of
        // two calls in one method keeps this order, so it is pinned here.
        @Test
        void aKeylessProviderIsReportedAheadOfAnOccupiedTimeframe(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final Path prepDir = root.resolve("logs/sift-prep/2019");
            Files.createDirectories(prepDir);
            Files.writeString(prepDir.resolve("stray.txt"), "an earlier sift, still here");
            final var pipeline = credentialPipeline(root, new RecordingProgressPort(),
                    List.of(new ManualModeSieve(MANUAL_PROVIDER_KEY)), new FixedSecretStore(null));

            assertThatThrownBy(() -> pipeline.sift(new SiftScope.Year(2019, null)))
                    .isInstanceOf(MissingCredentialException.class);
        }

        @Test
        void aResumeFinishesAKeylessProvidersRunRatherThanRefusingIt(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var keyed = List.<VisionSieve>of(new ManualModeSieve(MANUAL_PROVIDER_KEY));
            final var waiting = (SiftJobOutcome.Waiting) credentialPipeline(root, new RecordingProgressPort(),
                    keyed, new FixedSecretStore("a-key")).sift(new SiftScope.Year(2019, null)).join();
            writeAllKeepsShard(waiting.job().prepDir(), "montage-001");

            final SiftJobOutcome resumed = credentialPipeline(root, new RecordingProgressPort(),
                    keyed, new FixedSecretStore(null)).resume(waiting.job().prepDir(), false).join();

            assertThat(resumed).isInstanceOf(SiftJobOutcome.Applied.class);
        }

        @Test
        void aRunForAProviderThatNeedsNoneRunsWithNothingStored(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = credentialPipeline(root, new RecordingProgressPort(),
                    List.of(new ManualModeSieve()), new FixedSecretStore(null));

            assertThat(pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .isInstanceOf(SiftJobOutcome.Waiting.class);
        }
    }

    @Nested
    class Cancelling {

        @Test
        void aStopRightAfterPrepFinishesKeepsDispatchFromEverRunning(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var progress = new RecordingProgressPort();
            // The tick can fire before sift() has returned the handle, so the hook waits on the latch
            // for it rather than reading a null. Bounded, so a broken wiring fails fast instead of
            // hanging the suite.
            final var handleReady = new CountDownLatch(1);
            final var cancel = new AtomicReference<Runnable>(() -> {});
            progress.cancelWhenPhaseFinishes("Reading photos...", () -> {
                try {
                    if (!handleReady.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("handle was never handed over");
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                cancel.get().run();
            });
            final var pipeline = siftPipeline(root, progress, defaultSiftSettings(), List.of(new NeverCalledSieve()));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            cancel.set(handle::requestCancellation);
            handleReady.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            final WaitingSiftJob job = ((SiftJobOutcome.Waiting) outcome).job();
            assertThat(job.shards()).isEqualTo(new ShardTally(0, 0, 1));
            assertThat(progress.events).noneMatch(event -> event.startsWith("started:Sifting"));
            assertThat(pipeline.isWatchActive(job.prepDir())).isFalse();
        }

        @Test
        void aRunTheReaderStoppedIsNotSaidToHaveGivenUpPartWay(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var siftStarted = new CountDownLatch(1);
            final var releaseSift = new CountDownLatch(1);
            final var progress = new RecordingProgressPort();
            final var pipeline = siftPipeline(root, progress, autoApproveSiftSettings(),
                    List.of(new BlockingSieve(siftStarted, releaseSift, false)));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            siftStarted.await();
            handle.requestCancellation();
            releaseSift.countDown();
            handle.join();

            assertThat(progress.events).contains("finished:Sifting...")
                    .doesNotContain("cutShort:Sifting...");
        }

        @Test
        void midDispatchItResolvesToWaitingThenResumeCompletes(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var firstShardWritten = new CountDownLatch(1);
            final var releaseSift = new CountDownLatch(1);
            final var settings = new FixedSettings("auto-approve", List.of());
            final var pipeline = siftPipeline(root, new RecordingProgressPort(), settings,
                    List.of(new BlockingCancellableSieve(firstShardWritten, releaseSift)));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            firstShardWritten.await();
            handle.requestCancellation();
            releaseSift.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            final WaitingSiftJob job = ((SiftJobOutcome.Waiting) outcome).job();
            final Path prepDir = job.prepDir();
            assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
            assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isFalse();
            assertThat(pipeline.isWatchActive(prepDir)).isFalse();

            final SiftJobOutcome resumed = pipeline.resume(prepDir, false).join();

            assertThat(resumed).isInstanceOf(SiftJobOutcome.Applied.class);
            assertThat(Files.exists(prepDir.resolve("decisions-002.json"))).isTrue();
        }

        // BlockingListFiles synchronizes the test with the exact moment the renderer is scanning
        // Sorted for candidates, so the cancellation lands mid-render.
        @Test
        void midRenderItResolvesToCancelledWithNoPrepDirEverWritten(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var listStarted = new CountDownLatch(1);
            final var releaseList = new CountDownLatch(1);
            final var mediaStore = new BlockingListFiles(listStarted, releaseList);
            final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, defaultSiftSettings(),
                    List.of(new NeverCalledSieve()));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            listStarted.await();
            handle.requestCancellation();
            releaseList.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Cancelled.class);
            assertThat(Files.exists(root.resolve("logs/sift-prep/2019"))).isFalse();
        }

        // JunkEverythingSieve writes a real "junk" classification for every photo, so there is an
        // actual move loop for BlockingMoveTo to synchronize with.
        @Test
        void midApplyItResolvesToWaitingWithDecisionsJsonNeverWritten(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var moveStarted = new CountDownLatch(1);
            final var releaseMove = new CountDownLatch(1);
            final var mediaStore = new BlockingMoveTo(moveStarted, releaseMove);
            final var settings = new FixedSettings("auto-approve", List.of());
            final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, settings,
                    List.of(new JunkEverythingSieve()));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            moveStarted.await();
            handle.requestCancellation();
            releaseMove.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            final Path prepDir = ((SiftJobOutcome.Waiting) outcome).job().prepDir();
            assertThat(requireNonNull(((SiftJobOutcome.Waiting) outcome).movedBeforeItPaused()).byCategory())
                    .containsExactly(entry("junk", 1));
            assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
            // Exactly one of the two photos was fully processed before the cancellation stopped the
            // loop. Which one is not fixed, since scan order between them is not guaranteed.
            try (final var junked = Files.list(root.resolve("Review/junk"))) {
                assertThat(junked.filter(p -> p.getFileName().toString().startsWith("IMG_")).count()).isEqualTo(1);
            }
            try (final var remaining = Files.list(sortedPhotosDir(root, "2019", "06"))) {
                assertThat(remaining.count()).isEqualTo(1);
            }
        }

        @Test
        void aPauseDoesNotArmAWatcherWhenAStopRacedIt(@TempDir final Path root) throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var started = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new BlockingIncompleteSieve(started, release)), Duration.ofMillis(20));

            final JobHandle<SiftJobOutcome> handle = pipeline.sift(new SiftScope.Year(2019, null));
            started.await();
            handle.requestCancellation();
            release.countDown();
            final SiftJobOutcome outcome = handle.join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            final Path prepDir = ((SiftJobOutcome.Waiting) outcome).job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isFalse();
        }
    }

    @Nested
    class Resuming {

        @Test
        void appliesOnceAValidShardIsDropped(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
            final var applied = (SiftJobOutcome.Applied) outcome;
            assertThat(applied.applyReport().byCategory()).containsEntry("junk", 1);
            assertThat(Files.exists(photo)).isFalse();
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        @Test
        void bracketsTheApplyingPhaseOnTheAppliedPath(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var progress = new RecordingProgressPort();
            final var pipeline = siftPipeline(root, progress);
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            progress.events.clear();

            pipeline.resume(prepDir, false).join();

            assertThat(progress.events).containsExactly(
                    "planned:Sifting..., Applying decisions...",
                    "started:Applying decisions...", "tick:Applying decisions...:1/1", "finished:Applying decisions...");
        }

        // The absent "Sifting..." bracket is the second half of the proof: no phase ran for it either.
        @Test
        void goesStraightToApplyOnceEveryMontageHasAShard(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var preparing = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) preparing.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            final var progress = new RecordingProgressPort();
            final var resuming = siftPipeline(root, progress, defaultSiftSettings(), List.of(new NeverCalledSieve()));

            final SiftJobOutcome outcome = resuming.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
            assertThat(progress.events).noneMatch(event -> event.startsWith("started:Sifting"));
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        // The run is complete and would otherwise apply, so the refusal is what stops it rather than
        // anything missing from the prep dir.
        @Test
        void refusesARunOutsideTheWorkingRootInForce(@TempDir final Path root, @TempDir final Path movedTo)
                throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var preparing = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) preparing.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            final var moved = siftPipeline(movedTo, new RecordingProgressPort());

            final JobHandle<SiftJobOutcome> refused = moved.resume(prepDir, false);

            assertThatThrownBy(refused::join)
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .asInstanceOf(type(Pipeline.RunOutsideWorkingRootException.class))
                    .extracting(Pipeline.RunOutsideWorkingRootException::prepDir)
                    .isEqualTo(prepDir);
            assertThat(Files.exists(photo)).isTrue();
            assertThat(prepDir.resolve("decisions.json")).doesNotExist();
        }

        @Test
        void landsBlockedCarryingTheFindingsWhenApplyRefusesACompleteShardSet(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            // A file no montage showed, whose basename matches no in-scope file either, so no
            // unique-basename heal can pull it back into scope. The keep beside it covers the one photo
            // the sheet did show, leaving the out-of-scope file as the only thing to report.
            writeShard(prepDir, "montage-001", keepJson(photo),
                    classificationJson(root.resolve("never-in-scope.jpg"), "junk", "blurry"));

            final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Blocked.class);
            final var blocked = (SiftJobOutcome.Blocked) outcome;
            assertThat(blocked.job().prepDir()).isEqualTo(prepDir);
            assertThat(blocked.findings()).singleElement().isInstanceOf(Finding.FileOutOfScope.class);
            assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        }

        @Test
        void returnsWaitingAgainWithAnUpdatedTallyWhenAMontageStillLacksAShard(@TempDir final Path root) throws IOException {
            final Path juneDir = sortedPhotosDir(root, "2019", "06");
            final Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(waiting.job().shards()).isEqualTo(new ShardTally(0, 0, 2));
            writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

            final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Waiting.class);
            assertThat(((SiftJobOutcome.Waiting) outcome).job().shards()).isEqualTo(new ShardTally(1, 1, 2));
            assertThat(Files.exists(a)).isTrue();
        }

        @Test
        void withAllowPartialItAppliesWhatItHasAndLeavesTheMissingMontagesPhotoInPlace(@TempDir final Path root) throws IOException {
            final Path juneDir = sortedPhotosDir(root, "2019", "06");
            final Path a = writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final Path b = writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(a, "junk", "blurry"));

            final SiftJobOutcome outcome = pipeline.resume(prepDir, true).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
            assertThat(Files.exists(a)).isFalse();
            assertThat(Files.exists(b)).isTrue();
        }

        // A long poll interval keeps the watcher itself from racing to auto-resume before the manual
        // resume() below runs.
        @Test
        void byHandItDisarmsAnAlreadyArmedWatcher(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofSeconds(30));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Applied.class);
            assertThat(pipeline.isWatchActive(prepDir)).isFalse();
        }
    }

    @Nested
    class RedoingRejectedAnswers {

        @Test
        void filesThemAwayAndPutsTheRunBackToWaiting(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            // Judges nothing on a sheet that showed one photo, which is the hole coverage closes.
            writeShard(prepDir, "montage-001");

            final String prompt = pipeline.redoRejectedAnswers(prepDir);

            assertThat(prompt).contains("could not be used")
                    .contains("montage-001: no verdict for 1 of its photos (IMG_1.jpg)");
            assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isFalse();
            assertThat(pipeline.siftRuns()).isInstanceOfSatisfying(SiftRuns.Listed.class, listed ->
                    assertThat(listed.runs()).singleElement()
                            .satisfies(run -> assertThat(run.health().state()).isEqualTo(State.WAITING)));
        }

        @Test
        void isRefusedWhereNoSheetIsToBlame(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeAllKeepsShard(prepDir, "montage-001");

            assertThatThrownBy(() -> pipeline.redoRejectedAnswers(prepDir))
                    .isInstanceOf(Pipeline.NothingToRedoException.class)
                    .hasMessageContaining("waiting to be judged again");
            assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
        }
    }

    @Nested
    class ListingTheRuns {

        @Test
        void isEmptyWhenNoSiftHasEverRun(@TempDir final Path root) {
            assertThat(listed(siftPipeline(root, new RecordingProgressPort()).siftRuns())).isEmpty();
        }

        @Test
        void listsAPrepDirStillMissingShardsAsWaiting(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            pipeline.sift(new SiftScope.Year(2019, null)).join();

            final List<SiftRunSummary> runs = listed(pipeline.siftRuns());

            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst().scope()).isEqualTo("2019");
            assertThat(runs.getFirst().health().state()).isEqualTo(State.WAITING);
            assertThat(runs.getFirst().shards()).isEqualTo(new ShardTally(0, 0, 1));
        }

        @Test
        void stillListsAnAppliedRunAsComplete(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
            pipeline.resume(waiting.job().prepDir(), false).join();

            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.COMPLETE);
        }

        @Test
        void listsACorruptIndexAsBlockedAlongsideTheHealthyRun(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2020", "06"), "IMG_2.jpg", Instant.parse("2020-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var damaged = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final var healthy = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2020, null)).join();
            Files.writeString(damaged.job().prepDir().resolve("index.json"), "{ not json at all");

            final List<SiftRunSummary> runs = listed(pipeline.siftRuns());

            assertThat(runs).extracting(SiftRunSummary::prepDir)
                    .containsExactly(damaged.job().prepDir(), healthy.job().prepDir());
            assertThat(runs.getFirst().health().state()).isEqualTo(State.BLOCKED);
            assertThat(runs.getFirst().health().findings())
                    .containsExactly(new Finding.CorruptIndex(damaged.job().prepDir().resolve("index.json")));
            assertThat(runs.getFirst().shards()).isNull();
            assertThat(runs.getLast().health().state()).isEqualTo(State.WAITING);
            assertThat(runs.getLast().shards()).isEqualTo(new ShardTally(0, 0, 1));
        }

        @Test
        void aPresentButUnparseableShardTalliesAsPresentAndInvalidRatherThanFailingTheScan(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            Files.writeString(prepDir.resolve("montage-001.json"), "{ not json at all");
            Files.writeString(prepDir.resolve("decisions-001.json"), "{ not json at all");

            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(SiftRunSummary::shards).isEqualTo(new ShardTally(1, 0, 1));
        }

        // The name below carries a NUL character, which no mainstream filesystem accepts, so the
        // platform cannot make a path out of it at all.
        @Test
        void aShardNamingAnUnusableFileTalliesAsPresentAndInvalid(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            Files.writeString(prepDir.resolve("decisions-001.json"),
                    "{ \"montage\": \"montage-001\", \"decisions\": [ { \"file\": \"bad\\u0000name.jpg\", "
                            + "\"action\": \"junk\", \"reason\": \"blurry\" } ] }");

            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(SiftRunSummary::shards).isEqualTo(new ShardTally(1, 0, 1));
        }
    }

    @Nested
    class Watching {

        // The 30s interval means an armed watcher could not have fired and retired itself before the
        // assertion, so a false reading here means "never armed".
        @Test
        void armingOnStartupLeavesADamagedRunAlone(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var prepStore = new FailableIndexReads();
            final var setup = siftPipeline(root, new RecordingProgressPort(), prepStore);
            final Path prepDir = ((SiftJobOutcome.Waiting) setup.sift(new SiftScope.Year(2019, null)).join())
                    .job().prepDir();
            // The same store the scan below reads through, so the run really does diagnose DAMAGED for
            // it. A fresh store here would leave the index readable, and the run would arm as WAITING.
            final var watchPipeline = pipeline(root, new RecordingProgressPort(), new NioMediaStore(),
                    defaultSiftSettings(), List.of(new ManualModeSieve()), Duration.ofSeconds(30), prepStore);
            prepStore.startFailing();

            SiftEngineTest.this.armed.add(watchPipeline);
            watchPipeline.armWatchesForResumableRuns();

            assertThat(listed(watchPipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.DAMAGED);
            assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
        }

        @Test
        void armingOnStartupNeverArmsAWatcherForAnAutomatedProvidersRun(@TempDir final Path root)
                throws Exception {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
            final var firstShardWritten = new CountDownLatch(1);
            final var releaseSift = new CountDownLatch(1);
            final var settings = new FixedSettings("auto-approve", List.of());
            final var siftPipeline = siftPipeline(root, new RecordingProgressPort(), settings,
                    List.of(new BlockingCancellableSieve(firstShardWritten, releaseSift)));

            final JobHandle<SiftJobOutcome> handle = siftPipeline.sift(new SiftScope.Year(2019, null));
            firstShardWritten.await();
            handle.requestCancellation();
            releaseSift.countDown();
            final var waiting = (SiftJobOutcome.Waiting) handle.join();
            final Path prepDir = waiting.job().prepDir();

            final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), settings, List.of(),
                    Duration.ofMillis(20));

            SiftEngineTest.this.armed.add(watchPipeline);
            watchPipeline.armWatchesForResumableRuns();

            assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
        }

        // The shard is dropped before the restart, so this run is READY rather than WAITING when it is
        // found. That is the ordinary shape of the case, the agent having finished while the app was
        // closed.
        @Test
        void armingOnStartupAutoResumesARunItNeverStartedItself(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var manualPipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) manualPipeline.sift(new SiftScope.Year(2019, null)).join();
            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            SiftEngineTest.this.armed.add(watchPipeline);
            watchPipeline.armWatchesForResumableRuns();

            waitUntil(Duration.ofSeconds(2), () -> !Files.exists(photo));
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        // The poll interval below is long enough that an armed watcher could not have fired and
        // retired itself before the assertion. So a false reading here means "never armed", never
        // "already finished".
        @Test
        void armingOnStartupLeavesABlockedRunAlone(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var manualPipeline = siftPipeline(root, new RecordingProgressPort());
            final var waiting = (SiftJobOutcome.Waiting) manualPipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            // montage-002 belongs to no montage in the index, so the whole-batch gate refuses.
            writeShard(prepDir, "montage-002", classificationJson(photo, "junk", "blurry"));
            assertThat(listed(manualPipeline.siftRuns())).singleElement()
                    .extracting(run -> run.health().state()).isEqualTo(State.BLOCKED);

            final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofSeconds(30));
            SiftEngineTest.this.armed.add(watchPipeline);
            watchPipeline.armWatchesForResumableRuns();

            assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
        }

        @Test
        void aWaitingSiftAutoResumesOnceAValidShardIsDropped(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();

            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(Files.exists(photo)).isFalse();
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        @Test
        void anAutoResumeReachesAListenerOnTheFacadeWithTheRunsNameAndItsJob(@TempDir final Path root)
                throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var told = new AtomicReference<@Nullable String>();
            final var job = new AtomicReference<@Nullable JobHandle<SiftJobOutcome>>();
            pipeline.onSiftAutoResumed((scope, resumed) -> {
                told.set(scope);
                job.set(resumed);
            });
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();

            writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(told.get()).isEqualTo("2019");
            assertThat(requireNonNull(job.get()).join()).isInstanceOf(SiftJobOutcome.Applied.class);
        }

        @Test
        void aWaitingRunArmsItselfAndResumesWhenTheShardLands(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();

            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        @Test
        void aStrayShardResumesIntoBlockedRatherThanPollingOnForever(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            SiftEngineTest.this.armed.add(pipeline);
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            // montage-002 exists in no index, so its shard belongs to no montage at all.
            writeShard(prepDir, "montage-002", classificationJson(photo, "junk", "blurry"));

            // The watcher firing is what retires it, so an inactive watch proves the resume ran.
            waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));

            assertThat(listed(pipeline.siftRuns())).singleElement()
                    .extracting(SiftRunSummary::shards).isEqualTo(new ShardTally(1, 1, 1));
            assertThat(Files.exists(photo)).isTrue();
            assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
        }

        @Test
        void anApplyRefusalDisarmsTheWatchInsteadOfLeavingAPollerRunning(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofSeconds(30));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            Files.delete(photo);

            final SiftJobOutcome outcome = pipeline.resume(prepDir, false).join();

            assertThat(outcome).isInstanceOf(SiftJobOutcome.Blocked.class);
            assertThat(((SiftJobOutcome.Blocked) outcome).findings())
                    .singleElement().isInstanceOf(Finding.MissingSource.class);
            assertThat(pipeline.isWatchActive(prepDir)).isFalse();
        }

        @Test
        void stopAllWatchingRetiresEveryArmedWatcher(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            writePhoto(sortedPhotosDir(root, "2020", "06"), "IMG_2.jpg", Instant.parse("2020-06-01T10:00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofSeconds(30));
            final Path first = ((SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .job().prepDir();
            final Path second = ((SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2020, null)).join())
                    .job().prepDir();
            assertThat(pipeline.isWatchActive(first)).isTrue();
            assertThat(pipeline.isWatchActive(second)).isTrue();

            pipeline.stopAllWatching();

            assertThat(pipeline.isWatchActive(first)).isFalse();
            assertThat(pipeline.isWatchActive(second)).isFalse();
        }

        // Retiring the watcher announces a change too, on the way into the resume. So a test that
        // merely counted announcements would pass against a version saying nothing once work is done.
        @Test
        void aWatchTellsWhoeverIsListeningOnceItHasFinishedTheRunRatherThanOnStarting(
                @TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final Path prepDir = ((SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .job().prepDir();
            // Registered after the sift, so the arming this run already did is not what the wait sees.
            final var toldAfterThePhotoMoved = new AtomicBoolean();
            pipeline.onRunsChanged(() -> {
                if (!Files.exists(photo)) {
                    toldAfterThePhotoMoved.set(true);
                }
            });

            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            waitUntil(Duration.ofSeconds(2), toldAfterThePhotoMoved::get);
        }

        @Test
        void stopAllWatchingStillRetiresWatchersOnceAFolderRootHasGone(@TempDir final Path root) throws IOException {
            writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofSeconds(30));
            final Path prepDir = ((SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join())
                    .job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();
            Files.delete(root.resolve("Library"));

            pipeline.stopAllWatching();

            assertThat(pipeline.isWatchActive(prepDir)).isFalse();
        }

        @Test
        void aWatcherRefusesToAutoResumeOnceAFolderRootHasGone(@TempDir final Path root) throws Exception {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var moveStarted = new CountDownLatch(1);
            final var pipeline = pipeline(root, new RecordingProgressPort(),
                    new BlockingMoveTo(moveStarted, new CountDownLatch(0)), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();
            Files.delete(root.resolve("Library"));
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            // Retiring proves the poll reached the resume, not which way that resume went. A submitted
            // one retires the watcher just as fast, then moves files a moment later on the job's own
            // thread. The latch is what separates them.
            waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
            assertThat(moveStarted.await(WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
            assertThat(Files.exists(photo)).isTrue();
            assertThat(prepDir.resolve("decisions.json")).doesNotExist();

            // The control, and the reason the window above is not a guess. Restoring the root and
            // resuming by hand runs the apply the refusal withheld, and the latch trips inside the same
            // window on the same fixture. A window too short to see a move would fail here rather than
            // pass the assertion above for the wrong reason.
            // Joined rather than left running. The latch trips mid-move, so returning here would race
            // JUnit's own @TempDir delete against a job still writing into it.
            Files.createDirectory(root.resolve("Library"));
            final JobHandle<SiftJobOutcome> control = pipeline.resume(prepDir, false);

            assertThat(moveStarted.await(WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            control.join();
        }

        // A poll that met a shut runner without recognising the refusal would log and keep polling,
        // leaving the watch active here.
        @Test
        void aWatchRetiresOnceTheRunnerIsShut(@TempDir final Path root) throws Exception {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                    Instant.parse("2019-06-01T10:00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            assertThat(pipeline.isWatchActive(prepDir)).isTrue();
            assertThat(pipeline.stopAcceptingJobs(Duration.ofSeconds(5))).isTrue();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
        }

        // The sort is submitted and provably in flight before the shard lands. Otherwise the watcher
        // could take the job slot first, and sort() would throw instead of the test proving anything.
        @Test
        void aWatcherKeepsPollingWhileAnotherJobHoldsTheRunner(@TempDir final Path root) throws Exception {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            writeFile(inboxOf(root).resolve("20210315_other.jpg"), padded("keeper"));
            final var moveStarted = new CountDownLatch(1);
            final var releaseMove = new CountDownLatch(1);
            final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove),
                    defaultSiftSettings(), List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();

            final JobHandle<SortSummary> sorting = pipeline.sort(new SortScope.OldestYear());
            moveStarted.await();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            assertHoldsFor(Duration.ofMillis(200), () -> pipeline.isWatchActive(prepDir));
            releaseMove.countDown();
            sorting.join();

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(Files.exists(photo)).isFalse();
            assertThat(root.resolve("Review/junk/IMG_1.jpg")).exists();
        }

        // The truncated shard below is what a poll landing mid-write sees. A shard file exists from
        // the moment the agent opens it for writing.
        @Test
        void aWatcherWaitsRatherThanResumingWhileAShardIsStillHalfWritten(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            Files.writeString(prepDir.resolve("decisions-001.json"), "{ \"montage\": \"montage-001\", \"decis");

            // A fired watcher retires itself, so staying armed across a window many poll intervals
            // wide is the proof it never fired. Checked continuously rather than once at the end, so a
            // watcher that fired and stopped mid-window cannot slip through.
            assertHoldsFor(Duration.ofMillis(200), () -> pipeline.isWatchActive(prepDir));
            assertThat(Files.exists(photo)).isTrue();

            // The same shard, now complete, is what the next tick sees - so the wait was the file's
            // state, never a watcher that had quietly died.
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }

        @Test
        void aWatcherAppliesAMontageWhoseCorruptSidecarTheUserResolvedWithApplyAnyway(@TempDir final Path root) throws IOException {
            final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                    ":00:00Z"));
            final var pipeline = watchPipeline(root, new RecordingProgressPort(), defaultSiftSettings(),
                    List.of(new ManualModeSieve()), Duration.ofMillis(20));
            final var waiting = (SiftJobOutcome.Waiting) pipeline.sift(new SiftScope.Year(2019, null)).join();
            final Path prepDir = waiting.job().prepDir();
            writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
            Files.writeString(prepDir.resolve("montage-001.json"), "{ not json at all");
            prepDirRemedies(root).resolveCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.APPLY_ANYWAY,
                    AnswerSource.DESKTOP);
            assertThat(listed(pipeline.siftRuns())).singleElement().satisfies(run -> {
                assertThat(run.shards()).isNotNull();
                assertThat(run.shards().valid()).isEqualTo(0);
            });

            pipeline.armWatchesForResumableRuns();

            waitForJobToFinish(pipeline, Duration.ofSeconds(2));
            assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
        }
    }

    private static SpendLedgerEntry spent(final String scope, final long input, final long output,
                                          final RunEnding ending) {
        return new SpendLedgerEntry(Instant.parse("2026-09-01T10:00:00Z"), scope, "anthropic", "a-model",
                224, 5, 1, 0, 1, input, output, ending);
    }

    // A plain unchecked exception, so the guard is proven over the whole unchecked space rather
    // than a list of expected types. Only a folder, which is what the run's own mtime is read
    // from. A sift reads every photo's mtime too, and failing those would stop the run before it
    // reached the read under test.
    private static final class FailingLastModified extends NioMediaStore {

        @Override
        public Instant lastModifiedTime(final Path path) {
            if (Files.isDirectory(path)) {
                throw new IllegalStateException("simulated stat failure");
            }
            return super.lastModifiedTime(path);
        }
    }

    /**
     * Writes a prep dir holding one montage's index, sidecar and shard, standing in for a run
     * something outside this process created. Deliberately whole rather than a bare directory: the
     * shard is what makes overwriting it cost something, and what the refusal is protecting.
     *
     * @param prepDir {@link Path} the prep directory to plant
     * @param photo {@link Path} the photo its single decision names
     */
    private static void plantWaitingRun(final Path prepDir, final Path photo) {
        try {
            Files.createDirectories(prepDir);
            SiftPrepTestSupport.writeIndex(prepDir, 1, List.of("montage-001"));
            SiftPrepTestSupport.writeSidecar(prepDir, "montage-001", SiftPrepTestSupport.sidecarEntry(photo));
            SiftPrepTestSupport.writeShard(prepDir, "montage-001",
                    SiftPrepTestSupport.classificationJson(photo, "junk", "blurry"));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
