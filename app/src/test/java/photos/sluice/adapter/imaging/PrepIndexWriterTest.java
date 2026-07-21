package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.cull.PrepDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class PrepIndexWriterTest {

    private final PrepIndexWriter writer = new PrepIndexWriter();

    @Test
    void writesIndexJsonMatchingTheExactContractShape(@TempDir Path dir) throws IOException {
        Path indexPath = dir.resolve("index.json");
        Path basePath = dir.resolve("Sorted");
        Path prepDir = dir.resolve("prep");
        var prep = new PrepDir("2023", basePath, 42, 2, prepDir, List.of("montage-001", "montage-002"));

        writer.write(indexPath, prep);

        String json = Files.readString(indexPath, StandardCharsets.UTF_8);
        assertThat(json).isEqualToIgnoringWhitespace("""
                {
                  "scope": "2023",
                  "basePath": "%s",
                  "photos": 42,
                  "montages": 2,
                  "prepDir": "%s",
                  "entries": ["montage-001", "montage-002"]
                }
                """.formatted(jsonEscaped(basePath), jsonEscaped(prepDir)));
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir Path dir) {
        Path indexPath = dir.resolve("missing-parent").resolve("index.json");
        var prep = new PrepDir("2023", dir, 0, 0, dir, List.of());

        assertThatThrownBy(() -> writer.write(indexPath, prep))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(indexPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringTheWriteItselfIntoUncheckedIOException(@TempDir Path dir) {
        var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(java.io.OutputStream.class), any());
        var writerWithFailingMapper = new PrepIndexWriter(mapper);
        Path indexPath = dir.resolve("index.json");
        var prep = new PrepDir("2023", dir, 0, 0, dir, List.of());

        assertThatThrownBy(() -> writerWithFailingMapper.write(indexPath, prep))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(indexPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
