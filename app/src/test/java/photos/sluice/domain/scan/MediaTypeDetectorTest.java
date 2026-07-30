package photos.sluice.domain.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.domain.model.MediaType;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypeDetectorTest {

    private final MediaTypeDetector detector = new MediaTypeDetector();

    @ParameterizedTest
    @ValueSource(strings = {
            "jpg", "jpeg", "png", "heic", "heif", "webp", "bmp", "gif", "tif", "tiff", "dng",
            "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "svg", "avif"
    })
    void classifiesPhotoExtensions(final String extension) {
        final Optional<MediaType> result = this.detector.classify(Path.of("IMG_1234." + extension));

        assertThat(result).contains(MediaType.PHOTO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov", "mkv", "avi", "m4v", "3gp", "webm", "wmv", "mpg", "mpeg", "mts", "m2ts",
            "flv"})
    void classifiesVideoExtensions(final String extension) {
        final Optional<MediaType> result = this.detector.classify(Path.of("VID_1234." + extension));

        assertThat(result).contains(MediaType.VIDEO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"JPG", "Mp4", "HEIC"})
    void classificationIsCaseInsensitive(final String extension) {
        final Optional<MediaType> result = this.detector.classify(Path.of("IMG_1234." + extension));

        assertThat(result).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"txt", "json", "ini", "ds_store"})
    void returnsEmptyForUnrecognizedExtension(final String extension) {
        final Optional<MediaType> result = this.detector.classify(Path.of("file." + extension));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForFileWithNoExtension() {
        final Optional<MediaType> result = this.detector.classify(Path.of("README"));

        assertThat(result).isEmpty();
    }
}
