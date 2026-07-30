package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SidecarWriterTest {

    private final SidecarWriter writer = new SidecarWriter();

    @Test
    void writesASinglePhotoSidecarMatchingTheExactContractShape(@TempDir final Path dir) throws IOException {
        final Path sidecarPath = dir.resolve("montage-001.json");
        final Path montagePath = dir.resolve("montage-001.jpg");
        final var photo = new SidecarPhotoEntry(
                dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-06-15T10:30:00Z"), false);

        this.writer.write(sidecarPath, montagePath, List.of(photo));

        final String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
        assertThat(json).isEqualToIgnoringWhitespace("""
                {
                  "montage": "%s",
                  "photos": [
                    {
                      "src": "%s",
                      "name": "a.jpg",
                      "time": "2023-06-15T10:30:00Z",
                      "received": false
                    }
                  ]
                }
                """.formatted(jsonEscaped(montagePath), jsonEscaped(dir.resolve("a.jpg"))));
    }

    @Test
    void preservesPhotoOrderAcrossMultipleEntries(@TempDir final Path dir) throws IOException {
        final Path sidecarPath = dir.resolve("montage-002.json");
        final Path montagePath = dir.resolve("montage-002.jpg");
        final var first = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);
        final var second = new SidecarPhotoEntry(dir.resolve("b.jpg"), "b.jpg", Instant.parse("2023-01-02T00:00:00Z"), false);

        this.writer.write(sidecarPath, montagePath, List.of(first, second));

        final String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
        final int indexA = json.indexOf("a.jpg");
        final int indexB = json.indexOf("b.jpg");
        assertThat(indexA).isLessThan(indexB);
    }

    @Test
    void receivedFlagPassesThroughUntouched(@TempDir final Path dir) throws IOException {
        final Path sidecarPath = dir.resolve("montage-003.json");
        final Path montagePath = dir.resolve("montage-003.jpg");
        final var photo = new SidecarPhotoEntry(
                dir.resolve("IMG-20230615-WA0001.jpg"), "IMG-20230615-WA0001.jpg",
                Instant.parse("2023-06-15T10:30:00Z"), true);

        this.writer.write(sidecarPath, montagePath, List.of(photo));

        final String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
        assertThat(json).contains("\"received\":true");
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
        final Path sidecarPath = dir.resolve("missing-parent").resolve("montage-004.json");
        final Path montagePath = dir.resolve("montage-004.jpg");
        final var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);

        assertThatThrownBy(() -> this.writer.write(sidecarPath, montagePath, List.of(photo)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(sidecarPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringTheWriteItselfIntoUncheckedIOException(@TempDir final Path dir) {
        final var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(java.io.OutputStream.class), any());
        final var writerWithFailingMapper = new SidecarWriter(mapper);
        final Path sidecarPath = dir.resolve("montage-005.json");
        final Path montagePath = dir.resolve("montage-005.jpg");
        final var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);

        assertThatThrownBy(() -> writerWithFailingMapper.write(sidecarPath, montagePath, List.of(photo)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(sidecarPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
