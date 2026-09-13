package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.CsvSpendLedger;
import photos.sluice.adapter.fs.InboxScanner;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.SiftMontageRenderer;
import photos.sluice.adapter.imaging.ImageDimensionsReader;
import photos.sluice.adapter.imaging.MontageBuilder;
import photos.sluice.adapter.imaging.PrepIndexWriter;
import photos.sluice.adapter.imaging.SidecarWriter;
import photos.sluice.adapter.imaging.TileRenderer;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.adapter.vision.JsonSiftPrepStore;
import photos.sluice.application.port.out.SiftException;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftPrepPort;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.SiftSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.SpendLedgerPort;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.DecisionShard;
import photos.sluice.domain.sift.MontageConfig;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.sift.PrepDirHealth.State;
import photos.sluice.domain.sift.SidecarPhotoEntry;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.secrets.SecretHolding;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStatus;
import photos.sluice.secrets.SecretStore;

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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

// The real-adapter wiring factory and every fake shared by the pipeline tests.
final class PipelineTestSupport {

    // Any id a manual-mode fake and the settings pointing at it can agree on. The provider's type
    // is what selects the manual-mode paths, so no real id is needed.
    static final String MANUAL_PROVIDER_ID = "a-manual-provider";

    static final SecretId MANUAL_PROVIDER_KEY =
            new SecretId(MANUAL_PROVIDER_ID, "A_MANUAL_PROVIDER_KEY");

    private PipelineTestSupport() {
    }

    static void waitUntil(final Duration timeout, final BooleanSupplier condition) {
        final Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                // The busy-wait this polls for is a real background SiftWatcher/JobRunner thread,
                // not something this test can await via a latch or callback.
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // Proves a background thread did NOT act. The window has to be several poll intervals wide, to
    // give the thread real chances to act.
    static void assertHoldsFor(final Duration window, final BooleanSupplier condition) {
        final Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError("condition stopped holding within " + window);
            }
            try {
                // Throttles this checking loop; the thing being watched is a real background
                // SiftWatcher thread, with no latch or callback to await instead.
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

    // Both halves are load-bearing. An empty unresolved list alone goes true at decisions.json,
    // and the prep dir is still being cleaned out after that, so teardown would race it. An idle
    // runner alone is true before the job ever starts. The run has to be unresolved when the wait
    // begins, since a caller whose run already reads COMPLETE gets no wait at all.
    static void waitForJobToFinish(final Pipeline pipeline, final Duration timeout) {
        waitUntil(timeout, () -> unresolvedRuns(pipeline).isEmpty() && !pipeline.isBusy());
    }

    // An applied run stays on disk until purged, so siftRuns() keeps listing it and "no runs at
    // all" would never come true.
    static List<SiftRunSummary> unresolvedRuns(final Pipeline pipeline) {
        return listed(pipeline.siftRuns()).stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .toList();
    }

    // Fails rather than returning empty on an unlistable root, so a test that meant to read runs
    // cannot pass by reading a failure as none.
    static List<SiftRunSummary> listed(final SiftRuns runs) {
        if (runs instanceof SiftRuns.Listed(final List<SiftRunSummary> found)) {
            return found;
        }
        throw new AssertionError("The sift-prep root could not be listed: " + runs);
    }

    static Path sortedPhotosDir(final Path root, final String year, final String month) {
        return root.resolve("Sorted").resolve("Photos").resolve(year).resolve(month);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore());
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, defaultSiftSettings(), List.of(new ManualModeSieve()));
    }

