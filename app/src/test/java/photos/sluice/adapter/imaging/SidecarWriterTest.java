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
    void writesASinglePhotoSidecarMatchingTheExactContractShape(@TempDir Path dir) throws IOException {
        Path sidecarPath = dir.resolve("montage-001.json");
        Path montagePath = dir.resolve("montage-001.jpg");
        var photo = new SidecarPhotoEntry(
                dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-06-15T10:30:00Z"), false);

        writer.write(sidecarPath, montagePath, List.of(photo));

        String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
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
    void preservesPhotoOrderAcrossMultipleEntries(@TempDir Path dir) throws IOException {
        Path sidecarPath = dir.resolve("montage-002.json");
        Path montagePath = dir.resolve("montage-002.jpg");
        var first = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);
        var second = new SidecarPhotoEntry(dir.resolve("b.jpg"), "b.jpg", Instant.parse("2023-01-02T00:00:00Z"), false);

        writer.write(sidecarPath, montagePath, List.of(first, second));

        String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
        int indexA = json.indexOf("a.jpg");
        int indexB = json.indexOf("b.jpg");
        assertThat(indexA).isLessThan(indexB);
    }

    @Test
    void receivedFlagPassesThroughUntouched(@TempDir Path dir) throws IOException {
        Path sidecarPath = dir.resolve("montage-003.json");
        Path montagePath = dir.resolve("montage-003.jpg");
        var photo = new SidecarPhotoEntry(
                dir.resolve("IMG-20230615-WA0001.jpg"), "IMG-20230615-WA0001.jpg",
                Instant.parse("2023-06-15T10:30:00Z"), true);

        writer.write(sidecarPath, montagePath, List.of(photo));

        String json = Files.readString(sidecarPath, StandardCharsets.UTF_8);
        assertThat(json).contains("\"received\":true");
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir Path dir) {
        Path sidecarPath = dir.resolve("missing-parent").resolve("montage-004.json");
        Path montagePath = dir.resolve("montage-004.jpg");
        var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);

        assertThatThrownBy(() -> writer.write(sidecarPath, montagePath, List.of(photo)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(sidecarPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringTheWriteItselfIntoUncheckedIOException(@TempDir Path dir) {
        var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(java.io.OutputStream.class), any());
        var writerWithFailingMapper = new SidecarWriter(mapper);
        Path sidecarPath = dir.resolve("montage-005.json");
        Path montagePath = dir.resolve("montage-005.jpg");
        var photo = new SidecarPhotoEntry(dir.resolve("a.jpg"), "a.jpg", Instant.parse("2023-01-01T00:00:00Z"), false);

        assertThatThrownBy(() -> writerWithFailingMapper.write(sidecarPath, montagePath, List.of(photo)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(sidecarPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
