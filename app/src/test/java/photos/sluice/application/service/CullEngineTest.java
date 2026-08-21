package photos.sluice.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.job.WatchMode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static photos.sluice.application.service.PipelineTestSupport.BlockingCancellableCuller;
import static photos.sluice.application.service.PipelineTestSupport.BlockingIncompleteCuller;
import static photos.sluice.application.service.PipelineTestSupport.BlockingListFiles;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoveTo;
import static photos.sluice.application.service.PipelineTestSupport.BlockingMoves;
import static photos.sluice.application.service.PipelineTestSupport.FailableIndexReads;
import static photos.sluice.application.service.PipelineTestSupport.FailingListingOfPrepDir;
import static photos.sluice.application.service.PipelineTestSupport.FixedSettings;
import static photos.sluice.application.service.PipelineTestSupport.JunkEverythingCuller;
import static photos.sluice.application.service.PipelineTestSupport.ManualModeCuller;
import static photos.sluice.application.service.PipelineTestSupport.NeverCalledCuller;
import static photos.sluice.application.service.PipelineTestSupport.PlantOnFirstExists;
import static photos.sluice.application.service.PipelineTestSupport.RecordingProgressPort;
import static photos.sluice.application.service.PipelineTestSupport.ThrowingCuller;
import static photos.sluice.application.service.PipelineTestSupport.assertHoldsFor;
import static photos.sluice.application.service.PipelineTestSupport.classificationJson;
import static photos.sluice.application.service.PipelineTestSupport.cullPipeline;
import static photos.sluice.application.service.PipelineTestSupport.defaultCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.inboxOf;
import static photos.sluice.application.service.PipelineTestSupport.padded;
import static photos.sluice.application.service.PipelineTestSupport.pipeline;
import static photos.sluice.application.service.PipelineTestSupport.prepDirRemedies;
import static photos.sluice.application.service.PipelineTestSupport.sortedPhotosDir;
import static photos.sluice.application.service.PipelineTestSupport.waitForJobToFinish;
import static photos.sluice.application.service.PipelineTestSupport.waitUntil;
import static photos.sluice.application.service.PipelineTestSupport.watchCullSettings;
import static photos.sluice.application.service.PipelineTestSupport.watchPipeline;
import static photos.sluice.application.service.PipelineTestSupport.writeFile;
import static photos.sluice.application.service.PipelineTestSupport.writePhoto;
import static photos.sluice.application.service.PipelineTestSupport.writeShard;

class CullEngineTest {

    private final List<Pipeline> armed = new ArrayList<>();

    // Retiring a watcher leaves a poll that is already mid-attempt to finish, resume included, so
    // the run's last write can land after the test method returns. @TempDir deletion then meets an
    // open handle. Draining the runner is what orders the two, and it is the app's own exit
    // sequence rather than something this test invents.
    @AfterEach
    void stopEveryWatcherThisTestArmed() {
        this.armed.forEach(pipeline -> {
            pipeline.stopAllWatching();
            assertThat(pipeline.stopAcceptingJobs(Duration.ofSeconds(10))).isTrue();
        });
    }

