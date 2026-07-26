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
import photos.sluice.application.port.out.CullCategory;
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
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
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
// CurateEngineTest. Each of those three files brings in what it needs via explicit
// "import static photos.sluice.application.service.PipelineTestSupport.<member>;" lines, so their
// own test bodies read exactly as they did before the split.
final class PipelineTestSupport {

    private PipelineTestSupport() {
    }

    static void waitUntil(Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                // The busy-wait this polls for is a real background CullWatcher/JobRunner thread,
                // not something this test can await via a latch or callback.
                //noinspection BusyWait
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static Path inboxOf(Path root) {
        return root.resolve("Inbox");
    }

    static Path sortedPhotosDir(Path root, String year, String month) {
        return root.resolve("Sorted").resolve("Photos").resolve(year).resolve(month);
    }

    static Pipeline pipeline(Path root, RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore());
    }

    static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    // cull()/waitingJobs()/resume() tests always go through this name, wiring the same manual-mode
    // default (a fake external-agent-shaped VisionCuller) unless a test needs to vary the provider.
    static Pipeline cullPipeline(Path root, RecordingProgressPort progress) {
        return pipeline(root, progress, new NioMediaStore(), defaultCullSettings(), List.of(new ManualModeCuller()));
    }

    static Pipeline cullPipeline(Path root, RecordingProgressPort progress, CullSettings cullSettings,
            List<VisionCuller> cullers) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers);
    }

    // curate() tests go through this name, wiring AutoApproveCuller as the configured provider.
    // curate() runs prep/dispatch/apply in one call, with no gap to hand-drop a shard into the way
    // the manual-mode cull() tests above do.
    static Pipeline curatePipeline(Path root, RecordingProgressPort progress) {
        return curatePipeline(root, progress, new NioMediaStore());
    }

    static Pipeline curatePipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore) {
        return pipeline(root, progress, mediaStore, autoApproveCullSettings(), List.of(new AutoApproveCuller()));
    }

    static CullSettings autoApproveCullSettings() {
        return new FixedSettings("auto-approve", List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
    }

    // Watch-mode tests go through this name: same wiring, but with a millisecond-scale poll
    // interval (via Pipeline's package-private test constructor). A real auto-resume proves out
    // fast this way, instead of waiting on the production 2-second cadence.
    static Pipeline watchPipeline(Path root, RecordingProgressPort progress, CullSettings cullSettings,
            List<VisionCuller> cullers, Duration pollInterval) {
        return pipeline(root, progress, new NioMediaStore(), cullSettings, cullers, pollInterval);
    }

    static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore,
            CullSettings cullSettings, List<VisionCuller> cullers) {
        return pipeline(root, progress, mediaStore, cullSettings, cullers, null);
    }

    // The one full wiring every overload above funnels into - real adapters throughout (matching
    // this project's no-mocks test convention), same as the engines below. CullMontageRenderer's
    // HeifDecoder dependency is stubbed to always miss: none of these fixtures are HEIC/AVIF, and
    // real HEIC/AVIF decode already has its own coverage in TileRendererTest. pollInterval null
    // means "use Pipeline's own production default" - only watchPipeline() ever passes one.
    static Pipeline pipeline(Path root, RecordingProgressPort progress, MediaStore mediaStore,
            CullSettings cullSettings, List<VisionCuller> cullers, @Nullable Duration pollInterval) {
        Path libraryRoot = root.resolve("Library");
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), libraryRoot.toString(), root.resolve("Inbox").toString()));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        var sha256Port = new Sha256Hasher();

        var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        var sortEngine = new SortEngine(pathsConfig, new InboxScanner(), dateResolver, sha256Port, hashIndex,
                new ImageDimensionsReader(), mediaStore);
        var commitEngine = new CommitEngine(pathsConfig, mediaStore, sha256Port, hashIndex);
        var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        var rescueEngine = new RescueEngine(pathsConfig, mediaStore, sha256Port, hashIndex, rescueDateResolver);

        HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        var montageRenderer = new CullMontageRenderer(new TileRenderer(stubHeifDecoder), new MontageBuilder(),
                new SidecarWriter(), new PrepIndexWriter(), mediaStore, pathsConfig);
        var cullPrepPort = new JsonCullPrepStore();
        var cullDispatcher = new CullDispatcher(cullers, cullSettings);
        var applyEngine = new ApplyEngine(pathsConfig, mediaStore, cullPrepPort, cullSettings, sha256Port, hashIndex);
        // tilesPerRow=1 gives one photo per montage, so a test controls exactly which montage a
        // given photo lands in via mtime ordering alone, without depending on batch-size math.
        var montageConfig = new MontageConfig(64, 1);

        if (pollInterval == null) {
            return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                    cullPrepPort, cullSettings, mediaStore, pathsConfig, montageConfig, new JobRunner(), progress);
        }
        return new Pipeline(sortEngine, commitEngine, rescueEngine, montageRenderer, cullDispatcher, applyEngine,
                cullPrepPort, cullSettings, mediaStore, pathsConfig, montageConfig, new JobRunner(), progress,
                pollInterval);
    }

    static CullSettings defaultCullSettings() {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.MANUAL, null));
    }

    static CullSettings watchCullSettings(@Nullable Duration watchTimeout) {
        return new FixedSettings(VisionCuller.MANUAL_MODE_PROVIDER_ID,
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH, watchTimeout));
    }

    static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // 60,000 bytes clears LowResGate's 50KB threshold, same fixture convention as SortEngineTest -
    // sort's progress-bracket tests aren't testing low-res routing and shouldn't accidentally
    // exercise it.
    static String padded(String marker) {
        return marker + "x".repeat(60_000);
    }

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable -
    // same fixture convention as CullMontageRendererTest.
    static final int PHOTO_WIDTH = 800;
    static final int PHOTO_HEIGHT = 600;

    static Path writePhoto(Path dir, String name, Instant mtime) throws IOException {
        Files.createDirectories(dir);
        var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, PHOTO_WIDTH, PHOTO_HEIGHT);
        } finally {
            g.dispose();
        }
        Path file = dir.resolve(name);
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
    static Path writeInboxPhoto(Path root, String name) throws IOException {
        return writeInboxPhoto(root, name, 42);
    }

    // seed varies the noise, so two calls in the same test never produce byte-identical files that
    // ByteIdenticalDedup would then collapse into one.
    static Path writeInboxPhoto(Path root, String name, long seed) throws IOException {
        Path file = inboxOf(root).resolve(name);
        Files.createDirectories(file.getParent());
        var image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var random = new Random(seed);
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
    static void writeShard(Path prepDir, String montage, String... decisionsJson) throws IOException {
        String shardName = montage.replaceFirst("^montage-", "decisions-") + ".json";
        Files.writeString(prepDir.resolve(shardName),
                "{ \"montage\": \"%s\", \"decisions\": [ %s ] }".formatted(montage, String.join(", ", decisionsJson)));
    }

    static String classificationJson(Path file, String category, String reason) {
        return "{ \"file\": \"%s\", \"action\": \"%s\", \"reason\": \"%s\" }"
                .formatted(jsonEscaped(file), category, reason);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    static final class RecordingProgressPort implements ProgressPort {
        final List<String> events = new ArrayList<>();

        @Override
        public void phaseStarted(String phase) {
            events.add("started:" + phase);
        }

        @Override
        public void tick(String phase, int current, int total) {
            events.add("tick:" + phase + ":" + current + "/" + total);
        }

        @Override
        public void phaseFinished(String phase) {
            events.add("finished:" + phase);
        }
    }

    // Wraps the real NioMediaStore but always throws on move() - simulates an engine call that dies
    // mid-phase, to prove Pipeline still brackets phaseFinished on the failure path.
    static final class FailingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            throw new RuntimeException("simulated crash");
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
        }
    }

    // Wraps the real NioMediaStore but blocks the first move() call between two latches. A test can
    // synchronize with the exact moment SortEngine is mid-move this way. That's real observable
    // proof it hasn't returned yet, not a guessed sleep long enough to "probably" still be running.
    static final class BlockingMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private final CountDownLatch moveStarted;
        private final CountDownLatch releaseMove;

        BlockingMoves(CountDownLatch moveStarted, CountDownLatch releaseMove) {
            this.moveStarted = moveStarted;
            this.releaseMove = releaseMove;
        }

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            moveStarted.countDown();
            try {
                releaseMove.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
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

        BlockingListFiles(CountDownLatch listStarted, CountDownLatch releaseList) {
            this.listStarted = listStarted;
            this.releaseList = releaseList;
        }

        @Override
        public List<Path> listFiles(Path root) {
            listStarted.countDown();
            try {
                releaseList.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            return delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
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

        BlockingMoveTo(CountDownLatch moveStarted, CountDownLatch releaseMove) {
            this.moveStarted = moveStarted;
            this.releaseMove = releaseMove;
        }

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root);
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            return delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            moveStarted.countDown();
            try {
                releaseMove.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
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
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
            List<String> missing = new ArrayList<>();
            int done = 0;
            for (String montage : prep.entries()) {
                boolean hasShard = Files.exists(prep.prepDir().resolve(
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
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
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
        public CullReport cull(PrepDir prep, CullOptions opts) {
            for (String montage : prep.entries()) {
                try {
                    writeShard(prep.prepDir(), montage);
                } catch (IOException e) {
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
        public CullReport cull(PrepDir prep, CullOptions opts) {
            for (String montage : prep.entries()) {
                List<SidecarPhotoEntry> photos = cullPrepPort.readSidecar(prep.prepDir(), montage);
                String[] decisions = photos.stream()
                        .map(photo -> classificationJson(photo.src(), "junk", "blurry"))
                        .toArray(String[]::new);
                try {
                    writeShard(prep.prepDir(), montage, decisions);
                } catch (IOException e) {
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
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
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
        public CullReport cull(PrepDir prep, CullOptions opts) {
            return cull(prep, opts, ProgressCallback.NO_OP, CancellationSignal.NEVER);
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts, ProgressCallback progress,
                CancellationSignal cancellation) {
            int total = prep.entries().size();
            int current = 0;
            int culled = 0;
            while (current < total && !cancellation.isCancelled()) {
                String montage = prep.entries().get(current);
                current++;
                try {
                    writeShard(prep.prepDir(), montage);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                culled++;
                progress.tick(current, total);
                if (culled == 1) {
                    firstShardWritten.countDown();
                    try {
                        releaseRemaining.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }
            return new CullReport(culled, total - culled, 0, 0);
        }
    }

    // Proves buildFreshAndDispatch()'s post-PREPPING cancellation check short-circuits before
    // dispatch ever runs - any call into this fake fails the test outright.
    static final class NeverCalledCuller implements VisionCuller {
        @Override
        public String id() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) {
            throw new AssertionError("dispatch must never run after a post-PREPPING cancellation");
        }
    }

    record FixedSettings(String provider, List<CullCategory> categories, ExternalAgentSettings externalAgent)
            implements CullSettings {
        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }
    }
}
