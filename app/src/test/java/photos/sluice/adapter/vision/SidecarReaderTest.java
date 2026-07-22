package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidecarReaderTest {

    private final SidecarReader reader = new SidecarReader();

    @Test
    void returnsTheSrcOfEveryListedPhoto(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                {
                  "montage": "%s",
                  "photos": [
                    { "src": "%s", "name": "IMG_001.jpg", "time": "2019-06-20T15:00:10Z", "received": false },
                    { "src": "%s", "name": "IMG_002.jpg", "time": "2019-06-21T09:12:00Z", "received": true }
                  ]
                }
                """.formatted(
                jsonEscaped(dir.resolve("montage-001.jpg")),
                jsonEscaped(dir.resolve("IMG_001.jpg")),
                jsonEscaped(dir.resolve("IMG_002.jpg"))));

        assertThat(reader.readSrcs(sidecar))
                .containsExactly(dir.resolve("IMG_001.jpg"), dir.resolve("IMG_002.jpg"));
    }

    @Test
    void ignoresFieldsItDoesNotConsume(@TempDir Path dir) throws IOException {
        // A field this reader has never heard of must not break the projection either - the
        // sidecar's full shape is the writer's business, including any it grows later.
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                {
                  "montage": "whatever",
                  "some_future_field": 42,
                  "photos": [
                    { "src": "%s", "name": "IMG_001.jpg", "time": "2019-06-20T15:00:10Z",
                      "received": false, "another_new_field": true }
                  ]
                }
                """.formatted(jsonEscaped(dir.resolve("IMG_001.jpg"))));

        assertThat(reader.readSrcs(sidecar)).containsExactly(dir.resolve("IMG_001.jpg"));
    }

    @Test
    void failsLoudWhenTheSidecarFileIsMissing(@TempDir Path dir) {
        assertThatThrownBy(() -> reader.readSrcs(dir.resolve("montage-404.json")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-404.json");
    }

    @Test
    void failsLoudOnMalformedJson(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "{ not json");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-001.json");
    }

    @Test
    void failsLoudOnANullDocument(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "null");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudWhenThePhotosArrayIsMissing(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg" }""");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudOnAnEmptyPhotosArray(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg", "photos": [] }""");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("lists no photos");
    }

    @Test
    void failsLoudOnANullPhotoEntry(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ null ] }""");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("photo entry without a src");
    }

    @Test
    void failsLoudOnAPhotoWithoutASrc(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ { "name": "IMG_001.jpg" } ] }""");

        assertThatThrownBy(() -> reader.readSrcs(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("photo entry without a src");
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
