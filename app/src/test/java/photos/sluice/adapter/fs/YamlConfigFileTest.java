package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.MalformedSettingsException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigFileTest {

    @Test
    void readsAFileThatIsNotThereAsNothingConfigured(@TempDir final Path dir) {
        assertThat(new YamlConfigFile(dir.resolve("config.yml")).read()).isEmpty();
    }

    @Test
    void readsAnEmptyFileAsNothingConfigured(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "");

        assertThat(new YamlConfigFile(file).read()).isEmpty();
    }

    @Test
    void readsWhatIsInTheFile(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  cull:\n    provider: manual\n");

        assertThat(new YamlConfigFile(file).read()).containsOnlyKeys("sluice");
    }

    @Test
    void refusesAFileThatIsNotValidYaml(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  paths:\n - broken: [\n");

        assertThatThrownBy(() -> new YamlConfigFile(file).read())
                .isInstanceOf(MalformedSettingsException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void refusesAFileWhoseTopLevelIsNotAGroupOfSettings(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "just a string\n");

        assertThatThrownBy(() -> new YamlConfigFile(file).read())
                .isInstanceOfSatisfying(MalformedSettingsException.class,
                        e -> assertThat(e.settingsFile()).isEqualTo(file));
    }

    @Test
    void writesTheDocumentWhereItSaysItWill(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("nested").resolve("config.yml");
        final var document = new YamlConfigFile(file);

        document.write(new LinkedHashMap<>(Map.of("sluice", Map.of("cull", Map.of("provider", "manual")))));

        assertThat(Files.readString(file)).contains("provider: manual");
        assertThat(new YamlConfigFile(file).read()).containsOnlyKeys("sluice");
    }

    @Test
    void leavesNoTemporaryFileBehind(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");

        new YamlConfigFile(file).write(new LinkedHashMap<>(Map.of("sluice", Map.of())));

        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void aFailedWriteLeavesTheFileAsItWas(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = "sluice:\n  cull:\n    provider: manual\n";
        Files.writeString(file, original);

        assertThatThrownBy(() -> new FailingWriteDocument(file).write(new LinkedHashMap<>()))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readString(file)).isEqualTo(original);
        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void aGroupThatIsNotThereIsCreatedEmpty(@TempDir final Path dir) {
        final Map<String, Object> root = new LinkedHashMap<>();

        final Map<String, Object> group = new YamlConfigFile(dir.resolve("config.yml")).group(root, "sluice");

        assertThat(group).isEmpty();
        assertThat(root).containsOnlyKeys("sluice");
    }

    @Test
    void aGroupThatIsThereKeepsWhatItHeld(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  unknown-to-this-app: kept\n");
        final var document = new YamlConfigFile(file);
        final Map<String, Object> root = document.read();

        final Map<String, Object> group = document.group(root, "sluice");

        assertThat(group).containsEntry("unknown-to-this-app", "kept");
    }

    @Test
    void aGroupTheUserWroteAsSomethingElseIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice: off\n");
        final var document = new YamlConfigFile(file);
        final Map<String, Object> root = document.read();

        assertThatThrownBy(() -> document.group(root, "sluice"))
                .isInstanceOf(MalformedSettingsException.class)
                .hasMessageContaining("sluice");
    }

    // Config binding reads these three as one key. A caller that dropped one and left another would
    // leave a file where the value it did not choose can win.
    @Test
    void removingAKeyTakesEverySpellingOfIt() {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("tilesPerRow", 5);
        mapping.put("tiles_per_row", 6);
        mapping.put("tiles-per-row", 7);

        final Object removed = YamlConfigFile.remove(mapping, "tiles-per-row");

        assertThat(mapping).isEmpty();
        assertThat(removed).isNotNull();
    }

    @Test
    void removingAKeyThatIsNotThereAnswersNothingAndChangesNothing() {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("tile-size", 96);

        assertThat(YamlConfigFile.remove(mapping, "tiles-per-row")).isNull();
        assertThat(mapping).containsExactly(Map.entry("tile-size", 96));
    }

    @Test
    void settingAKeyReplacesTheSpellingThatWasThere() {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("tilesPerRow", 5);

        YamlConfigFile.set(mapping, "tiles-per-row", 7);

        assertThat(mapping).containsExactly(Map.entry("tiles-per-row", 7));
    }

    @Test
    void settingAKeyToNothingRemovesIt() {
        final Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("repo-root", "/photos/work");

        YamlConfigFile.set(mapping, "repo-root", null);

        assertThat(mapping).isEmpty();
    }

    @Test
    void namesTheFileItReadsAndWrites(@TempDir final Path dir) {
        final Path file = dir.resolve("config.yml");

        assertThat(new YamlConfigFile(file).path()).isEqualTo(file);
    }
}
