package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.SiftSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.SiftScope;
import photos.sluice.domain.sift.JunkCategory;
import photos.sluice.domain.sift.MontageConfig;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end test wiring the real TileRenderer/MontageBuilder/SidecarWriter/PrepIndexWriter behind
// SiftMontageRenderer, over a synthetic Sorted/Photos tree. HeifDecoder is stubbed: every fixture
// here is a JPEG, so nothing reaches it.
class SiftMontageRendererTest {

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable.
    private static final int PHOTO_WIDTH = 800;
    private static final int PHOTO_HEIGHT = 600;

    private static final List<SiftCategory> CATEGORIES = List.of(
            SiftCategory.of("food", "meals and menus"),
            SiftCategory.of("scenery", "landscapes with nobody in them"));

    @Test
    void buildBatchesIntoMultipleMontagesComputesReceivedFlagsAndDropsUnreviewableFiles(@TempDir final Path root)
            throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final Path a = writePhoto(juneDir, "IMG_20190601_100000.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final Path b = writePhoto(juneDir, "IMG-20190602-WA0001.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        final Path c = writePhoto(juneDir, "IMG_20190603_100000.jpg", Instant.parse("2019-06-03T10:00:00Z"));
        final Path d = writePhoto(juneDir, "IMG_20190604_100000.jpg", Instant.parse("2019-06-04T10:00:00Z"));
        final Path e = writePhoto(juneDir, "IMG_20190605_100000.jpg", Instant.parse("2019-06-05T10:00:00Z"));
        final Path corrupt = writeCorruptFile(juneDir, "corrupt.jpg", Instant.parse("2019-06-06T10:00:00Z"));

        final PrepDir result = renderer(pathsConfig)
                .build(new SiftScope.Year(2019, null), new MontageConfig(64, 2));

        assertThat(result.scope()).isEqualTo("2019");
        assertThat(result.basePath()).isEqualTo(pathsConfig.sorted().resolve("Photos").resolve("2019"));
        assertThat(result.photos()).isEqualTo(5);
        assertThat(result.unreviewable()).containsExactly(corrupt);
        assertThat(result.montages()).isEqualTo(2);
        assertThat(result.entries()).containsExactly("montage-001", "montage-002");

        final Path montage1 = result.prepDir().resolve("montage-001.jpg");
        final Path montage2 = result.prepDir().resolve("montage-002.jpg");
        assertThat(decode(montage1).getWidth()).isPositive();
        assertThat(decode(montage2).getWidth()).isPositive();
        assertThat(Files.exists(result.prepDir().resolve("montage-003.jpg"))).isFalse();

        final String sidecar1 = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar1).isEqualToIgnoringWhitespace("""
                {
                  "montage": "%s",
                  "photos": [
                    { "src": "%s", "name": "IMG_20190601_100000.jpg", "time": "2019-06-01T10:00:00Z", "received": false },
                    { "src": "%s", "name": "IMG-20190602-WA0001.jpg", "time": "2019-06-02T10:00:00Z", "received": true },
                    { "src": "%s", "name": "IMG_20190603_100000.jpg", "time": "2019-06-03T10:00:00Z", "received": false },
                    { "src": "%s", "name": "IMG_20190604_100000.jpg", "time": "2019-06-04T10:00:00Z", "received": false }
                  ]
                }
                """.formatted(jsonEscaped(montage1), jsonEscaped(a), jsonEscaped(b), jsonEscaped(c), jsonEscaped(d)));

        final String sidecar2 = Files.readString(result.prepDir().resolve("montage-002.json"), StandardCharsets.UTF_8);
        assertThat(sidecar2).isEqualToIgnoringWhitespace("""
                {
                  "montage": "%s",
                  "photos": [
                    { "src": "%s", "name": "IMG_20190605_100000.jpg", "time": "2019-06-05T10:00:00Z", "received": false }
                  ]
                }
                """.formatted(jsonEscaped(montage2), jsonEscaped(e)));
        assertThat(sidecar1 + sidecar2).doesNotContain("corrupt.jpg");

