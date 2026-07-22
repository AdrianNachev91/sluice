package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.domain.cull.SidecarPhotoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidecarReaderTest {

    private final SidecarReader reader = new SidecarReader();

    @Test
    void returnsEveryListedPhotoEntryInFull(@TempDir Path dir) throws IOException {
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

        assertThat(reader.readEntries(sidecar)).containsExactly(
                new SidecarPhotoEntry(dir.resolve("IMG_001.jpg"), "IMG_001.jpg",
                        Instant.parse("2019-06-20T15:00:10Z"), false),
                new SidecarPhotoEntry(dir.resolve("IMG_002.jpg"), "IMG_002.jpg",
                        Instant.parse("2019-06-21T09:12:00Z"), true));
    }

    @Test
    void ignoresFieldsItDoesNotConsume(@TempDir Path dir) throws IOException {
        // A field this reader has never heard of must not break the read either - the sidecar's
        // full shape is the writer's business, including any it grows later.
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

        assertThat(reader.readEntries(sidecar)).containsExactly(
                new SidecarPhotoEntry(dir.resolve("IMG_001.jpg"), "IMG_001.jpg",
                        Instant.parse("2019-06-20T15:00:10Z"), false));
    }

    @Test
    void failsLoudWhenTheSidecarFileIsMissing(@TempDir Path dir) {
        assertThatThrownBy(() -> reader.readEntries(dir.resolve("montage-404.json")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-404.json");
    }

    @Test
    void failsLoudOnMalformedJson(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "{ not json");

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("montage-001.json");
    }

    @Test
    void failsLoudOnANullDocument(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "null");

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudWhenThePhotosArrayIsMissing(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg" }""");

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudOnAnEmptyPhotosArray(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg", "photos": [] }""");

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("lists no photos");
    }

    @Test
    void failsLoudOnANullPhotoEntry(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ null ] }""");

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("null photo entry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src", "name", "time", "received"})
    void failsLoudOnAPhotoEntryMissingARequiredField(String missing, @TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ %s ] }""".formatted(photoWithout(missing, dir)));

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("photo entry missing '" + missing + "'");
    }

    @Test
    void failsLoudOnAnUnparseableTime(@TempDir Path dir) throws IOException {
        Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ { "src": "%s", "name": "IMG_001.jpg", "time": "20-06-2019 15:00",
                  "received": false } ] }""".formatted(jsonEscaped(dir.resolve("IMG_001.jpg"))));

        assertThatThrownBy(() -> reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("unparseable time '20-06-2019 15:00'");
    }

    // One complete photo entry as raw JSON, with the named field left out.
    private String photoWithout(String missing, Path dir) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("src", "\"" + jsonEscaped(dir.resolve("IMG_001.jpg")) + "\"");
        fields.put("name", "\"IMG_001.jpg\"");
        fields.put("time", "\"2019-06-20T15:00:10Z\"");
        fields.put("received", "false");
        fields.remove(missing);
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\": " + field.getValue())
                .collect(Collectors.joining(", ", "{ ", " }"));
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
