package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
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
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.WatchMode;

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
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

// The real-adapter wiring factory and every fake shared by PipelineTest/CullEngineTest/
// CurateEngineTest. Each of those three files pulls in what it needs through explicit static
// imports, so a test body reads as though the helper were declared locally.
final class PipelineTestSupport {

    private PipelineTestSupport() {
    }

    static void waitUntil(final Duration timeout, final BooleanSupplier condition) {
        final Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                // The busy-wait this polls for is a real background CullWatcher/JobRunner thread,
                // not something this test can await via a latch or callback.
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // waitUntil's negative counterpart, for proving a background thread did NOT act. The condition
    // is re-checked for the whole window rather than once at the end, so a state that breaks and
    // recovers mid-window still fails. The window has to be several poll intervals wide to give the
    // thread real chances to act.
    static void assertHoldsFor(final Duration window, final BooleanSupplier condition) {
        final Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError("condition stopped holding within " + window);
            }
            try {
                // Throttles this checking loop; the thing being watched is a real background
                // CullWatcher thread, with no latch or callback to await instead.
                //noinspection BusyWait
                Thread.sleep(5);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static Path inboxOf(final Path root) {
        return root.resolve("Inbox");
    }

    // Every run still owing somebody something - anything but COMPLETE. An applied run stays on
    // disk until purged, so cullRuns() keeps listing it and "no runs at all" would never come true.
    // A test waiting for an apply to land waits on this going empty. Waiting on a moved file
    // instead would pass part way through, before decisions.json makes the run COMPLETE.
    static List<CullRunSummary> unresolvedRuns(final Pipeline pipeline) {
        return pipeline.cullRuns().stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .toList();
    }

    static Path sortedPhotosDir(final Path root, final String year, final String month) {
        return root.resolve("Sorted").resolve("Photos").resolve(year).resolve(month);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore());
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    // cull()/cullRuns()/resume() tests always go through this name, wiring the same manual-mode
    // default (a fake external-agent-shaped VisionCuller) unless a test needs to vary the provider.
    static Pipeline cullPipeline(final Path root, final RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore(), defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    static Pipeline cullPipeline(final Path root, final RecordingProgressPort progress, final CullSettings cullSettings,
                                 final List<VisionCuller> cullers) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers);
    }

    // curate() tests go through this name, wiring AutoApproveCuller as the configured provider.
    // curate() runs prep/dispatch/apply in one call, with no gap to hand-drop a shard into the way
    // the manual-mode cull() tests above do.
    static Pipeline curatePipeline(final Path root, final RecordingProgressPort progress) {
        return curatePipeline(root, progress, new NioMediaStore());
    }

    static Pipeline curatePipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, autoApproveCullSettings(), List.of(new AutoApproveCuller()));
    }

    static CullSettings autoApproveCullSettings() {
        return new FixedSettings("auto-approve", List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
    }

    // Watch-mode tests go through this name: same wiring, but with a millisecond-scale poll
    // interval (via Pipeline's package-private test constructor). A real auto-resume proves out
    // fast this way, instead of waiting on the production 2-second cadence.
    static Pipeline watchPipeline(final Path root, final RecordingProgressPort progress,
                                  final CullSettings cullSettings,
                                  final List<VisionCuller> cullers, final Duration pollInterval) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers, pollInterval);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final CullSettings cullSettings, final List<VisionCuller> cullers) {
        return pipeline(root, progress, mediaStore, cullSettings, cullers, null);
    }

    // A prep-dir reader that can be told to start failing its index reads, standing in for a file
    // held open by a backup or antivirus process. Reaching that state through the filesystem
    // instead would mean testing the OS. A directory in place of the file fails at the open on
    // Windows and at the first read on Linux, which are different clauses in the reader.
    static final class FailableIndexReads implements CullPrepPort {

        private final CullPrepPort delegate = new JsonCullPrepStore();
        private boolean failing;

        void startFailing() {
            this.failing = true;
        }

        // The self-healing half of the fixture above. The bytes were never touched, so the very
        // next read succeeds again, the same way a backup or antivirus handle releasing a locked
        // file does.
        void stopFailing() {
            this.failing = false;
        }

        @Override
        public PrepDir readIndex(final Path prepDir) {
            if (this.failing) {
                throw new UncheckedIOException(new IOException("simulated read failure"));
            }
            return this.delegate.readIndex(prepDir);
        }

        @Override
        public void writeIndex(final Path prepDir, final PrepDir index) {
            this.delegate.writeIndex(prepDir, index);
        }

        @Override
        public List<SidecarPhotoEntry> readSidecar(final Path prepDir, final String montage) {
            return this.delegate.readSidecar(prepDir, montage);
        }

        @Override
        public boolean hasShard(final Path prepDir, final String montage) {
            return this.delegate.hasShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShard(final Path prepDir, final String montage) {
            return this.delegate.readShard(prepDir, montage);
        }

        @Override
        public DecisionShard readShardFile(final Path shardFile) {
            return this.delegate.readShardFile(shardFile);
        }

        @Override
        public void writeMergedDecisions(final Path prepDir, final String scope, final List<Decision> decisions,
                                         final ApplyReport report) {
            this.delegate.writeMergedDecisions(prepDir, scope, decisions, report);
        }
    }

    // Runs onFirstQuery once, the first time anything asks whether target exists. The occupancy
    // check's own exists() call is that question, so this plants a state change squarely between
    // cull()'s synchronous ask and claimScope()'s ask on the job thread. The real case is something
    // outside this process creating a prep dir during a long sort. JobRunner's single slot stops
    // another in-app job doing it, but nothing stops the user or a sync client.
    static final class PlantOnFirstExists implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final Path target;
        private final Runnable onFirstQuery;
        private boolean fired;

        PlantOnFirstExists(final Path target, final Runnable onFirstQuery) {
            this.target = target;
            this.onFirstQuery = onFirstQuery;
        }

        @Override
        public boolean exists(final Path path) {
            final boolean result = this.delegate.exists(path);
            if (!this.fired && path.equals(this.target)) {
                this.fired = true;
                this.onFirstQuery.run();
            }
            return result;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            return this.delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Fails listFiles() for exactly one target path. Stands in for a scope's own occupancy check
    // failing - a locked disaster-drawer file, say. Every other read behaves normally.
    static final class FailingListingOfPrepDir extends NioMediaStore {

        private final Path target;

        FailingListingOfPrepDir(final Path target) {
            this.target = target;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            if (root.equals(this.target)) {
                throw new IllegalStateException("simulated listing failure");
            }
            return super.listFiles(root);
        }
    }

    // The one full wiring every overload above funnels into - real adapters throughout (matching
    // this project's no-mocks test convention), same as the engines below. CullMontageRenderer's
    // HeifDecoder dependency is stubbed to always miss: none of these fixtures are HEIC/AVIF, and
    // real HEIC/AVIF decode already has its own coverage in TileRendererTest. pollInterval null
    // means "use Pipeline's own production default" - only watchPipeline() ever passes one.
    // Same wiring, with the prep-dir reader swapped out. Only a test that needs a read to fail at a
    // seam this code owns passes one.
    static Pipeline cullPipeline(final Path root, final RecordingProgressPort progress,
                                 final CullPrepPort cullPrepPort) {
        return pipeline(root, progress, new NioMediaStore(), defaultCullSettings(), List.of(new ManualModeCuller()),
                null, cullPrepPort);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final CullSettings cullSettings, final List<VisionCuller> cullers,
                             final @Nullable Duration pollInterval) {
        return pipeline(root, progress, mediaStore, cullSettings, cullers, pollInterval, new JsonCullPrepStore());
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final CullSettings cullSettings, final List<VisionCuller> cullers,
                             final @Nullable Duration pollInterval, final CullPrepPort cullPrepPort) {
        final Path libraryRoot = createDirectory(root.resolve("Library"));
        final Path inbox = createDirectory(root.resolve("Inbox"));
        final var settings = SettingsFixture.holder(root, libraryRoot, inbox);
        final var pathsConfig = new PathsConfig(settings);
        final var pathValidation = new PathValidationService(mediaStore, settings);
        final var hashIndex = new CsvLibraryHashIndex(pathsConfig);
        final var sha256Port = new Sha256Hasher();

        final var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        final var sortEngine = new SortEngine(pathsConfig, new InboxScanner(), dateResolver, sha256Port, hashIndex,
                new ImageDimensionsReader(), mediaStore);
        final var commitEngine = new CommitEngine(pathsConfig, mediaStore, sha256Port, hashIndex);
        final var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        final var rescueEngine = new RescueEngine(pathsConfig, mediaStore, sha256Port, hashIndex, rescueDateResolver);

        final HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        final var montageRenderer = new CullMontageRenderer(new TileRenderer(stubHeifDecoder), new MontageBuilder(),
                new SidecarWriter(), new PrepIndexWriter(), mediaStore, pathsConfig, cullSettings);
        final var cullDispatcher = new CullDispatcher(cullers, cullSettings);
        final var disasterDrawer = new DisasterDrawer(mediaStore);
        final var moveLedger = new MoveLedger(mediaStore, disasterDrawer);
        final var cullDestinations = new CullDestinations(pathsConfig);
        final var applyPlanner = new ApplyPlanner(mediaStore, cullPrepPort, sha256Port, pathsConfig);
        final var applyEngine = new ApplyEngine(mediaStore, cullPrepPort, sha256Port, hashIndex, cullDestinations,
                moveLedger, applyPlanner);
        final var reconcileEngine = new ReconcileEngine(mediaStore, cullPrepPort, sha256Port, disasterDrawer,
                cullDestinations, moveLedger, applyPlanner);
        final var prepDirRemedies = new PrepDirRemedies(mediaStore, cullPrepPort, pathsConfig, cullSettings,
                disasterDrawer, moveLedger);
        final var prepDirDoctor = new PrepDirDoctor(cullPrepPort, mediaStore, applyPlanner, moveLedger);
        final var troubleshooter = new Troubleshooter(prepDirDoctor, reconcileEngine, prepDirRemedies, disasterDrawer);
        if (pollInterval == null) {
            return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                    prepDirRemedies, cullPrepPort, cullSettings, mediaStore, pathsConfig,
                    new JobRunner(), progress, disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner,
                    moveLedger, pathValidation);
        }
        return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                prepDirRemedies, cullPrepPort, cullSettings, mediaStore, pathsConfig, new JobRunner(),
                progress, disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner, moveLedger, pathValidation,
                pollInterval);
    }

    // The folder roots have to be there for the pipeline's own path check to pass, the same way a
    // real install's are.
    private static Path createDirectory(final Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create " + directory, e);
        }
    }

    // The same PrepDirRemedies the pipeline() factory above wires into its own Pipeline, built
    // standalone here. It lets a test record a user's troubleshooting answer against a prep dir and
    // then watch the pipeline honour it. Pipeline exposes troubleshoot() but not the individual
    // CHOICE remedies, which a troubleshoot screen calls directly.
    static PrepDirRemedies prepDirRemedies(final Path root) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
        final var mediaStore = new NioMediaStore();
        final var disasterDrawer = new DisasterDrawer(mediaStore);
        return new PrepDirRemedies(mediaStore, new JsonCullPrepStore(), pathsConfig, defaultCullSettings(),
                disasterDrawer, new MoveLedger(mediaStore, disasterDrawer));
    }

    static CullSettings defaultCullSettings() {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL));
    }

    static CullSettings watchCullSettings() {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH));
    }

    static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // 60,000 bytes clears LowResGate's 50KB threshold, same fixture convention as SortEngineTest -
    // sort's progress-bracket tests aren't testing low-res routing and shouldn't accidentally
    // exercise it.
    static String padded(final String marker) {
        return marker + "x".repeat(60_000);
    }

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable -
    // same fixture convention as CullMontageRendererTest.
    static final int PHOTO_WIDTH = 800;
    static final int PHOTO_HEIGHT = 600;

    static Path writePhoto(final Path dir, final String name, final Instant mtime) throws IOException {
        Files.createDirectories(dir);
        final var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, PHOTO_WIDTH, PHOTO_HEIGHT);
        } finally {
            g.dispose();
        }
        final Path file = dir.resolve(name);
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
    static Path writeInboxPhoto(final Path root, final String name) throws IOException {
        return writeInboxPhoto(root, name, 42);
    }

    // seed varies the noise, so two calls in the same test never produce byte-identical files that
    // ByteIdenticalDedup would then collapse into one.
    static Path writeInboxPhoto(final Path root, final String name, final long seed) throws IOException {
        final Path file = inboxOf(root).resolve(name);
        Files.createDirectories(file.getParent());
        final var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        final var random = new Random(seed);
        for (int y = 0; y < PHOTO_HEIGHT; y++) {
            for (int x = 0; x < PHOTO_WIDTH; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ImageIO.write(image, "jpg", file.toFile());
        return file;
    }

    // Hand-drops a shard the same shape a real external agent would write, matching
    // ApplyEngineTest's own writeShard/classificationJson convention. ShardCodec itself is
    // package-private to adapter.vision and unreachable from here.
    static void writeShard(final Path prepDir, final String montage, final String... decisionsJson) throws IOException {
        final String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    static String classificationJson(final Path file, final String category, final String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(jsonEscaped(file), category, reason);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    static final class RecordingProgressPort implements ProgressPort {
        final List<String> events = new ArrayList<>();
        // Runs once, on the first tick of any phase. This is how a test cancels a job at an exact
        // point in its own progress. The engine reports a tick only once the work that tick counts
        // is genuinely on disk, so it is a real signal rather than a guessed moment.
        private @Nullable Runnable onFirstTick;
        // Runs once, the first time the named phase reports finished. PhaseRunner.run() fires
        // phaseFinished in a finally, right after the engine call returns and before control moves
        // on to whatever the caller does next. That is how a test lands a real cancellation in the
        // exact window between one phase ending and the next one starting.
        private @Nullable Runnable onPhaseFinished;
        private @Nullable String finishedPhase;

        void cancelOnFirstTick(final Runnable action) {
            this.onFirstTick = action;
        }

        void cancelWhenPhaseFinishes(final String phase, final Runnable action) {
            this.finishedPhase = phase;
            this.onPhaseFinished = action;
        }

        @Override
        public void phaseStarted(final String phase) {
            this.events.add("started:" + phase);
        }

        @Override
        public void tick(final String phase, final int current, final int total) {
            this.events.add("tick:" + phase + ":" + current + "/" + total);
            if (this.onFirstTick != null) {
                final Runnable action = this.onFirstTick;
                this.onFirstTick = null;
                action.run();
            }
        }

        @Override
        public void phaseFinished(final String phase) {
            this.events.add("finished:" + phase);
            if (this.onPhaseFinished != null && phase.equals(this.finishedPhase)) {
                final Runnable action = this.onPhaseFinished;
                this.onPhaseFinished = null;
                action.run();
            }
        }
    }

    // Wraps the real NioMediaStore but always throws on move() - simulates an engine call that dies
    // mid-phase, to prove Pipeline still brackets phaseFinished on the failure path.
    static final class FailingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            throw new RuntimeException("simulated crash");
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Wraps the real NioMediaStore but blocks the first move() call between two latches. A test can
    // synchronize with the exact moment SortEngine is mid-move this way. That's real observable
    // proof it hasn't returned yet, not a guessed sleep long enough to "probably" still be running.
    static final class BlockingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CountDownLatch moveStarted;
        private final CountDownLatch releaseMove;

        BlockingMoves(final CountDownLatch moveStarted, final CountDownLatch releaseMove) {
            this.moveStarted = moveStarted;
            this.releaseMove = releaseMove;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            this.moveStarted.countDown();
            try {
                this.releaseMove.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return this.delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Wraps the real NioMediaStore but blocks the first listFiles() call between two latches - the
    // call CullMontageRenderer.collectCandidates() makes while scanning Sorted for candidates, mid-
    // PREPPING. Lets a test synchronize a cancellation request with that exact moment, the same
    // technique BlockingMoves above gives the sort/routing loop.
    static final class BlockingListFiles implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CountDownLatch listStarted;
        private final CountDownLatch releaseList;

        BlockingListFiles(final CountDownLatch listStarted, final CountDownLatch releaseList) {
            this.listStarted = listStarted;
            this.releaseList = releaseList;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            this.listStarted.countDown();
            try {
                this.releaseList.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return this.delegate.listFiles(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            return this.delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Wraps the real NioMediaStore but blocks the first moveTo() call between two latches -
    // ApplyEngine.recordThenMove()'s own move step. Lets a test synchronize a real cancellation
    // with the exact moment a decision's move is in flight, the same technique BlockingMoves gives
    // SortEngine/CommitEngine/RescueEngine's own move() call.
    static final class BlockingMoveTo implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CountDownLatch moveStarted;
        private final CountDownLatch releaseMove;

        BlockingMoveTo(final CountDownLatch moveStarted, final CountDownLatch releaseMove) {
            this.moveStarted = moveStarted;
            this.releaseMove = releaseMove;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path move(final Path source, final Path destDir) {
            return this.delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination) {
            this.moveStarted.countDown();
            try {
                this.releaseMove.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return this.delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(final Path source, final Path destDir) {
            return this.delegate.copy(source, destDir);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Stands in for the real (package-private, unreachable from here) ExternalAgentCuller. Only a
    // completeness check, gating on hasShard() alone rather than full shard validation. That
    // validation is ShardValidator/ApplyEngine's job, already covered by their own tests - and by
    // CullEngine's own tally (ShardTallyCalculator), which runs real ShardValidator logic
    // independently of this fake.
    static final class ManualModeCuller implements VisionCuller {
        @Override
        public String id() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
            final List<String> missing = new ArrayList<>();
            int done = 0;
            for (final String montage : prep.entries()) {
                final boolean hasShard = Files.exists(prep.prepDir().resolve(
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

    // The same manual-mode "not complete yet" pause as ManualModeCuller above, but blocking
    // first. That lets a test synchronize a real cancellation with the exact moment dispatch is
    // in flight, before it throws the CullException. A real external-agent provider would throw
    // that same exception for a genuinely incomplete shard set.
    record BlockingIncompleteCuller(CountDownLatch started, CountDownLatch release) implements VisionCuller {
        @Override
        public String id() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
            this.started.countDown();
            try {
                this.release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            throw new CullException("still incomplete");
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
    static final class AutoApproveCuller implements VisionCuller {
        @Override
        public String id() {
            return "auto-approve";
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            for (final String montage : prep.entries()) {
                try {
                    writeShard(prep.prepDir(), montage);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new CullReport(prep.entries().size(), 0, 0, 0);
        }
    }

    // A sibling of AutoApproveCuller that writes a real "junk" classification for every photo in
    // every montage, instead of an all-keeps shard. That gives ApplyEngine an actual move loop to
    // run - and a test something to synchronize with via BlockingMoveTo - rather than zero
    // decisions.
    static final class JunkEverythingCuller implements VisionCuller {
        private final CullPrepPort cullPrepPort = new JsonCullPrepStore();

        @Override
        public String id() {
            return "auto-approve";
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            for (final String montage : prep.entries()) {
                final List<SidecarPhotoEntry> photos = this.cullPrepPort.readSidecar(prep.prepDir(), montage);
                final String[] decisions = photos.stream()
                        .map(photo -> classificationJson(photo.src(), "junk", "blurry"))
                        .toArray(String[]::new);
                try {
                    writeShard(prep.prepDir(), montage, decisions);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new CullReport(prep.entries().size(), 0, 0, 0);
        }
    }

    // An automated provider that succeeds at its own job and still produces a shard set apply
    // refuses. Every decision names a file no montage ever showed, and whose basename matches no
    // in-scope file either, so no unique-basename heal can pull it back into scope. That is the
    // shape a run needs to reach Blocked without any culler-side failure along the way.
    static final class OutOfScopeCuller implements VisionCuller {
        @Override
        public String id() {
            return "auto-approve";
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            for (final String montage : prep.entries()) {
                try {
                    writeShard(prep.prepDir(), montage,
                            classificationJson(prep.prepDir().resolve("never-in-scope.jpg"), "junk", "blurry"));
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new CullReport(prep.entries().size(), 0, 0, 0);
        }
    }

    // Stands in for an automated provider (Anthropic/OpenAI/Ollama) whose CullException means a
    // genuine failure, never "waiting for more shards" - see VisionCuller.MANUAL_MODE_PROVIDER_ID.
    record ThrowingCuller(String id) implements VisionCuller {
        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
            throw new CullException("the model could not produce a valid judgement");
        }
    }

    // A signal-honoring stand-in for an automated provider, the same shape as AnthropicCuller. It
    // writes a shard for each montage in turn, checking cancellation between them. It blocks after
    // the first shard, so a test can synchronize a mid-dispatch cancellation with a real observable
    // signal instead of a guessed sleep. Cancellation is never surfaced as a CullException here. The
    // loop just stops early and returns whatever partial CullReport it has - the real provider's own
    // "never throws to signal a cancellation" contract.
    record BlockingCancellableCuller(CountDownLatch firstShardWritten, CountDownLatch releaseRemaining)
            implements VisionCuller {

        @Override
        public String id() {
            return "auto-approve";
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            return this.cull(prep, opts, ProgressCallback.NO_OP, CancellationSignal.NEVER);
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress,
                               final CancellationSignal cancellation) {
            final int total = prep.entries().size();
            int current = 0;
            int culled = 0;
            while (current < total && !cancellation.isCancelled()) {
                final String montage = prep.entries().get(current);
                current++;
                try {
                    writeShard(prep.prepDir(), montage);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
                culled++;
                progress.tick(current, total);
                if (culled == 1) {
                    this.firstShardWritten.countDown();
                    try {
                        this.releaseRemaining.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }
            return new CullReport(culled, total - culled, 0, 0);
        }
    }

    // Any call into this fake fails its test outright. Two separate claims lean on that. One is
    // buildFreshAndDispatch()'s post-PREPPING cancellation check short-circuiting before dispatch.
    // The other is a resume skipping dispatch entirely once every montage already has a shard.
    static final class NeverCalledCuller implements VisionCuller {
        @Override
        public String id() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            throw new AssertionError("dispatch must never run: neither after a post-PREPPING "
                    + "cancellation, nor when every montage already has a shard");
        }
    }

    record FixedSettings(String provider, List<CullCategory> categories, ExternalAgentSettings externalAgent)
            implements CullSettings {
        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }

        // tilesPerRow=1 gives one photo per montage, so a test controls exactly which montage a
        // given photo lands in via mtime ordering alone, without depending on batch-size math.
        @Override
        public MontageConfig montage() {
            return new MontageConfig(64, 1);
        }
    }
}
