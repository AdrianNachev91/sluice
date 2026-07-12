package photos.sluice.domain.dating;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.domain.model.Confidence;
import photos.sluice.domain.model.DateResult;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DateResolverTest {

    private static final Path FIXTURES = Path.of("src/test/resources/dating");

    private final DateResolver resolver =
            new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());

    @Test
    void sidecarWinsOverExif(@TempDir Path dir) throws IOException {
        MediaFile file = new MediaFile(FIXTURES.resolve("synthetic-exif.jpg")); // exif date 2021-03-15
        LocalDateTime sidecarDate = LocalDateTime.of(2015, 5, 5, 12, 0, 0);
        TakeoutSidecar sidecar = new TakeoutSidecar(writeSidecar(dir, sidecarDate));

        DateResult result = resolver.resolve(file, sidecar);

        assertThat(result).isEqualTo(new DateResult(sidecarDate, Confidence.TRUSTED, "sidecar"));
    }

    @Test
    void exifWinsWhenNoSidecar() {
        MediaFile file = new MediaFile(FIXTURES.resolve("synthetic-exif.jpg"));

        DateResult result = resolver.resolve(file, null);

        assertThat(result).isEqualTo(
                new DateResult(LocalDateTime.of(2021, 3, 15, 10, 30, 0), Confidence.TRUSTED, "exif"));
    }

    @Test
    void resolvesHeicExifWithoutExiftool() {
        MediaFile file = new MediaFile(FIXTURES.resolve("iphone-exif.heic"));

        DateResult result = resolver.resolve(file, null);

        assertThat(result).isEqualTo(
                new DateResult(LocalDateTime.of(2018, 2, 5, 15, 11, 44), Confidence.TRUSTED, "exif"));
    }

    @Test
    void filenameWinsWhenNoSidecarAndNoExif() {
        MediaFile file = new MediaFile(Path.of("IMG_20210315_103000.jpg"));

        DateResult result = resolver.resolve(file, null);

        assertThat(result).isEqualTo(
                new DateResult(LocalDate.of(2021, 3, 15).atStartOfDay(), Confidence.TRUSTED, "filename"));
    }

    @Test
    void trustedFilenameDateBypassesThePlausibilityGuard() {
        // 1999 predates the plausibility floor, but only LOW-confidence sources are guarded.
        MediaFile file = new MediaFile(Path.of("IMG_19990101_120000.jpg"));

        DateResult result = resolver.resolve(file, null);

        assertThat(result).isEqualTo(
                new DateResult(LocalDate.of(1999, 1, 1).atStartOfDay(), Confidence.TRUSTED, "filename"));
    }

    @Test
    void mtimeIsTheLowConfidenceLastResort(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("plain-file.jpg"); // no date-pattern name, no exif data
        Files.writeString(file, "not an image");
        LocalDateTime mtime = LocalDateTime.of(2022, 6, 1, 9, 0, 0);
        Files.setLastModifiedTime(file, FileTime.from(mtime.atZone(ZoneId.systemDefault()).toInstant()));

        DateResult result = resolver.resolve(new MediaFile(file), null);

        assertThat(result).isEqualTo(new DateResult(mtime, Confidence.LOW, "mtime"));
    }

    @Test
    void implausibleMtimeDateBecomesUnsortable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("plain-file.jpg");
        Files.writeString(file, "not an image");
        LocalDateTime preYear2000 = LocalDateTime.of(1995, 1, 1, 0, 0, 0);
        Files.setLastModifiedTime(file, FileTime.from(preYear2000.atZone(ZoneId.systemDefault()).toInstant()));

        DateResult result = resolver.resolve(new MediaFile(file), null);

        // The guard only flips the confidence; the date and source name it rejected are preserved
        // so the caller can still report what was found, not just that it was unsortable.
        assertThat(result).isEqualTo(new DateResult(preYear2000, Confidence.UNSORTABLE, "mtime"));
    }

    @Test
    void futureMtimeDateBecomesUnsortable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("plain-file.jpg");
        Files.writeString(file, "not an image");
        LocalDateTime tenYearsOut = LocalDateTime.now().plusYears(10).withNano(0);
        Files.setLastModifiedTime(file, FileTime.from(tenYearsOut.atZone(ZoneId.systemDefault()).toInstant()));

        DateResult result = resolver.resolve(new MediaFile(file), null);

        assertThat(result).isEqualTo(new DateResult(tenYearsOut, Confidence.UNSORTABLE, "mtime"));
    }

    @Test
    void allSourcesFailingStillProducesAnUnsortableResult() {
        // No sidecar, no exif (nonexistent path), no date-pattern filename, and no mtime to read.
        MediaFile file = new MediaFile(Path.of("does-not-exist/plain-file.jpg"));

        DateResult result = resolver.resolve(file, null);

        assertThat(result.confidence()).isEqualTo(Confidence.UNSORTABLE);
        assertThat(result.source()).isEqualTo("none");
    }

    private static Path writeSidecar(Path dir, LocalDateTime photoTakenAt) throws IOException {
        long epochSeconds = photoTakenAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        Path sidecar = dir.resolve("IMG_0001.jpg.supplemental-metadata.json");
        Files.writeString(sidecar, """
                {
                  "photoTakenTime": {
                    "timestamp": "%d",
                    "formatted": "May 5, 2015"
                  }
                }""".formatted(epochSeconds));
        return sidecar;
    }
}
