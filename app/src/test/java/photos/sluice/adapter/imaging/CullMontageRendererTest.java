package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.out.HeifDecoder;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;

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

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end test wiring the real TileRenderer/MontageBuilder/SidecarWriter/PrepIndexWriter behind
// CullMontageRenderer, over a synthetic Sorted/Photos tree. HeifDecoder is stubbed - HEIC/AVIF
// decode paths already have real-fixture coverage in TileRendererTest, not this chunk's job to
// re-prove.
class CullMontageRendererTest {

    // Above LowResGate.MIN_DIMENSION (640) on the long side, so these photos are always reviewable.
    private static final int PHOTO_WIDTH = 800;
    private static final int PHOTO_HEIGHT = 600;

    @Test
    void buildBatchesIntoMultipleMontagesComputesReceivedFlagsAndDropsUnreviewableFiles(@TempDir Path root)
            throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        Path a = writePhoto(juneDir, "IMG_20190601_100000.jpg", Instant.parse("2019-06-01T10:00:00Z"));
        Path b = writePhoto(juneDir, "IMG-20190602-WA0001.jpg", Instant.parse("2019-06-02T10:00:00Z"));
        Path c = writePhoto(juneDir, "IMG_20190603_100000.jpg", Instant.parse("2019-06-03T10:00:00Z"));
        Path d = writePhoto(juneDir, "IMG_20190604_100000.jpg", Instant.parse("2019-06-04T10:00:00Z"));
        Path e = writePhoto(juneDir, "IMG_20190605_100000.jpg", Instant.parse("2019-06-05T10:00:00Z"));
        Path corrupt = writeCorruptFile(juneDir, "corrupt.jpg", Instant.parse("2019-06-06T10:00:00Z"));

        PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, null), new MontageConfig(64, 2));

        assertThat(result.scope()).isEqualTo("2019");
        assertThat(result.basePath()).isEqualTo(pathsConfig.sorted().resolve("Photos").resolve("2019"));
        assertThat(result.photos()).isEqualTo(5);
        assertThat(result.unreviewable()).containsExactly(corrupt);
        assertThat(result.montages()).isEqualTo(2);
        assertThat(result.entries()).containsExactly("montage-001", "montage-002");

        Path montage1 = result.prepDir().resolve("montage-001.jpg");
        Path montage2 = result.prepDir().resolve("montage-002.jpg");
        assertThat(decode(montage1).getWidth()).isPositive();
        assertThat(decode(montage2).getWidth()).isPositive();
        assertThat(Files.exists(result.prepDir().resolve("montage-003.jpg"))).isFalse();

        String sidecar1 = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
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

        String sidecar2 = Files.readString(result.prepDir().resolve("montage-002.json"), StandardCharsets.UTF_8);
        assertThat(sidecar2).isEqualToIgnoringWhitespace("""
                {
                  "montage": "%s",
                  "photos": [
                    { "src": "%s", "name": "IMG_20190605_100000.jpg", "time": "2019-06-05T10:00:00Z", "received": false }
                  ]
                }
                """.formatted(jsonEscaped(montage2), jsonEscaped(e)));
        assertThat(sidecar1 + sidecar2).doesNotContain("corrupt.jpg");

        String index = Files.readString(result.prepDir().resolve("index.json"), StandardCharsets.UTF_8);
        assertThat(index).isEqualToIgnoringWhitespace("""
                {
                  "scope": "2019",
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
    void yearScopeNarrowedToSpecificMonthsExcludesOtherMonths(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        Path julyDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("07");
        writePhoto(juneDir, "june.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(julyDir, "july.jpg", Instant.parse("2019-07-01T00:00:00Z"));

        PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
        assertThat(result.unreviewable()).isEmpty();
        String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("june.jpg").doesNotContain("july.jpg");
    }

    @Test
    void aRequestedMonthDirectoryThatDoesNotExistIsSkippedRatherThanThrowing(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "june.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        // 2019/07 is never created on disk.

        PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, List.of(6, 7)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(1);
    }

    @Test
    void oldestNPicksTheTrueGlobalOldestAcrossDifferentYears(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path photosRoot = pathsConfig.sorted().resolve("Photos");
        writePhoto(photosRoot.resolve("2018").resolve("01"), "oldest.jpg", Instant.parse("2018-01-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2019").resolve("06"), "middle.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(photosRoot.resolve("2020").resolve("01"), "newest.jpg", Instant.parse("2020-01-01T00:00:00Z"));

        PrepDir result = renderer(pathsConfig).build(new CullScope.OldestN(2), new MontageConfig(64, 2));

        assertThat(result.scope()).isEqualTo("oldest-2");
        assertThat(result.basePath()).isEqualTo(photosRoot);
        assertThat(result.photos()).isEqualTo(2);
        String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("oldest.jpg").contains("middle.jpg").doesNotContain("newest.jpg");
    }

    @Test
    void oldestNCapsBeforeTheUnreviewableFilterSoAnUnreviewableFileWithinTheWindowIsNotBackfilled(
            @TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        Path oldestCorrupt = writeCorruptFile(juneDir, "oldest-corrupt.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "second-oldest.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        writePhoto(juneDir, "third-oldest.jpg", Instant.parse("2019-06-03T00:00:00Z"));

        PrepDir result = renderer(pathsConfig).build(new CullScope.OldestN(2), MontageConfig.defaults());

        // The 2 oldest by mtime are oldest-corrupt and second-oldest. Capping to n happens before
        // the unreviewable filter, so the corrupt file's slot is dropped rather than backfilled from
        // third-oldest, even though third-oldest would itself be reviewable.
        assertThat(result.photos()).isEqualTo(1);
        assertThat(result.unreviewable()).containsExactly(oldestCorrupt);
        String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("second-oldest.jpg").doesNotContain("third-oldest.jpg");
    }

    @Test
    void aScopeWithNoReviewablePhotosProducesAnEmptyPrepDirWithoutThrowing(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        Path corrupt = writeCorruptFile(juneDir, "corrupt.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

        assertThat(result.photos()).isEqualTo(0);
        assertThat(result.unreviewable()).containsExactly(corrupt);
        assertThat(result.montages()).isEqualTo(0);
        assertThat(result.entries()).isEmpty();
        assertThat(Files.exists(result.prepDir().resolve("index.json"))).isTrue();
        assertThat(Files.exists(result.prepDir().resolve("montage-001.jpg"))).isFalse();
    }

    @Test
    void receivedFlagMatchesTheWhatsAppFilenamePatternCaseInsensitively(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "img-20190601-wa0001.jpg", Instant.parse("2019-06-01T00:00:00Z"));

        PrepDir result = renderer(pathsConfig)
                .build(new CullScope.Year(2019, List.of(6)), MontageConfig.defaults());

        String sidecar = Files.readString(result.prepDir().resolve("montage-001.json"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("\"received\":true");
    }

    @Test
    void rerunningWithFewerPhotosClearsStaleMontageFilesFromAPriorLargerRun(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        List<Path> files = List.of(
                writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z")),
                writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z")),
                writePhoto(juneDir, "c.jpg", Instant.parse("2019-06-03T00:00:00Z")),
                writePhoto(juneDir, "d.jpg", Instant.parse("2019-06-04T00:00:00Z")),
                writePhoto(juneDir, "e.jpg", Instant.parse("2019-06-05T00:00:00Z")));
        var scope = new CullScope.Year(2019, null);
        var config = new MontageConfig(64, 2);
        CullMontageRenderer renderer = renderer(pathsConfig);

        PrepDir first = renderer.build(scope, config);
        assertThat(first.montages()).isEqualTo(2);
        Path staleMontage = first.prepDir().resolve("montage-002.jpg");
        Path staleSidecar = first.prepDir().resolve("montage-002.json");
        assertThat(Files.exists(staleMontage)).isTrue();

        Files.delete(files.get(2));
        Files.delete(files.get(3));
        Files.delete(files.get(4));
        PrepDir second = renderer.build(scope, config);

        assertThat(second.montages()).isEqualTo(1);
        assertThat(Files.exists(staleMontage)).isFalse();
        assertThat(Files.exists(staleSidecar)).isFalse();
    }

    @Test
    void progressCallbackTicksOnceForEachMontageWritten(@TempDir Path root) throws IOException {
        var pathsConfig = pathsConfig(root);
        Path juneDir = pathsConfig.sorted().resolve("Photos").resolve("2019").resolve("06");
        writePhoto(juneDir, "a.jpg", Instant.parse("2019-06-01T00:00:00Z"));
        writePhoto(juneDir, "b.jpg", Instant.parse("2019-06-02T00:00:00Z"));
        writePhoto(juneDir, "c.jpg", Instant.parse("2019-06-03T00:00:00Z"));
        writePhoto(juneDir, "d.jpg", Instant.parse("2019-06-04T00:00:00Z"));
        writePhoto(juneDir, "e.jpg", Instant.parse("2019-06-05T00:00:00Z"));

        List<String> ticks = new ArrayList<>();
        PrepDir result = renderer(pathsConfig).build(new CullScope.Year(2019, null), new MontageConfig(64, 2),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(result.montages()).isEqualTo(2);
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private static PathsConfig pathsConfig(Path root) {
        return new PathsConfig(new PathsProperties(
                root.toString(), root.resolve("Library").toString(), root.resolve("Inbox").toString()));
    }

    private static CullMontageRenderer renderer(PathsConfig pathsConfig) {
        HeifDecoder stubHeifDecoder = _ -> Optional.empty();
        return new CullMontageRenderer(
                new TileRenderer(stubHeifDecoder), new MontageBuilder(), new SidecarWriter(),
                new PrepIndexWriter(), new NioMediaStore(), pathsConfig);
    }

    private static Path writePhoto(Path dir, String name, Instant mtime) throws IOException {
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

    private static Path writeCorruptFile(Path dir, String name, Instant mtime) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "not a real image");
        Files.setLastModifiedTime(file, FileTime.from(mtime));
        return file;
    }

    private static BufferedImage decode(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
