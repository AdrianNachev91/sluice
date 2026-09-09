package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AtomicJsonWriteTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private record Doc(String name, int count) {
    }

    @Test
    void publishesTheDocumentUnderTheDestinationName(@TempDir final Path dir) throws IOException {
        final Path target = dir.resolve("index.json");

        AtomicJsonWrite.write(target, this.mapper, new Doc("first", 1));

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .isEqualToIgnoringWhitespace("{\"name\":\"first\",\"count\":1}");
        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(target);
        }
    }

    @Test
    void replacesWhateverTheDestinationHeldBefore(@TempDir final Path dir) throws IOException {
        final Path target = dir.resolve("index.json");
        Files.writeString(target, "stale content that must not survive");

        AtomicJsonWrite.write(target, this.mapper, new Doc("second", 2));

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .isEqualToIgnoringWhitespace("{\"name\":\"second\",\"count\":2}")
                .doesNotContain("stale");
    }

    @Test
    void aFailedWriteLeavesTheDestinationAndTheDirectoryUntouched(@TempDir final Path dir) throws IOException {
        final Path target = dir.resolve("index.json");
        AtomicJsonWrite.write(target, this.mapper, new Doc("kept", 1));
        final String before = Files.readString(target, StandardCharsets.UTF_8);
        final var failing = mock(JsonMapper.class);
        doThrow(mock(JacksonException.class)).when(failing).writeValue(any(OutputStream.class), any());

        assertThatThrownBy(() -> AtomicJsonWrite.write(target, failing, new Doc("lost", 2)))
                .isInstanceOf(JacksonException.class);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(before);
        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(target);
        }
    }

    // The failure is induced through Files.move's own contract, which specifies
    // DirectoryNotEmptyException for a non-empty directory at the destination on every platform.
    @Test
    void aFailedRenameKeepsTheCompleteDocumentInATemporaryFile(@TempDir final Path dir) throws IOException {
        final Path target = dir.resolve("index.json");
        Files.createDirectory(target);
        Files.writeString(target.resolve("occupant.txt"), "blocks the rename");

        assertThatThrownBy(() -> AtomicJsonWrite.write(target, this.mapper, new Doc("paid for", 3)))
                .isInstanceOf(IOException.class);

        try (final var entries = Files.list(dir)) {
            final List<Path> survivors = entries.filter(Files::isRegularFile).toList();
            assertThat(survivors).singleElement().satisfies(kept ->
                    assertThat(Files.readString(kept, StandardCharsets.UTF_8)).contains("paid for"));
        }
    }

    @Test
    void aDestinationWhoseDirectoryDoesNotExistFailsAsIo(@TempDir final Path dir) {
        final Path target = dir.resolve("missing-parent").resolve("index.json");

        assertThatThrownBy(() -> AtomicJsonWrite.write(target, this.mapper, new Doc("nowhere", 4)))
                .isInstanceOf(IOException.class);
    }
}