        final String index = Files.readString(result.prepDir().resolve("index.json"), StandardCharsets.UTF_8);
        assertThat(index).isEqualToIgnoringWhitespace("""
                {
                  "scope": "2019",
                  "categories": [{ "name": "food", "description": "meals and menus" },
                                 { "name": "scenery", "description": "landscapes with nobody in them" },
                                 { "name": "junk", "description": "%s" }],
                  "basePath": "%s",
                  "photos": 5,
                  "unreviewable": ["%s"],
                  "montages": 2,
                  "entries": ["montage-001", "montage-002"]
                }
                """.formatted(JunkCategory.category().description(), jsonEscaped(result.basePath()),
                jsonEscaped(corrupt)));
    }

    @Test
    void yearScopeNarrowedToSpecificMonthsExcludesOtherMonths(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final Path julyDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("07");
        writePhoto(juneDir, "june.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(julyDir, "july.jpg", Instant.parse("2019-07-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig)
                .build(new SiftScope.Year(2019, List.of(6)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
        assertThat(result.unreviewable()).isEmpty();
        final String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("june.jpg").doesNotContain("july.jpg");
    }

    @Test
    void aRequestedMonthDirectoryThatDoesNotExistIsSkippedRatherThanThrowing(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "june.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        // 2019/07 is never created on disk.

        final PrepDir result = renderer(pathsConfig)
                .build(new SiftScope.Year(2019, List.of(6, 7)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
    }

    @Test
    void oldestNPicksTheTrueGlobalOldestAcrossDifferentYears(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path photosRoot = pathsConfig.sorted().resolve("Photos");
        writePhoto(photosRoot.resolve("2018").resolve("01"), "oldest.jpg", Instant.parse("2018-01-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2019").resolve("06"), "middle.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2020").resolve("01"), "newest.jpg", Instant.parse("2020-01-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig).build(new SiftScope.OldestN(2), new MontageConfig(64, 2));

        assertThat(result.scope()).isEqualTo("oldest-2");
        assertThat(result.basePath()).isEqualTo(photosRoot);
        assertThat(result.photos()).isEqualTo(2);
        final String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("oldest.jpg").contains("middle.jpg").doesNotContain("newest.jpg");
    }

    @Test
    void oldestNCapsBeforeTheUnreviewableFilterSoAnUnreviewableFileWithinTheWindowIsNotBackfilled(
            @TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final Path oldestCorrupt = writeCorruptFile(juneDir, "oldest-corrupt.jpg", Instant.parse("2019-06-01T00:00" +
                ":00Z"));
        writePhoto(juneDir, "second-oldest.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        writePhoto(juneDir, "third-oldest.jpg", Instant.parse("2019-06-03T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig).build(new SiftScope.OldestN(2), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
        assertThat(result.unreviewable()).containsExactly(oldestCorrupt);
        final String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("second-oldest.jpg").doesNotContain("third-oldest.jpg");
    }

    @Test
    void aScopeWithNoReviewablePhotosProducesAnEmptyPrepDirWithoutThrowing(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final Path corrupt = writeCorruptFile(juneDir, "corrupt.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig)
                .build(new SiftScope.Year(2019, List.of(6)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(0);
        assertThat(result.unreviewable()).containsExactly(corrupt);
        assertThat(result.montages()).isEqualTo(0);
        assertThat(result.entries()).isEmpty();
        assertThat(Files.exists(result.prepDir().resolve("index.json"))).isTrue();
        assertThat(Files.exists(result.prepDir().resolve("montage-001.jpg"))).isFalse();
    }

    @Test
    void receivedFlagMatchesTheWhatsAppFilenamePatternCaseInsensitively(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "img-20190601-wa0001.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig)
                .build(new SiftScope.Year(2019, List.of(6)), MontageConfig.defaults());

        final String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("\"received\":true");
    }

    @Test
    void rerunningWithFewerPhotosClearsStaleMontageFilesFromAPriorLargerRun(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final List<Path> files = List.of(
                writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z")),
                writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z")),
                writePhoto(juneDir, "c.jpg", Instant.parse("2019-06-03T00:00:00Z")),
                writePhoto(juneDir, "d.jpg", Instant.parse("2019-06-04T00:00:00Z")),
                writePhoto(juneDir, "e.jpg", Instant.parse("2019-06-05T00:00:00Z")));
        final var scope = new SiftScope.Year(2019, null);
        final var config = new MontageConfig(64, 2);
        final SiftMontageRenderer renderer = renderer(pathsConfig);

        final PrepDir first = renderer.build(scope, config);
        assertThat(first.montages()).isEqualTo(2);
        final Path staleMontage = first.prepDir().resolve("montage-002.jpg");
        final Path staleSidecar = first.prepDir().resolve("montage-002.json");
        assertThat(Files.exists(staleMontage)).isTrue();

        Files.delete(files.get(2));
        Files.delete(files.get(3));
        Files.delete(files.get(4));
        final PrepDir second = renderer.build(scope, config);

        assertThat(second.montages()).isEqualTo(1);
        assertThat(Files.exists(staleMontage)).isFalse();
        assertThat(Files.exists(staleSidecar)).isFalse();
    }

    @Test
    void progressIsCountedInPhotosReadRatherThanSheetsWritten(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        writePhoto(juneDir, "c.jpg", Instant.parse("2019-06-03T00:00:00Z"));
        writePhoto(juneDir, "d.jpg", Instant.parse("2019-06-04T00:00:00Z"));
        writePhoto(juneDir, "e.jpg", Instant.parse("2019-06-05T00:00:00Z"));

        final List<String> ticks = new ArrayList<>();
        final PrepDir result = renderer(pathsConfig).build(new SiftScope.Year(2019, null), new MontageConfig(64, 2),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(result.montages()).isEqualTo(2);
        assertThat(ticks).containsExactly("1/5", "2/5", "3/5", "4/5", "5/5");
    }

    @Test
    void cancellationBeforeRenderingAnyCandidateLeavesNoPrepDirAtAllAndReturnsNull(@TempDir final Path root)
            throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig).build(new SiftScope.Year(2019, null), MontageConfig.defaults(),
                ProgressCallback.NO_OP, () -> true);

        assertThat(result).isNull();
        assertThat(Files.exists(pathsConfig.logs().resolve("sift-prep").resolve("2019"))).isFalse();
    }

    @Test
    void cancellationAfterOneCandidateRendersStillLeavesDiskUntouchedSinceRenderRunsBeforeClearing(
            @TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        // Not cancelled for the first candidate's check, cancelled from the second check onward,
        // so one tile is rendered in memory before the cancellation trips.
        final AtomicInteger checks = new AtomicInteger();
        final CancellationSignal cancelBeforeSecondCandidate = () -> checks.incrementAndGet() > 1;

        final PrepDir result = renderer(pathsConfig).build(new SiftScope.Year(2019, null), new MontageConfig(64, 2),
                ProgressCallback.NO_OP, cancelBeforeSecondCandidate);

        assertThat(result).isNull();
        assertThat(Files.exists(pathsConfig.logs().resolve("sift-prep").resolve("2019"))).isFalse();
    }

    @Test
    void cancellationMidBatchClearsTheMontagesItHadAlreadyWritten(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        // tilesPerRow=1 puts one photo per montage, so two photos make two montages - the write
        // loop below stops after the first.
        final var config = new MontageConfig(64, 1);
        // The signal is checked once per candidate in the render pass first (2 calls, both false
        // here so both candidates fully render). Then the montage-write loop's own checks begin
        // (one per montage): false for the first montage, true from the second montage onward.
        final AtomicInteger checks = new AtomicInteger();
        final CancellationSignal cancelBeforeSecondMontage = () -> checks.incrementAndGet() > 3;
        // Watched on disk while the run is still going, because afterwards there is nothing left
        // to tell apart. Progress cannot answer this: it counts photos read, so it reports the
        // same two ticks whether a sheet was ever written or not.
        final Path prepDir = pathsConfig.logs().resolve("sift-prep").resolve("2019");
        final AtomicInteger sheetsSeenOnDisk = new AtomicInteger();
        final CancellationSignal watchingCancel = () -> {
            sheetsSeenOnDisk.set(Math.max(sheetsSeenOnDisk.get(), montageImagesIn(prepDir)));
            return cancelBeforeSecondMontage.isCancelled();
        };

        final PrepDir result = renderer(pathsConfig).build(new SiftScope.Year(2019, null), config,
                ProgressCallback.NO_OP, watchingCancel);

        assertThat(result).isNull();
        assertThat(sheetsSeenOnDisk.get()).isEqualTo(1);
        assertThat(prepDir).doesNotExist();
    }

    // Proved against a set that is not the default, so a renderer ignoring its settings and
    // hardcoding something would fail rather than coincidentally match.
    @Test
    void buildStampsTheConfiguredCategorySetOntoThePrepDirAndItsIndex(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "IMG_20190601_100000.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final List<SiftCategory> configured = List.of(
                SiftCategory.of("blurry", "out of focus"),
                SiftCategory.of("receipts", "photographed paperwork"));

        final PrepDir result = renderer(pathsConfig, configured)
                .build(new SiftScope.Year(2019, null), new MontageConfig(64, 2));

        assertThat(result.categories()).containsExactly(configured.get(0), configured.get(1),
                JunkCategory.category());
        assertThat(Files.readString(result.prepDir().resolve("index.json"), StandardCharsets.UTF_8))
                .contains("\"categories\":[{\"name\":\"blurry\",\"description\":\"out of focus\"},"
                        + "{\"name\":\"receipts\",\"description\":\"photographed paperwork\"},"
                        + "{\"name\":\"junk\",\"description\":\""
                        + JunkCategory.category().description() + "\"}]");
    }

    private static PathsConfig pathsConfig(final Path root) {
        return SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
    }

    private static int montageImagesIn(final Path prepDir) {
        if (!Files.isDirectory(prepDir)) {
            return 0;
        }
        try (final var files = Files.list(prepDir)) {
            return (int) files.filter(f -> f.getFileName().toString().endsWith(".jpg")).count();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SiftMontageRenderer renderer(final PathsConfig pathsConfig) {
        return renderer(pathsConfig, CATEGORIES);
    }

    private static SiftMontageRenderer renderer(final PathsConfig pathsConfig, final List<SiftCategory> categories) {
        final HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        return new SiftMontageRenderer(
                new TileRenderer(stubHeifDecoder), new MontageBuilder(), new SidecarWriter(),
                new PrepIndexWriter(), new NioMediaStore(), pathsConfig, new FixedSettings(categories));
    }

    // Only categories() is ever read here.
    private record FixedSettings(List<SiftCategory> categories) implements SiftSettings {

        @Override
        public String provider() {
            return "any-provider";
        }

        @Override
        public SiftProviderSettings providerSettings() {
            return SiftProviderSettings.unset();
        }

        @Override
        public SiftProviderSettings providerSettings(final String providerId) {
            return SiftProviderSettings.unset();
        }

        @Override
        public MontageConfig montage() {
            return MontageConfig.defaults();
        }
    }

    private static Path writePhoto(final Path dir, final String name, final Instant mtime) throws IOException {
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

    private static Path writeCorruptFile(final Path dir, final String name, final Instant mtime) throws IOException {
        Files.createDirectories(dir);
        final Path file = dir.resolve(name);
        Files.writeString(file, "not a real image");
        Files.setLastModifiedTime(file, FileTime.from(mtime));
        return file;
    }

    private static BufferedImage decode(final Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
