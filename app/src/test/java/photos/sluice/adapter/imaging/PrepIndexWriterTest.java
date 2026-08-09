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
    void writesIndexJsonMatchingTheExactContractShape(@TempDir final Path dir) throws IOException {
        final Path indexPath = dir.resolve("index.json");
        final Path basePath = dir.resolve("Sorted");
        final Path prepDir = dir.resolve("prep");
        final Path corrupt = dir.resolve("corrupt.cr2");
        final var prep = new PrepDir("2023", List.of("junk", "scenery"), basePath, 42, List.of(corrupt), 2, prepDir,
                List.of("montage-001", "montage-002"));

        this.writer.write(indexPath, prep);

        final String json = Files.readString(indexPath, StandardCharsets.UTF_8);
        assertThat(json).isEqualToIgnoringWhitespace("""
                {
                  "scope": "2023",
                  "categories": ["junk", "scenery"],
                  "basePath": "%s",
                  "photos": 42,
                  "unreviewable": ["%s"],
                  "montages": 2,
                  "prepDir": "%s",
                  "entries": ["montage-001", "montage-002"]
                }
                """.formatted(jsonEscaped(basePath), jsonEscaped(corrupt), jsonEscaped(prepDir)));
    }

    @Test
    void wrapsAWriteFailureIntoUncheckedIOException(@TempDir final Path dir) {
        final Path indexPath = dir.resolve("missing-parent").resolve("index.json");
        final var prep = new PrepDir("2023", List.of("junk"), dir, 0, List.of(), 0, dir, List.of());

        assertThatThrownBy(() -> this.writer.write(indexPath, prep))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(indexPath.toString());
    }

    @Test
    void wrapsAJacksonExceptionDuringTheWriteItselfIntoUncheckedIOException(@TempDir final Path dir) {
        final var mapper = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(mapper).writeValue(any(java.io.OutputStream.class), any());
        final var writerWithFailingMapper = new PrepIndexWriter(mapper);
        final Path indexPath = dir.resolve("index.json");
        final var prep = new PrepDir("2023", List.of("junk"), dir, 0, List.of(), 0, dir, List.of());

        assertThatThrownBy(() -> writerWithFailingMapper.write(indexPath, prep))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(indexPath.toString())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasCauseInstanceOf(JacksonException.class);
    }

    private static String jsonEscaped(final Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