    // How long a test waits on a latch that should never trip, proving no apply started. Every use
    // is paired with a control trip inside the same window, so the number is checked by the test
    // rather than picked to feel safe.
    private static final Duration WINDOW = Duration.ofMillis(500);

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
                "started:Sifting...", "finished:Sifting...");
    }

    // MontageRenderer.build() clears a scope's prep dir before writing. Re-running cull() on a
    // scope whose run is still unresolved would destroy every shard already dropped for it.
    @Test
    void cullRefusesToRebuildAScopeThatAlreadyHasAWaitingRun(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                        refusal -> assertThat(refusal.occupant().scope()).isEqualTo("2019"));
        // The dropped shard must have survived the refused call - proves no rebuild/clear happened.
        assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
    }

    // A run whose shards are all in but whose apply has not run holds the most unspent work of any
    // unfinished state. It is a full set of shards somebody paid for, one Resume away from
    // applying. So it refuses like every other one.
    @Test
    void cullRefusesAScopeWhoseRunIsReadyToApply(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.READY);

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                        refusal -> assertThat(refusal.occupant().health().state()).isEqualTo(State.READY));
    }

    // The refusal a guard reading index.json could never make. This dir's index is gone entirely,
    // so nothing it holds can describe itself - and a shard an agent was paid for is sitting right
    // there. Occupancy is presence of files, which is exactly why this case is covered.
    @Test
    void cullRefusesAScopeWhoseIndexIsGoneButWhoseShardsRemain(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        Files.delete(prepDir.resolve("index.json"));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOf(Pipeline.ScopeOccupiedException.class);
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
    }

    // A scope whose own occupancy could not be determined refuses the same direction an occupied one
    // does, but without fabricating a diagnosed occupant to justify it. Answering "empty" instead
    // would let this reach buildFreshAndDispatch(), clearing a directory nobody could confirm held
    // nothing worth losing.
    @Test
    void cullRefusesWithScopeUnreadableRatherThanFabricatingAnOccupantWhenOccupancyCannotBeRead(
            @TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final Path prepDir = root.resolve("logs/sift-prep/2019");
        Files.createDirectories(prepDir);
        Files.writeString(prepDir.resolve("stray.txt"), "something already here");
        final var mediaStore = new FailingListingOfPrepDir(prepDir);
        final var pipeline = pipeline(root, new RecordingProgressPort(), mediaStore, defaultCullSettings(),
                List.of(new ManualModeCuller()));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOfSatisfying(Pipeline.ScopeUnreadableException.class,
                        refusal -> assertThat(refusal.prepDir()).isEqualTo(prepDir));
        // Nothing was cleared - the stray file the fixture planted is still exactly where it was.
        assertThat(Files.exists(prepDir.resolve("stray.txt"))).isTrue();
    }

    // Archive and proceed, with no confirmation asked. Curate resolves its own scope mid-job so no
    // dialog could fire there anyway, and a monthly curate of the current year would meet this
    // occupant every month. Nothing is destroyed: the old record keeps the graveyard's own 30-day
    // window, and the outcome names where it went.
    @Test
    void cullArchivesACompletedRunOfTheSameScopeAndProceeds(@TempDir final Path root) throws IOException {
        final Path first = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(first, "junk", "blurry"));
        pipeline.resume(prepDir, false).join();
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));

        final var second = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();

        final Path graveyard = second.archivedPriorRun();
        assertThat(graveyard).isNotNull();
        assertThat(graveyard.getFileName().toString()).startsWith("2019-");
        // The completed run's own record survives in the graveyard rather than being overwritten.
        assertThat(graveyard.resolve("decisions.json")).exists();
        assertThat(graveyard.resolve("decisions-001.json")).exists();
        // And the fresh run really did run, over the photo the first one never saw.
        assertThat(second.job().shards()).isEqualTo(new ShardTally(0, 0, 1));
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
    }

    // The state a guard reading index.json cannot even name. Nothing here says what the run is, and
    // the refusal has to happen anyway, because a shard an agent was paid for is sitting in there.
    @Test
    void cullRefusesAScopeWhoseRunCannotBeReadAtAll(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var prepStore = new FailableIndexReads();
        final var pipeline = cullPipeline(root, new RecordingProgressPort(), prepStore);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
        prepStore.startFailing();

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOfSatisfying(Pipeline.ScopeOccupiedException.class,
                        refusal -> assertThat(refusal.occupant().health().state()).isEqualTo(State.DAMAGED));
        assertThat(Files.exists(waiting.job().prepDir().resolve("decisions-001.json"))).isTrue();
    }

    // The self-healing half of DAMAGED. A read that merely failed is transient, and no diagnosis is
    // cached, so the run reports its real state the moment the file opens again.
    @Test
    void aTransientlyDamagedRunDiagnosesItsRealStateOnceTheReadSucceedsAgain(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var prepStore = new FailableIndexReads();
        final var pipeline = cullPipeline(root, new RecordingProgressPort(), prepStore);
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        prepStore.startFailing();
        // The control: proves this fixture's failing read actually reaches DAMAGED before trusting
        // the READY assertion below to mean the read succeeding, not the absence of caching alone.
        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.DAMAGED);

        prepStore.stopFailing();
        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.READY);
    }

    // A damaged run never reads as ready, so a watcher armed for it would poll for good. The 30s
    // interval means an armed watcher could not have fired and retired itself before the assertion,
    // so a false reading here means "never armed".
    @Test
    void armWatchesForResumableRunsLeavesADamagedRunAlone(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var prepStore = new FailableIndexReads();
        final var setup = cullPipeline(root, new RecordingProgressPort(), prepStore);
        final Path prepDir = ((CullJobOutcome.Waiting) setup.cull(new CullScope.Year(2019, null)).join())
                .job().prepDir();
        // The same store the scan below reads through, so the run really does diagnose DAMAGED for
        // it. Wiring a fresh store here would leave the index perfectly readable. The run would
        // then arm as WAITING, for reasons unrelated to what this test claims.
        final var watchPipeline = pipeline(root, new RecordingProgressPort(), new NioMediaStore(),
                watchCullSettings(), List.of(new ManualModeCuller()), Duration.ofSeconds(30), prepStore);
        prepStore.startFailing();

        this.armed.add(watchPipeline);
        watchPipeline.armWatchesForResumableRuns();

        assertThat(watchPipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.DAMAGED);
        assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
    }

    // The occupancy question is asked twice, and this is the second ask carrying its own weight.
    // The scope is free when cull() checks synchronously, and taken by the time the job thread
    // claims it. Deleting that second refusal leaves the renderer free to clear the prep dir
    // planted below, shards and all. The first ask cannot catch this fixture, since at the moment
    // it runs there is nothing there to catch.
    @Test
    void cullRefusesAScopeTakenBetweenTheSynchronousCheckAndTheClaim(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final Path prepDir = root.resolve("logs/sift-prep/2019");
        // Planted the first time anything asks whether the prep dir exists, which is the
        // synchronous check's own question. The job thread's claim then finds it there.
        final var planting = new PlantOnFirstExists(prepDir, () -> plantWaitingRun(prepDir, photo));
        final var pipeline = pipeline(root, new RecordingProgressPort(), planting, defaultCullSettings(),
                List.of(new ManualModeCuller()));

        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(Pipeline.ScopeOccupiedException.class);
        assertThat(Files.exists(prepDir.resolve("decisions-001.json"))).isTrue();
    }

    // The archive happens before montage rendering, so every later way a run can end is reachable
    // with a prior record already filed away. Cancelled is the one furthest from Applied, and the
    // branch a refactor drops most easily.
    //
    // The second run needs a photo the first one never saw. Reusing the first run's own single
    // photo leaves nothing for the second run to render. It can then complete before cancellation
    // is even requested, a race that surfaced as Applied on a loaded CI runner.
    //
    // cancelOnFirstTick is the same deterministic hook aCancelledRenderLeavesTheScopeFreeForAnotherCull
    // uses. It fires once real rendering work is genuinely on disk, rather than guessing a moment by
    // thread timing.
    @Test
    void aCancelledRunStillReportsWhereItArchivedThePriorRun(@TempDir final Path root) throws IOException {
        final Path dir = sortedPhotosDir(root, "2019", "06");
        final Path first = writePhoto(dir, "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var progress = new RecordingProgressPort();
        final var pipeline = cullPipeline(root, progress);
        final var firstRun = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
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

        final var handle = pipeline.cull(new CullScope.Year(2019, null));
        cancel.set(handle::requestCancellation);
        handleReady.countDown();
        final CullJobOutcome outcome = handle.join();

        // A cancellation landing after rendering finished resolves to Waiting instead of Cancelled.
        // Every variant carries the archive, so this reads it off the interface rather than picking
        // a case. The claim is about the archive, never about which outcome won.
        final Path graveyard = outcome.archivedPriorRun();
        assertThat(outcome).isInstanceOfAny(CullJobOutcome.Cancelled.class, CullJobOutcome.Waiting.class);
        assertThat(graveyard).isNotNull();
        assertThat(graveyard.resolve("decisions.json")).exists();
    }

    // The renderer clearing its own partial output exists so a cancelled run leaves its scope
    // claimable. That is one layer up from the renderer's own test, which only proves the directory
    // is gone.
    // Two photos at one montage each, so the render really does write montage-001 before stopping.
    // A single photo would leave either no directory or an empty one, and an empty dir never
    // occupies a scope anyway. The assertion would then hold with the cleanup deleted.
    @Test
    void aCancelledRenderLeavesTheScopeFreeForAnotherCull(@TempDir final Path root) throws IOException {
        final Path dir = sortedPhotosDir(root, "2019", "06");
        writePhoto(dir, "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(dir, "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var progress = new RecordingProgressPort();
        // Cancelled the moment montage-001's write is reported, so the stop lands between the first
        // montage reaching disk and the second starting. That is the only window in which a partial
        // prep dir exists, and ticking on a real write makes hitting it deterministic.
        // The latch closes the one race left. The tick can fire before cull() has even returned the
        // handle, so the hook waits for the test to hand it over rather than reading a null.
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
        final var pipeline = cullPipeline(root, progress);

        final var handle = pipeline.cull(new CullScope.Year(2019, null));
        cancel.set(handle::requestCancellation);
        handleReady.countDown();
        final CullJobOutcome first = handle.join();

        assertThat(first).isInstanceOf(CullJobOutcome.Cancelled.class);
        assertThat(root.resolve("logs/sift-prep/2019")).doesNotExist();
        assertThat(pipeline.cull(new CullScope.Year(2019, null)).join())
                .isInstanceOf(CullJobOutcome.Waiting.class);
    }

    // A cancellation landing right after prep finishes but before dispatch starts. NeverCalledCuller
    // fails the test outright if dispatch runs at all, proving the run never reaches it.
    @Test
    void aCancellationRightAfterPrepFinishesStopsDispatchFromEverRunning(@TempDir final Path root) throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var progress = new RecordingProgressPort();
        // The tick can fire before cull() has even returned the handle. The hook waits (bounded, so
        // a broken wiring fails fast instead of hanging the suite) for the test to hand it over
        // rather than reading a null.
        final var handleReady = new CountDownLatch(1);
        final var cancel = new AtomicReference<Runnable>(() -> {});
        progress.cancelWhenPhaseFinishes("Building montages...", () -> {
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
        final var pipeline = cullPipeline(root, progress, defaultCullSettings(), List.of(new NeverCalledCuller()));

        final JobHandle<CullJobOutcome> handle = pipeline.cull(new CullScope.Year(2019, null));
        cancel.set(handle::requestCancellation);
        handleReady.countDown();
        final CullJobOutcome outcome = handle.join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        final WaitingCullJob job = ((CullJobOutcome.Waiting) outcome).job();
        assertThat(job.shards()).isEqualTo(new ShardTally(0, 0, 1));
        assertThat(progress.events).noneMatch(event -> event.startsWith("started:Culling"));
        assertThat(pipeline.isWatchActive(job.prepDir())).isFalse();
    }

    // A run that never had to archive anything says so, rather than leaving a caller to guess
    // whether null means "nothing was there" or "nobody looked".
    @Test
    void cullOverAFreeScopeReportsNoArchivedPriorRun(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());

        final var outcome = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();

        assertThat(outcome.archivedPriorRun()).isNull();
    }

    @Test
    void cullRunsIsEmptyWhenNoCullHasEverRun(@TempDir final Path root) {
        assertThat(cullPipeline(root, new RecordingProgressPort()).cullRuns()).isEmpty();
    }

    @Test
    void cullRunsListsAPrepDirStillMissingShardsAsWaiting(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        pipeline.cull(new CullScope.Year(2019, null)).join();

        final List<CullRunSummary> runs = pipeline.cullRuns();

        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().scope()).isEqualTo("2019");
        assertThat(runs.getFirst().health().state()).isEqualTo(State.WAITING);
        assertThat(runs.getFirst().shards()).isEqualTo(new ShardTally(0, 0, 1));
    }

    // An applied run stays on disk until the user purges it, so it stays listed. COMPLETE is what
    // separates it from a run still owing somebody something, and it is the state a re-cull of the
    // same scope archives rather than refuses.
    @Test
    void cullRunsStillListsAnAppliedRunAsComplete(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));
        pipeline.resume(waiting.job().prepDir(), false).join();

        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.COMPLETE);
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
    // "Sifting..." bracket is the second half of the same proof: no phase ran for it either.
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

    // A resume carries a prep dir its caller chose earlier, and the folder roots can move in
    // between. The run here is complete and would otherwise apply, so the refusal is what stops it
    // rather than anything missing from the prep dir. Nothing is read and nothing moves, so the run
    // stays exactly as it was and pointing the working root back at it makes it resumable again.
    @Test
    void resumeRefusesARunOutsideTheWorkingRootInForce(@TempDir final Path root, @TempDir final Path movedTo)
            throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                Instant.parse("2019-06-01T10:00:00Z"));
        final var preparing = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) preparing.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        final var moved = cullPipeline(movedTo, new RecordingProgressPort());

        final JobHandle<CullJobOutcome> refused = moved.resume(prepDir, false);

        assertThatThrownBy(refused::join)
                .isInstanceOf(CompletionException.class)
                .cause()
                // A surface needs the refused run to name it and offer a way back. Carrying it is
                // what saves that surface parsing it back out of the message.
                .asInstanceOf(type(Pipeline.RunOutsideWorkingRootException.class))
                .extracting(Pipeline.RunOutsideWorkingRootException::prepDir)
                .isEqualTo(prepDir);
        assertThat(Files.exists(photo)).isTrue();
        assertThat(prepDir.resolve("decisions.json")).doesNotExist();
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

        final var blocked = new CullJobOutcome.Blocked(job, mutable, null);
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
        final var settings = new FixedSettings("anthropic", List.of(CullCategory.of("junk", "objectively worthless " +
                "shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
        final var pipeline = cullPipeline(root, progress, settings, List.of(new ThrowingCuller("anthropic")));

        final var handle = pipeline.cull(new CullScope.Year(2019, null));

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CullException.class);
        assertThat(progress.events).contains("finished:Sifting...");
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
                List.of(CullCategory.of("junk", "objectively worthless shots")),
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

    // Proves the armWatchIfConfigured() provider gate. armWatchesForResumableRuns()'s
    // startup scan walks every waiting job on disk regardless of which provider produced it.
    // dispatchAndApply()'s own call site is different: it can only reach armWatchIfConfigured() when
    // the provider already matches. So this scan is the one call site the gate actually changes
    // behavior at. Without it, a leftover mode=WATCH setting would arm a phantom watcher for this
    // automated provider's own cancelled prep dir, risking an unasked-for, API-spending auto-resume.
    @Test
    void armWatchesForResumableRunsNeverArmsAWatcherForAnAutomatedProvidersRun(@TempDir final Path root)
            throws Exception {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_2.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final var firstShardWritten = new CountDownLatch(1);
        final var releaseCull = new CountDownLatch(1);
        final var manualSettings = new FixedSettings("auto-approve",
                List.of(CullCategory.of("junk", "objectively worthless shots")),
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
                List.of(CullCategory.of("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH));
        final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchSettings, List.of(),
                Duration.ofMillis(20));

        this.armed.add(watchPipeline);
        watchPipeline.armWatchesForResumableRuns();

        assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
    }

    // MontageRenderer.build() (CullMontageRenderer) is itself cancellation-aware: it checks the
    // signal before rendering each candidate, entirely before the prep dir is ever cleared or
    // index.json is written. BlockingListFiles synchronizes the test with the exact moment
    // CullMontageRenderer is scanning Sorted for candidates, mid-render. "Block the slow real
    // call, request cancellation while blocked" is the same technique the sort/curate boundary
    // tests use. A null PrepDir return means nothing is resumable yet. buildFreshAndDispatch()
    // therefore resolves to CullJobOutcome.Cancelled rather than Waiting, proven here by the
    // whole sift-prep dir never existing at all. NeverCalledCuller fails the test outright if
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
        assertThat(Files.exists(root.resolve("logs/sift-prep/2019"))).isFalse();
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
                List.of(CullCategory.of("junk", "objectively worthless shots")),
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

        waitForJobToFinish(pipeline, Duration.ofSeconds(2));
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
    // instance for the run at all. armWatchesForResumableRuns() has to discover it on disk, purely
    // from what diagnosing the sift-prep root turns up.
    // The shard is dropped before the restart, so this run is READY rather than WAITING when it is
    // found. That is the ordinary shape of the case: the agent finished while the app was closed.
    @Test
    void armWatchesForResumableRunsAutoResumesARunItNeverStartedItself(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var manualPipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) manualPipeline.cull(new CullScope.Year(2019, null)).join();
        writeShard(waiting.job().prepDir(), "montage-001", classificationJson(photo, "junk", "blurry"));

        final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        this.armed.add(watchPipeline);
        watchPipeline.armWatchesForResumableRuns();

        waitUntil(Duration.ofSeconds(2), () -> !Files.exists(photo));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // A blocked run would resume once and block again on the same findings. Arming it buys a full
    // validation pass at every launch, for a verdict only the user can change.
    // The poll interval below is long enough that an armed watcher could not have fired and retired
    // itself before the assertion. So a false reading here means "never armed", never "already
    // finished".
    @Test
    void armWatchesForResumableRunsLeavesABlockedRunAlone(@TempDir final Path root) throws IOException {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var manualPipeline = cullPipeline(root, new RecordingProgressPort());
        final var waiting = (CullJobOutcome.Waiting) manualPipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));
        // montage-002 belongs to no montage in the index, so the whole-batch gate refuses.
        writeShard(prepDir, "montage-002", classificationJson(photo, "junk", "blurry"));
        assertThat(manualPipeline.cullRuns()).singleElement()
                .extracting(run -> run.health().state()).isEqualTo(State.BLOCKED);

        final var watchPipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        this.armed.add(watchPipeline);
        watchPipeline.armWatchesForResumableRuns();

        assertThat(watchPipeline.isWatchActive(prepDir)).isFalse();
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

        waitForJobToFinish(pipeline, Duration.ofSeconds(2));
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
        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(CullRunSummary::prepDir).isEqualTo(prepDir);
        assertThatThrownBy(() -> pipeline.cull(new CullScope.Year(2019, null)))
                .isInstanceOf(Pipeline.ScopeOccupiedException.class);
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
                List.of(CullCategory.of("junk", "objectively worthless shots")),
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

    // A dir whose index.json is unparseable is listed, not dropped, and it does not take the rest
    // of the scan down with it. Dropping it would hide the run most in need of attention, at the
    // moment it needs it. Its tally is null because a tally counts montages, and the montage list
    // is the one thing that could not be read.
    @Test
    void cullRunsListsACorruptIndexAsBlockedAlongsideTheHealthyRun(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2020", "06"), "IMG_2.jpg", Instant.parse("2020-06-01T10:00:00Z"));
        final var pipeline = cullPipeline(root, new RecordingProgressPort());
        final var damaged = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final var healthy = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2020, null)).join();
        Files.writeString(damaged.job().prepDir().resolve("index.json"), "{ not json at all");

        final List<CullRunSummary> runs = pipeline.cullRuns();

        assertThat(runs).extracting(CullRunSummary::prepDir)
                .containsExactly(damaged.job().prepDir(), healthy.job().prepDir());
        assertThat(runs.getFirst().health().state()).isEqualTo(State.BLOCKED);
        assertThat(runs.getFirst().health().findings())
                .containsExactly(new Finding.CorruptIndex(damaged.job().prepDir().resolve("index.json")));
        assertThat(runs.getFirst().shards()).isNull();
        assertThat(runs.getLast().health().state()).isEqualTo(State.WAITING);
        assertThat(runs.getLast().shards()).isEqualTo(new ShardTally(0, 0, 1));
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

        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(CullRunSummary::shards).isEqualTo(new ShardTally(1, 0, 1));
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

        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(CullRunSummary::shards).isEqualTo(new ShardTally(1, 0, 1));
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

        assertThat(pipeline.cullRuns()).singleElement()
                .extracting(CullRunSummary::shards).isEqualTo(new ShardTally(1, 1, 1));
        assertThat(Files.exists(photo)).isTrue();
        assertThat(Files.exists(prepDir.resolve("decisions.json"))).isFalse();
    }

    @Test
    void stopAllWatchingRetiresEveryArmedWatcher(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        writePhoto(sortedPhotosDir(root, "2020", "06"), "IMG_2.jpg", Instant.parse("2020-06-01T10:00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        final Path first = ((CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join())
                .job().prepDir();
        final Path second = ((CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2020, null)).join())
                .job().prepDir();
        assertThat(pipeline.isWatchActive(first)).isTrue();
        assertThat(pipeline.isWatchActive(second)).isTrue();

        pipeline.stopAllWatching();

        assertThat(pipeline.isWatchActive(first)).isFalse();
        assertThat(pipeline.isWatchActive(second)).isFalse();
    }

    @Test
    void stopAllWatchingStillRetiresWatchersOnceAFolderRootHasGone(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofSeconds(30));
        final Path prepDir = ((CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join())
                .job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        Files.delete(root.resolve("Library"));

        pipeline.stopAllWatching();

        assertThat(pipeline.isWatchActive(prepDir)).isFalse();
    }

    @Test
    void watchModeRefusesToAutoResumeOnceAFolderRootHasGone(@TempDir final Path root) throws Exception {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        final var moveStarted = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(),
                new BlockingMoveTo(moveStarted, new CountDownLatch(0)), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        Files.delete(root.resolve("Library"));
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        // Retiring proves the poll reached the resume, not which way that resume went. A submitted
        // one retires the watcher just as fast, then moves files on the job's own thread a moment
        // later. The latch is what separates them.
        waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
        assertThat(moveStarted.await(WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isFalse();
        assertThat(Files.exists(photo)).isTrue();
        assertThat(prepDir.resolve("decisions.json")).doesNotExist();

        // The control, and the reason the window above is not a guess. Restoring the root and
        // resuming by hand runs the apply the refusal withheld, and the latch trips inside the same
        // window on the same fixture. A window too short to see a move would fail here rather than
        // pass the assertion above for the wrong reason.
        // Joined, not left running: the latch trips mid-move, so returning here would race JUnit's
        // own @TempDir delete against a job still writing into it.
        Files.createDirectory(root.resolve("Library"));
        final JobHandle<CullJobOutcome> control = pipeline.resume(prepDir, false);

        assertThat(moveStarted.await(WINDOW.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        control.join();
    }

    // Retiring is the whole proof, and it discriminates. A poll that met a shut runner without
    // recognising the refusal would log and keep polling, leaving the watch active here.
    @Test
    void watchModeRetiresItsWatcherOnceTheRunnerIsShut(@TempDir final Path root) throws Exception {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg",
                Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = watchPipeline(root, new RecordingProgressPort(), watchCullSettings(),
                List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
        final Path prepDir = waiting.job().prepDir();
        assertThat(pipeline.isWatchActive(prepDir)).isTrue();
        assertThat(pipeline.stopAcceptingJobs(Duration.ofSeconds(5))).isTrue();
        writeShard(prepDir, "montage-001", classificationJson(photo, "junk", "blurry"));

        waitUntil(Duration.ofSeconds(2), () -> !pipeline.isWatchActive(prepDir));
    }

    // The sort is submitted and provably in flight before the shard lands. Otherwise the watcher
    // could take the job slot first, and sort() would throw instead of the test proving anything.
    @Test
    void watchModeKeepsPollingWhileAnotherJobHoldsTheRunner(@TempDir final Path root) throws Exception {
        final Path photo = writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10" +
                ":00:00Z"));
        writeFile(inboxOf(root).resolve("20210315_other.jpg"), padded("keeper"));
        final var moveStarted = new CountDownLatch(1);
        final var releaseMove = new CountDownLatch(1);
        final var pipeline = pipeline(root, new RecordingProgressPort(), new BlockingMoves(moveStarted, releaseMove),
                watchCullSettings(), List.of(new ManualModeCuller()), Duration.ofMillis(20));
        final var waiting = (CullJobOutcome.Waiting) pipeline.cull(new CullScope.Year(2019, null)).join();
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

        waitForJobToFinish(pipeline, Duration.ofSeconds(2));
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
        // The tally still reads the montage as invalid - only the whole-batch pass knows to trust
        // its shard as its own scope. Readiness is what ignores that, which is the claim below.
        assertThat(pipeline.cullRuns()).singleElement().satisfies(run -> {
            assertThat(run.shards()).isNotNull();
            assertThat(run.shards().valid()).isEqualTo(0);
        });

        pipeline.startWatching(prepDir);

        waitForJobToFinish(pipeline, Duration.ofSeconds(2));
        assertThat(Files.exists(root.resolve("Review/junk/IMG_1.jpg"))).isTrue();
    }

    // buildWaitingJob's own guard against an unstattable prep dir, mirroring
    // PrepDirDoctor.lastModifiedOrEpoch for the job-resolution path rather than the dashboard.
    // Without it, a stat failure here replaces a legitimate Waiting outcome with a crash.
    @Test
    void cullStillReturnsWaitingWhenTheJobsMtimeCannotBeRead(@TempDir final Path root) throws IOException {
        writePhoto(sortedPhotosDir(root, "2019", "06"), "IMG_1.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final var pipeline = pipeline(root, new RecordingProgressPort(), new FailingLastModified(),
                defaultCullSettings(), List.of(new ManualModeCuller()));

        final CullJobOutcome outcome = pipeline.cull(new CullScope.Year(2019, null)).join();

        assertThat(outcome).isInstanceOf(CullJobOutcome.Waiting.class);
        assertThat(((CullJobOutcome.Waiting) outcome).job().since()).isEqualTo(Instant.EPOCH);
    }

    // A stat that fails with a plain unchecked exception - the guard holds for the whole
    // unchecked space, not a list of expected types.
    private static final class FailingLastModified extends NioMediaStore {

        @Override
        public Instant lastModifiedTime(final Path path) {
            throw new IllegalStateException("simulated stat failure");
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
            CullPrepTestSupport.writeIndex(prepDir, 1, List.of("montage-001"));
            CullPrepTestSupport.writeSidecar(prepDir, "montage-001", CullPrepTestSupport.sidecarEntry(photo));
            CullPrepTestSupport.writeShard(prepDir, "montage-001",
                    CullPrepTestSupport.classificationJson(photo, "junk", "blurry"));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
