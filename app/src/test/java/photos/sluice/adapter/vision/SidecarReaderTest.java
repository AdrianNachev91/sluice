package photos.sluice.adapter.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SidecarReaderTest {

    private final SidecarReader reader = new SidecarReader();

    @Test
    void returnsEveryListedPhotoEntryInFull(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
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

        assertThat(this.reader.readEntries(sidecar)).containsExactly(
                new SidecarPhotoEntry(dir.resolve("IMG_001.jpg"), "IMG_001.jpg",
                        Instant.parse("2019-06-20T15:00:10Z"), false),
                new SidecarPhotoEntry(dir.resolve("IMG_002.jpg"), "IMG_002.jpg",
                        Instant.parse("2019-06-21T09:12:00Z"), true));
    }

    @Test
    void ignoresFieldsItDoesNotConsume(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
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

        assertThat(this.reader.readEntries(sidecar)).containsExactly(
                new SidecarPhotoEntry(dir.resolve("IMG_001.jpg"), "IMG_001.jpg",
                        Instant.parse("2019-06-20T15:00:10Z"), false));
    }

    @Test
    void failsLoudWhenTheSidecarFileIsMissing(@TempDir final Path dir) {
        assertThatThrownBy(() -> this.reader.readEntries(dir.resolve("montage-404.json")))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("montage-404.json");
    }

    @Test
    void aReadFailureThrowsPlainUncheckedIOExceptionNotMalformed(@TempDir final Path dir) throws IOException {
        // A directory in place of the sidecar is what makes the open itself fail, rather than the
        // content parse.
        final Path sidecar = dir.resolve("montage-001.json");
        Files.createDirectory(sidecar);

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class);
    }

    @Test
    // any(Class.class) is the only unambiguous matcher for the Class<T>-vs-TypeReference<T>
    // readValue overload. The raw type it forces is a Mockito-generics artifact, not a real cast risk.
    @SuppressWarnings("unchecked")
    void aWrappedReadFailureThrowsPlainUncheckedIOExceptionNotMalformed(@TempDir final Path dir) throws IOException {
        // A stream that opens fine and then fails mid-read has no portable fixture, so the mapper
        // is stubbed to throw the JacksonIOException that shape produces.
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "{}");
        final var wrapped = new IOException("simulated mid-stream read failure");
        final var jacksonIoException = mock(JacksonIOException.class);
        when(jacksonIoException.getCause()).thenReturn(wrapped);
        final var mapper = mock(JsonMapper.class);
        doThrow(jacksonIoException).when(mapper).readValue(any(InputStream.class), any(Class.class));
        final var readerWithFailingMapper = new SidecarReader(mapper);

        assertThatThrownBy(() -> readerWithFailingMapper.readEntries(sidecar))
                .isInstanceOf(UncheckedIOException.class)
                .isNotInstanceOf(MalformedPrepJsonException.class)
                .hasCause(wrapped);
    }

    @Test
    void failsLoudOnMalformedJson(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "{ not json");

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("montage-001.json");
    }

    @Test
    void failsLoudOnANullDocument(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, "null");

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudWhenThePhotosArrayIsMissing(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg" }""");

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("has no photos array");
    }

    @Test
    void failsLoudOnAnEmptyPhotosArray(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "montage": "montage-001.jpg", "photos": [] }""");

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("lists no photos");
    }

    @Test
    void failsLoudOnANullPhotoEntry(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ null ] }""");

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("null photo entry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src", "name", "time", "received"})
    void failsLoudOnAPhotoEntryMissingARequiredField(final String missing, @TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                { "photos": [ %s ] }""".formatted(this.photoWithout(missing, dir)));

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("photo entry missing '" + missing + "'");
    }

    // A NUL character is the one path character Path.of refuses on every platform, so the fixture
    // reaches InvalidPathException wherever the suite runs.
    @Test
    void failsLoudOnASrcThisPlatformCannotMakeAPathOutOf(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                {
                  "photos": [
                    { "src": "bad\\u0000path.jpg", "name": "IMG_001.jpg", "time": "2019-06-20T15:00:00Z",
                      "received": false }
                  ]
                }
                """);

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("unusable src");
    }

    // "/" parses cleanly into a path with no name elements, on every platform the suite runs on.
    // So the fixture needs no escaping to reach one.
    @Test
    void failsLoudOnASrcNamingAFilesystemRoot(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                {
                  "photos": [
                    { "src": "/", "name": "IMG_001.jpg", "time": "2019-06-20T15:00:00Z", "received": false }
                  ]
                }
                """);

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("whole filesystem root");
    }

    @Test
    void failsLoudOnAnUnparseableTime(@TempDir final Path dir) throws IOException {
        final Path sidecar = dir.resolve("montage-001.json");
        Files.writeString(sidecar, """
                {
                  "photos": [
                    { "src": "%s", "name": "IMG_001.jpg", "time": "20-06-2019 15:00", "received": false }
                  ]
                }
                """.formatted(jsonEscaped(dir.resolve("IMG_001.jpg"))));

        assertThatThrownBy(() -> this.reader.readEntries(sidecar))
                .isInstanceOf(MalformedPrepJsonException.class)
                .hasMessageContaining("unparseable time '20-06-2019 15:00'");
    }

    private String photoWithout(final String missing, final Path dir) {
        final var fields = new LinkedHashMap<String, String>();
        fields.put("src", "\"" + jsonEscaped(dir.resolve("IMG_001.jpg")) + "\"");
        fields.put("name", "\"IMG_001.jpg\"");
        fields.put("time", "\"2019-06-20T15:00:10Z\"");
        fields.put("received", "false");
        fields.remove(missing);
        return fields.entrySet().stream()
                .map(field -> "\"" + field.getKey() + "\": " + field.getValue())
                .collect(Collectors.joining(", ", "{ ", " }"));
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
