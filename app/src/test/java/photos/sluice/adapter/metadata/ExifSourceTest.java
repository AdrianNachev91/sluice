package photos.sluice.adapter.metadata;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.model.MediaFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExifSourceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/dating");

    private final ExifSource source = new ExifSource();

    @Test
    void resolvesDateTimeOriginalFromJpeg() {
        final var file = new MediaFile(FIXTURES.resolve("synthetic-exif.jpg"));

        final Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDateTime.of(2021, 3, 15, 10, 30, 0));
    }

    @Test
    void resolvesDateTimeOriginalFromIphoneHeic() {
        final var file = new MediaFile(FIXTURES.resolve("iphone-exif.heic"));

        final Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDateTime.of(2018, 2, 5, 15, 11, 44));
    }

    @Test
    void fallsBackToDateTimeDigitizedWhenOriginalAbsent() {
        final var file = new MediaFile(FIXTURES.resolve("digitized-only-exif.jpg"));

        final Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).contains(LocalDateTime.of(2019, 6, 20, 8, 0, 0));
    }

    @Test
    void returnsEmptyWhenImageHasNoExifData() {
        final var file = new MediaFile(FIXTURES.resolve("no-exif.jpg"));

        final Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenFileIsNotAnImage() {
        final var file = new MediaFile(FIXTURES.resolve("not-an-image.txt"));

        final Optional<LocalDateTime> result = source.resolve(file, null);

        assertThat(result).isEmpty();
    }
}
