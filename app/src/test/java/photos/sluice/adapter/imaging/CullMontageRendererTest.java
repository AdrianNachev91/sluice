package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.WatchMode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
// CullMontageRenderer, over a synthetic Sorted/Photos tree. HeifDecoder is stubbed - HEIC/AVIF
// decode paths already have real-fixture coverage in TileRendererTest, not this test's job to
// re-prove.
class CullMontageRendererTest {

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable.
    private static final int PHOTO_WIDTH = 800;
    private static final int PHOTO_HEIGHT = 600;

    private static final List<CullCategory> CATEGORIES = List.of(
            new CullCategory("junk", "objectively worthless shots"),
            new CullCategory("scenery", "landscapes with nobody in them"));

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
                .build(new CullScope.Year(2019, null), new MontageConfig(64, 2));

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
                  "categories": ["junk", "scenery"],
                  "basePath": "%s",
                  "photos": 5,
                  "unreviewable": ["%s"],
                  "montages": 2,
                  "prepDir": "%s",
                  "entries": ["montage-001", "montage-002"]
                }
                """.formatted(jsonEscaped(result.basePath()), jsonEscaped(corrupt), jsonEscaped(result.prepDir())));
    }

    @Test
    void yearScopeNarrowedToSpecificMonthsExcludesOtherMonths(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        final Path julyDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("07");
        writePhoto(juneDir, "june.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(julyDir, "july.jpg", Instant.parse("2019-07-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

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
                .build(new CullScope.Year(2019, List.of(6, 7)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
    }

    @Test
    void oldestNPicksTheTrueGlobalOldestAcrossDifferentYears(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path photosRoot = pathsConfig.sorted().resolve("Photos");
        writePhoto(photosRoot.resolve("2018").resolve("01"), "oldest.jpg", Instant.parse("2018-01-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2019").resolve("06"), "middle.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2020").resolve("01"), "newest.jpg", Instant.parse("2020-01-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig).build(new CullScope.OldestN(2), new MontageConfig(64, 2));

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

        final PrepDir result = renderer(pathsConfig).build(new CullScope.OldestN(2), MontageConfig.defaults());

        // The 2 oldest by mtime are oldest-corrupt and second-oldest. Capping to n happens before
        // the unreviewable filter, so the corrupt file's slot is dropped rather than backfilled from
        // third-oldest, even though third-oldest would itself be reviewable.
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
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

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
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

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
        final var scope = new CullScope.Year(2019, null);
        final var config = new MontageConfig(64, 2);
        final CullMontageRenderer renderer = renderer(pathsConfig);

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
    void progressCallbackTicksOnceForEachMontageWritten(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        writePhoto(juneDir, "c.jpg", Instant.parse("2019-06-03T00:00:00Z"));
        writePhoto(juneDir, "d.jpg", Instant.parse("2019-06-04T00:00:00Z"));
        writePhoto(juneDir, "e.jpg", Instant.parse("2019-06-05T00:00:00Z"));

        final List<String> ticks = new ArrayList<>();
        final PrepDir result = renderer(pathsConfig).build(new CullScope.Year(2019, null), new MontageConfig(64, 2),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(result.montages()).isEqualTo(2);
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancellationBeforeRenderingAnyCandidateLeavesNoPrepDirAtAllAndReturnsNull(@TempDir final Path root)
            throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        final PrepDir result = renderer(pathsConfig).build(new CullScope.Year(2019, null), MontageConfig.defaults(),
                ProgressCallback.NO_OP, () -> true);

        assertThat(result).isNull();
        assertThat(Files.exists(pathsConfig.logs().resolve("cull-prep").resolve("2019"))).isFalse();
    }

    @Test
    void cancellationAfterOneCandidateRendersStillLeavesDiskUntouchedSinceRenderRunsBeforeClearing(
            @TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        // Not cancelled for the first candidate's check, cancelled from the second check onward,
        // so one tile is rendered in memory before the cancellation trips. That proves even a
        // partially-rendered tile doesn't leave anything on disk, since the render pass runs
        // entirely before clearPrepDir().
        final AtomicInteger checks = new AtomicInteger();
        final CancellationSignal cancelBeforeSecondCandidate = () -> checks.incrementAndGet() > 1;

        final PrepDir result = renderer(pathsConfig).build(new CullScope.Year(2019, null), new MontageConfig(64, 2),
                ProgressCallback.NO_OP, cancelBeforeSecondCandidate);

        assertThat(result).isNull();
        assertThat(Files.exists(pathsConfig.logs().resolve("cull-prep").resolve("2019"))).isFalse();
    }

    // A scope is occupied by any prep dir holding files. A half-rendered one left lying around
    // would refuse every later cull of that scope, while holding nothing worth refusing over. It
    // records no decision at all, and its montages cost only the time to render them again.
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
        // Ticked once per montage actually written to disk, so this is what separates "the cleanup
        // removed montage-001" from "montage-001 was never written in the first place". Without it
        // the empty-directory assertion below would hold under either.
        final AtomicInteger montagesWritten = new AtomicInteger();

        final PrepDir result = renderer(pathsConfig).build(new CullScope.Year(2019, null), config,
                (current, _) -> montagesWritten.set(current), cancelBeforeSecondMontage);

        assertThat(result).isNull();
        assertThat(montagesWritten.get()).isEqualTo(1);
        assertThat(pathsConfig.logs().resolve("cull-prep").resolve("2019")).doesNotExist();
    }

    // The category set is captured at prep time and travels with the run. Proved against a set that
    // is not the default, so a renderer ignoring its settings and hardcoding something would fail
    // rather than coincidentally match.
    @Test
    void buildStampsTheConfiguredCategorySetOntoThePrepDirAndItsIndex(@TempDir final Path root) throws IOException {
        final var pathsConfig = pathsConfig(root);
        final Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "IMG_20190601_100000.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        final List<CullCategory> configured = List.of(
                new CullCategory("blurry", "out of focus"),
                new CullCategory("receipts", "photographed paperwork"));

        final PrepDir result = renderer(pathsConfig, configured)
                .build(new CullScope.Year(2019, null), new MontageConfig(64, 2));

        assertThat(result.categories()).containsExactly("blurry", "receipts");
        assertThat(Files.readString(result.prepDir().resolve("index.json"), StandardCharsets.UTF_8))
                .contains("\"categories\":[\"blurry\",\"receipts\"]");
    }

    private static PathsConfig pathsConfig(final Path root) {
        return new PathsConfig(new PathsProperties(
                root.toString(), root.resolve("Library").toString(), root.resolve("Inbox").toString()));
    }

    private static CullMontageRenderer renderer(final PathsConfig pathsConfig) {
        return renderer(pathsConfig, CATEGORIES);
    }

    private static CullMontageRenderer renderer(final PathsConfig pathsConfig, final List<CullCategory> categories) {
        final HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        return new CullMontageRenderer(
                new TileRenderer(stubHeifDecoder), new MontageBuilder(), new SidecarWriter(),
                new PrepIndexWriter(), new NioMediaStore(), pathsConfig, new FixedSettings(categories));
    }

    // Only categories() is ever read here. The rest of the port is provider routing, which the
    // renderer has no part in.
    private record FixedSettings(List<CullCategory> categories) implements CullSettings {

        @Override
        public String provider() {
            return VisionCuller.MANUAL_MODE_PROVIDER_ID;
        }

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL);
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
