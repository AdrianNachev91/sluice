package photos.sluice.adapter.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TakeoutJsonSourceTest {

    private final TakeoutJsonSource source = new TakeoutJsonSource();
    private final MediaFile anyFile = new MediaFile(Path.of("IMG_0001.jpg"));

    @Test
    void resolvesTimestampToLocalTimeInSystemZone(@TempDir Path dir) throws IOException {
        LocalDateTime photoTakenAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        long epochSeconds = photoTakenAt.atZone(ZoneId.systemDefault()).toEpochSecond();
        Path sidecar = writeSidecar(dir, """
                {
                  "photoTakenTime": {
                    "timestamp": "%d",
                    "formatted": "Jan 1, 2021"
                  }
                }""".formatted(epochSeconds));

        Optional<LocalDateTime> result = source.resolve(anyFile, new TakeoutSidecar(sidecar));

        assertThat(result).contains(photoTakenAt);
    }

    @Test
    void returnsEmptyWhenSidecarAbsent() {
        Optional<LocalDateTime> result = source.resolve(anyFile, null);

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenTimestampFieldMissing(@TempDir Path dir) throws IOException {
        Path sidecar = writeSidecar(dir, """
                {
                  "photoTakenTime": {
                    "formatted": "Jan 1, 2021"
                  }
                }""");

        Optional<LocalDateTime> result = source.resolve(anyFile, new TakeoutSidecar(sidecar));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenPhotoTakenTimeObjectMissing(@TempDir Path dir) throws IOException {
        Path sidecar = writeSidecar(dir, """
                {
                  "title": "IMG_0001.jpg"
                }""");

        Optional<LocalDateTime> result = source.resolve(anyFile, new TakeoutSidecar(sidecar));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOnMalformedJson(@TempDir Path dir) throws IOException {
        Path sidecar = writeSidecar(dir, "{not valid json");

        Optional<LocalDateTime> result = source.resolve(anyFile, new TakeoutSidecar(sidecar));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenTimestampIsNotTextual(@TempDir Path dir) throws IOException {
        // Real Takeout sidecars always quote the timestamp, but guard the numeric-node shape too.
        Path sidecar = writeSidecar(dir, """
                {
                  "photoTakenTime": {
                    "timestamp": 1609459200
                  }
                }""");

        Optional<LocalDateTime> result = source.resolve(anyFile, new TakeoutSidecar(sidecar));

        assertThat(result).isEmpty();
    }

    private static Path writeSidecar(Path dir, String json) throws IOException {
        Path file = dir.resolve("IMG_0001.jpg.supplemental-metadata.json");
        Files.writeString(file, json);
        return file;
    }
}