    static Pipeline siftPipeline(final Path root, final RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore(), defaultSiftSettings(), List.of(new ManualModeSieve()));
    }

    static Pipeline siftPipeline(final Path root, final RecordingProgressPort progress, final SiftSettings siftSettings,
                                 final List<VisionSieve> sieves) {
        return pipeline(root, progress, new NioMediaStore(), siftSettings, sieves);
    }

    // Every other factory here wires a provider naming no credential, so what the machine holds
    // never comes up.
    static Pipeline credentialPipeline(final Path root, final RecordingProgressPort progress,
                                       final List<VisionSieve> sieves, final SecretStore secretStore) {
        return pipeline(root, progress, new NioMediaStore(), defaultSiftSettings(), sieves, null,
                new JsonSiftPrepStore(), secretStore);
    }

    static Pipeline curatePipeline(final Path root, final RecordingProgressPort progress) {
        return curatePipeline(root, progress, new NioMediaStore());
    }

    static Pipeline curatePipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, autoApproveSiftSettings(), List.of(new AutoApproveSieve()));
    }

    static SpendLedgerPort spendLedgerOf(final Path root) {
        return new CsvSpendLedger(SettingsFixture.workingRoot(root));
    }

    static SiftSettings autoApproveSiftSettings() {
        return new FixedSettings("auto-approve", List.of());
    }

    // A millisecond-scale poll interval, so a real auto-resume proves out without waiting on the
    // production cadence.
    static Pipeline watchPipeline(final Path root, final RecordingProgressPort progress,
                                  final SiftSettings siftSettings,
                                  final List<VisionSieve> sieves, final Duration pollInterval) {
        return pipeline(root, progress, new NioMediaStore(), siftSettings, sieves, pollInterval);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final SiftSettings siftSettings, final List<VisionSieve> sieves) {
        return pipeline(root, progress, mediaStore, siftSettings, sieves, null);
    }

    // A prep-dir reader that can be told to start failing its index reads, standing in for a file
    // held open by a backup or antivirus process. Reaching that state through the filesystem
    // instead would mean testing the OS. A directory in place of the file fails at the open on
    // Windows and at the first read on Linux, which are different clauses in the reader.
    static final class FailableIndexReads implements SiftPrepPort {

        private final SiftPrepPort delegate = new JsonSiftPrepStore();
        private boolean failing;

        void startFailing() {
            this.failing = true;
        }

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

    // The real case is something outside this process creating a prep dir during a long sort.
    // JobRunner's single slot stops another in-app job doing it, but nothing stops the user or a
    // sync client.
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
        public boolean directoryExists(final Path path) {
            return this.delegate.directoryExists(path);
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public Walk listFilesToleratingRefusals(final Path root) {
            return this.delegate.listFilesToleratingRefusals(root);
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
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
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
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.move(source, destDir, stop, transferProgress);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.moveTo(source, destination, stop, transferProgress);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copy(source, destDir, stop, transferProgress);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copyTo(source, destination, stop, transferProgress);
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

    // Stands in for a scope's own occupancy check failing, over a file something else holds open.
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

    static Pipeline siftPipeline(final Path root, final RecordingProgressPort progress,
                                 final SiftPrepPort siftPrepPort) {
        return pipeline(root, progress, new NioMediaStore(), defaultSiftSettings(), List.of(new ManualModeSieve()),
                null, siftPrepPort);
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final SiftSettings siftSettings, final List<VisionSieve> sieves,
                             final @Nullable Duration pollInterval) {
        return pipeline(root, progress, mediaStore, siftSettings, sieves, pollInterval, new JsonSiftPrepStore());
    }

    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final SiftSettings siftSettings, final List<VisionSieve> sieves,
                             final @Nullable Duration pollInterval, final SiftPrepPort siftPrepPort) {
        return pipeline(root, progress, mediaStore, siftSettings, sieves, pollInterval, siftPrepPort,
                new FixedSecretStore(null));
    }

    // Real adapters throughout. SiftMontageRenderer's HeifDecoder dependency is stubbed to always
    // miss, none of these fixtures being HEIC or AVIF. A null pollInterval means Pipeline's own
    // production default.
    static Pipeline pipeline(final Path root, final RecordingProgressPort progress, final MediaStore mediaStore,
                             final SiftSettings siftSettings, final List<VisionSieve> sieves,
                             final @Nullable Duration pollInterval, final SiftPrepPort siftPrepPort,
                             final SecretStore secretStore) {
        final Path libraryRoot = createDirectory(root.resolve("Library"));
        final Path inbox = createDirectory(root.resolve("Inbox"));
        final var settings = SettingsFixture.holder(root, libraryRoot, inbox);
        final var pathsConfig = new PathsConfig(settings);
        final var pathValidation = new PathValidationService(mediaStore, settings);
        final var hashIndex = new CsvLibraryHashIndex(pathsConfig);
        final var spendLedger = new CsvSpendLedger(pathsConfig);
        final var sha256Port = new Sha256Hasher();

        final var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        final var sortEngine = new SortEngine(pathsConfig, new InboxScanner(), dateResolver, sha256Port, hashIndex,
                new ImageDimensionsReader(), mediaStore, progress);
        final var commitEngine = new CommitEngine(pathsConfig, mediaStore, sha256Port, hashIndex);
        final var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        final var rescueEngine = new RescueEngine(pathsConfig, mediaStore, rescueDateResolver, new Sha256Hasher());

        final HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        final var montageRenderer = new SiftMontageRenderer(new TileRenderer(stubHeifDecoder), new MontageBuilder(),
                new SidecarWriter(), new PrepIndexWriter(), mediaStore, pathsConfig, siftSettings);
        final var siftDispatcher = new SiftDispatcher(sieves, siftSettings);
        final var disasterDrawer = new DisasterDrawer(mediaStore);
        final var moveLedger = new MoveLedger(mediaStore, disasterDrawer);
        final var siftDestinations = new SiftDestinations(pathsConfig);
        final var applyPlanner = new ApplyPlanner(mediaStore, siftPrepPort, sha256Port, pathsConfig);
        final var applyEngine = new ApplyEngine(mediaStore, siftPrepPort, sha256Port, hashIndex, siftDestinations,
                moveLedger, applyPlanner);
        final var reconcileEngine = new ReconcileEngine(mediaStore, siftPrepPort, sha256Port, disasterDrawer,
                siftDestinations, moveLedger, applyPlanner);
        final var prepDirRemedies = new PrepDirRemedies(mediaStore, siftPrepPort, pathsConfig, siftSettings,
                disasterDrawer, moveLedger);
        final var prepDirDoctor = new PrepDirDoctor(siftPrepPort, mediaStore, applyPlanner, moveLedger);
        final var troubleshooter = new Troubleshooter(prepDirDoctor, reconcileEngine, prepDirRemedies, disasterDrawer);
        final var importEngine = new ImportEngine(mediaStore, pathsConfig, sha256Port);
        if (pollInterval == null) {
            return new Pipeline(sortEngine, commitEngine, rescueEngine, importEngine, montageRenderer,
                    siftDispatcher, applyEngine,
                    prepDirRemedies, siftPrepPort, siftSettings, mediaStore, pathsConfig,
                    new JobRunner(), progress, disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner,
                    moveLedger, pathValidation, spendLedger, secretStore);
        }
        return new Pipeline(sortEngine, commitEngine, rescueEngine, importEngine, montageRenderer, siftDispatcher,
                applyEngine,
                prepDirRemedies, siftPrepPort, siftSettings, mediaStore, pathsConfig, new JobRunner(),
                progress, disasterDrawer, troubleshooter, prepDirDoctor, applyPlanner, moveLedger, pathValidation,
                spendLedger, secretStore, pollInterval);
    }

    // The folder roots have to exist for the pipeline's own path check to pass.
    private static Path createDirectory(final Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to create " + directory, e);
        }
    }

    // Built standalone because Pipeline exposes troubleshoot() but not the individual CHOICE
    // remedies, which a troubleshoot screen calls directly.
    static PrepDirRemedies prepDirRemedies(final Path root) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
        final var mediaStore = new NioMediaStore();
        final var disasterDrawer = new DisasterDrawer(mediaStore);
        return new PrepDirRemedies(mediaStore, new JsonSiftPrepStore(), pathsConfig, defaultSiftSettings(),
                disasterDrawer, new MoveLedger(mediaStore, disasterDrawer));
    }

    static SiftSettings defaultSiftSettings() {
        return new FixedSettings(MANUAL_PROVIDER_ID, List.of());
    }

    static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Enough bytes to clear LowResGate's size threshold, so a test that is not about low-res
    // routing cannot accidentally exercise it.
    static String padded(final String marker) {
        return marker + "x".repeat(60_000);
    }

    // Above LowResGate.MIN_DIMENSION on the long side, so these photos are always reviewable.
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

    // A photo that has to survive an actual sort pass has to clear the low-res gate for real. A
    // solid fill compresses to a few KB, well under the floor. Filling every pixel with random
    // noise defeats JPEG compression instead, so the file clears it easily. name must carry a
    // FilenameSource-recognized date (e.g. "20190601_photo.jpg"), these fixtures having no EXIF or
    // Takeout JSON.
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

    // Hand-drops a shard the same shape a real external agent would write. ShardCodec itself is
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

    static String keepJson(final Path file) {
        return "{ \"file\": \"%s\", \"action\": \"keep\" }".formatted(jsonEscaped(file));
    }

    // Reads the sidecar rather than taking a count, so a fixture that grows a photo stays covered.
    static void writeAllKeepsShard(final Path prepDir, final String montage) throws IOException {
        writeShard(prepDir, montage, new JsonSiftPrepStore().readSidecar(prepDir, montage).stream()
                .map(photo -> keepJson(photo.src()))
                .toArray(String[]::new));
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    static final class RecordingProgressPort implements ProgressPort {
        final List<String> events = new ArrayList<>();
        // A tick is reported only once the work it counts is genuinely on disk. A cancellation
        // hung off this one therefore lands at a real moment rather than a guessed one.
        private @Nullable Runnable onFirstTick;
        // phaseFinished fires between one phase ending and the next one starting, so a
        // cancellation hung off this one lands in exactly that window.
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
        public void phasesPlanned(final List<String> phases) {
            this.events.add("planned:" + String.join(", ", phases));
        }

        @Override
        public void phaseStarted(final String phase) {
            this.events.add("started:" + phase);
        }

        @Override
        public void phaseStopped(final String phase) {
            this.events.add("cutShort:" + phase);
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

    static final class FailingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root);
        }

        @Override
        public Walk listFilesToleratingRefusals(final Path root) {
            return this.delegate.listFilesToleratingRefusals(root);
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
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            throw new RuntimeException("simulated crash");
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.moveTo(source, destination, stop, transferProgress);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copy(source, destDir, stop, transferProgress);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copyTo(source, destination, stop, transferProgress);
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
        public boolean directoryExists(final Path path) {
            return this.delegate.directoryExists(path);
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

    // Blocks the first move() call between two latches, so a test synchronizes with the exact
    // moment a move is in flight. That is observable proof the call has not returned, rather than
    // a sleep long enough to probably still be running.
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
        public Walk listFilesToleratingRefusals(final Path root) {
            return this.delegate.listFilesToleratingRefusals(root);
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
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            this.moveStarted.countDown();
            try {
                this.releaseMove.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return this.delegate.move(source, destDir, stop, transferProgress);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.moveTo(source, destination, stop, transferProgress);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copy(source, destDir, stop, transferProgress);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copyTo(source, destination, stop, transferProgress);
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
        public boolean directoryExists(final Path path) {
            return this.delegate.directoryExists(path);
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

    // Blocks the first listFiles() call between two latches, which is the one the montage renderer
    // makes while scanning Sorted for candidates. A test lands a cancellation at that moment.
    static final class BlockingListFiles implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public Walk listFilesToleratingRefusals(final Path root) {
            return this.delegate.listFilesToleratingRefusals(root);
        }

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
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.move(source, destDir, stop, transferProgress);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.moveTo(source, destination, stop, transferProgress);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copy(source, destDir, stop, transferProgress);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copyTo(source, destination, stop, transferProgress);
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
        public boolean directoryExists(final Path path) {
            return this.delegate.directoryExists(path);
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

    // Blocks the first moveTo() call between two latches, so a test lands a real cancellation at
    // the exact moment a decision's move is in flight.
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
        public Walk listFilesToleratingRefusals(final Path root) {
            return this.delegate.listFilesToleratingRefusals(root);
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
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.move(source, destDir, stop, transferProgress);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            this.moveStarted.countDown();
            try {
                this.releaseMove.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return this.delegate.moveTo(source, destination, stop, transferProgress);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copy(source, destDir, stop, transferProgress);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress transferProgress) {
            return this.delegate.copyTo(source, destination, stop, transferProgress);
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
        public boolean directoryExists(final Path path) {
            return this.delegate.directoryExists(path);
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

    // Every double below needs a description because the port has one, and none of them is about
    // what a settings screen would draw. Named settings and a credential would be fixture no
    // assertion here reads.
    static VisionProviderDescriptor describing(final String id) {
        return new VisionProviderDescriptor(id, id, Set.of(), Set.of(), null, null, null, null);
    }

    record FixedSecretStore(@Nullable String held) implements SecretStore {

        @Override
        public Optional<String> secret(final SecretId id) {
            throw new UnsupportedOperationException("deciding whether to start a run reads no "
                    + "credential value, only which tier answers");
        }

        @Override
        public SecretStatus status(final SecretId id) {
            return this.held == null || !MANUAL_PROVIDER_KEY.equals(id)
                    ? new SecretStatus.Absent() : new SecretStatus.InFile();
        }

        @Override
        public List<SecretHolding> holdings(final SecretId id) {
            return List.of(new SecretHolding(new SecretStatus.InFile(),
                    this.held == null ? SecretHolding.Holding.EMPTY : SecretHolding.Holding.HOLDS));
        }

        @Override
        public Optional<SecretStatus.StoredLocation> whereASaveWouldStoreIt() {
            return Optional.of(new SecretStatus.InFile());
        }

        @Override
        public void save(final SecretId id, final String secret) {
            throw new UnsupportedOperationException("no pipeline test stores a credential");
        }

        @Override
        public void remove(final SecretId id) {
            throw new UnsupportedOperationException("no pipeline test clears a credential");
        }
    }

    // Stands in for the real ExternalAgentSieve, which is package-private and unreachable from
    // here. Only a completeness check, gating on hasShard() rather than on full shard validation.
    static final class ManualModeSieve implements VisionSieve {

        private final @Nullable SecretId credential;

        ManualModeSieve() {
            this(null);
        }

        // A provider that authenticates. Manual mode otherwise, so a dispatch behaves the same
        // either way and only the credential varies.
        ManualModeSieve(final @Nullable SecretId credential) {
            this.credential = credential;
        }

        @Override
        public VisionProviderDescriptor describe() {
            return this.credential == null ? describing(MANUAL_PROVIDER_ID)
                    : new VisionProviderDescriptor(MANUAL_PROVIDER_ID, MANUAL_PROVIDER_ID,
                            Set.of(ProviderSetting.CREDENTIAL), Set.of(),
                            this.credential, null, null, null);
        }

        @Override
        public ProviderType type() {
            return ProviderType.MANUAL;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) throws SiftException {
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
                throw new SiftException("missing shard(s) for: " + missing);
            }
            return new SiftReport(done, prep.entries().size() - done, 0,
                    TokenSpend.none(MANUAL_PROVIDER_ID), false);
        }
    }

    // Blocks before throwing the "not complete yet" SiftException, so a test lands a real
    // cancellation at the exact moment dispatch is in flight.
    record BlockingIncompleteSieve(CountDownLatch started, CountDownLatch release) implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing(MANUAL_PROVIDER_ID);
        }

        @Override
        public ProviderType type() {
            return ProviderType.MANUAL;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) throws SiftException {
            this.started.countDown();
            try {
                this.release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            throw new SiftException("still incomplete");
        }
    }

    // Stands in for an automated provider that always succeeds on its first try, writing a valid
    // shard for every montage in one call.
    //
    // Unconditional rather than gated on hasShard(). The prep dir is always rebuilt fresh right
    // before dispatch runs, so a montage here can never already carry a shard.
    static final class AutoApproveSieve implements VisionSieve {
        @Nullable SiftOptions receivedOptions;

        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            this.receivedOptions = opts;
            for (final String montage : prep.entries()) {
                try {
                    writeAllKeepsShard(prep.prepDir(), montage);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new SiftReport(prep.entries().size(), 0, 0, TokenSpend.none(this.describe().id()), false);
        }
    }

    // Nothing validates SiftReport, so a provider can hand back counts SpendLedgerEntry refuses.
    static final class ImpossibleCountSieve implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            for (final String montage : prep.entries()) {
                try {
                    writeAllKeepsShard(prep.prepDir(), montage);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new SiftReport(prep.entries().size(), -1, 0, TokenSpend.none(this.describe().id()), false);
        }
    }

    // Spends and then gives up, the way a real provider does when a montage fails its corrective
    // retry.
    static final class SpendingThenFailingSieve implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.Counted(1_000);
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) throws SiftException {
            throw new SiftException("gave up on a sheet", new SiftReport(1, 0, 3,
                    new TokenSpend(4_000, 800, "auto-approve", "a-model"), false));
        }
    }

    // CeilingStoppedSieve with a gap held open in the middle. A test lands a cancellation in that
    // gap, so both it and the ceiling stop are true when the engine picks which one to report.
    record BlockingSieve(CountDownLatch started, CountDownLatch release, boolean stoppedAtCeiling)
            implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.Counted(1_000);
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            try {
                writeAllKeepsShard(prep.prepDir(), prep.entries().getFirst());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            this.started.countDown();
            try {
                this.release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return new SiftReport(1, 0, 2, new TokenSpend(9_000, 3_000, "auto-approve", "a-model"),
                    this.stoppedAtCeiling);
        }
    }

    // Judges the first montage and then reports that its ceiling ended the run. It forecasts a
    // real per-call figure, so the engine has something to build a ceiling from.
    static final class CeilingStoppedSieve implements VisionSieve {
        @Nullable SpendCeiling receivedCeiling;

        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.Counted(1_000);
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            this.receivedCeiling = opts.ceiling();
            try {
                writeAllKeepsShard(prep.prepDir(), prep.entries().getFirst());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            return new SiftReport(1, 0, 2, new TokenSpend(9_000, 3_000, "auto-approve", "a-model"), true);
        }
    }

    // Writes a real "junk" classification for every photo in every montage rather than an
    // all-keeps shard, so apply has an actual move loop to run.
    static final class JunkEverythingSieve implements VisionSieve {
        private final SiftPrepPort siftPrepPort = new JsonSiftPrepStore();

        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            for (final String montage : prep.entries()) {
                final List<SidecarPhotoEntry> photos = this.siftPrepPort.readSidecar(prep.prepDir(), montage);
                final String[] decisions = photos.stream()
                        .map(photo -> classificationJson(photo.src(), "junk", "blurry"))
                        .toArray(String[]::new);
                try {
                    writeShard(prep.prepDir(), montage, decisions);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new SiftReport(prep.entries().size(), 0, 0, TokenSpend.none(this.describe().id()), false);
        }
    }

    // Succeeds at its own job and still produces a shard set apply refuses. Every decision names a
    // file no montage showed, whose basename matches no in-scope file either, so no
    // unique-basename heal can pull it back into scope. That is the shape a run needs to reach
    // Blocked with no sieve-side failure along the way.
    static final class OutOfScopeSieve implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            final SiftPrepPort prepPort = new JsonSiftPrepStore();
            for (final String montage : prep.entries()) {
                // Keeps for every photo the sheet showed, so coverage holds and the out-of-scope
                // file is the only thing left to report.
                final List<String> verdicts = new ArrayList<>(prepPort.readSidecar(prep.prepDir(), montage).stream()
                        .map(photo -> keepJson(photo.src()))
                        .toList());
                verdicts.add(classificationJson(prep.prepDir().resolve("never-in-scope.jpg"), "junk", "blurry"));
                try {
                    writeShard(prep.prepDir(), montage, verdicts.toArray(new String[0]));
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return new SiftReport(prep.entries().size(), 0, 0, TokenSpend.none(this.describe().id()), false);
        }
    }

    // Stands in for a provider that calls a model, so its SiftException means the model could not
    // answer rather than that shards are still arriving.
    record ThrowingSieve(String id) implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing(this.id);
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) throws SiftException {
            throw new SiftException("the model could not produce a valid judgement");
        }
    }

    // A signal-honoring stand-in for an automated provider, the same shape as AnthropicSieve. It
    // writes a shard for each montage in turn, checking cancellation between them. It blocks after
    // the first shard, so a test can synchronize a mid-dispatch cancellation with a real observable
    // signal instead of a guessed sleep. Cancellation is never surfaced as a SiftException here. The
    // loop just stops early and returns whatever partial SiftReport it has - the real provider's own
    // "never throws to signal a cancellation" contract.
    record BlockingCancellableSieve(CountDownLatch firstShardWritten, CountDownLatch releaseRemaining)
            implements VisionSieve {

        @Override
        public VisionProviderDescriptor describe() {
            return describing("auto-approve");
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            return this.sift(prep, opts, ProgressCallback.NO_OP, CancellationSignal.NEVER);
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts, final ProgressCallback progress,
                               final CancellationSignal cancellation) {
            final int total = prep.entries().size();
            int current = 0;
            int sifted = 0;
            while (current < total && !cancellation.isCancelled()) {
                final String montage = prep.entries().get(current);
                current++;
                try {
                    writeAllKeepsShard(prep.prepDir(), montage);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
                sifted++;
                progress.tick(current, total);
                if (sifted == 1) {
                    this.firstShardWritten.countDown();
                    try {
                        this.releaseRemaining.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }
            return new SiftReport(sifted, total - sifted, 0, TokenSpend.none(this.describe().id()), false);
        }
    }

    // Any call into this fake fails its test outright. Two separate claims lean on that. One is
    // buildFreshAndDispatch()'s post-PREPPING cancellation check short-circuiting before dispatch.
    // The other is a resume skipping dispatch entirely once every montage already has a shard.
    static final class NeverCalledSieve implements VisionSieve {
        @Override
        public VisionProviderDescriptor describe() {
            return describing(MANUAL_PROVIDER_ID);
        }

        @Override
        public ProviderType type() {
            return ProviderType.MANUAL;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            throw new AssertionError("dispatch must never run: neither after a post-PREPPING "
                    + "cancellation, nor when every montage already has a shard");
        }
    }

    record FixedSettings(String provider, List<SiftCategory> categories)
            implements SiftSettings {
        @Override
        public SiftProviderSettings providerSettings() {
            return SiftProviderSettings.unset();
        }

        @Override
        public SiftProviderSettings providerSettings(final String providerId) {
            return SiftProviderSettings.unset();
        }

        // tilesPerRow=1 gives one photo per montage, so a test controls exactly which montage a
        // given photo lands in via mtime ordering alone, without depending on batch-size math.
        @Override
        public MontageConfig montage() {
            return new MontageConfig(64, 1);
        }
    }
}
