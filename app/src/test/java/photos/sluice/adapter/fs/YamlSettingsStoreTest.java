package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import photos.sluice.config.CullConfig;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSettingsStoreTest {

    @Test
    void writesEverySettingItOwns(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");

        new YamlSettingsStore(file).save(settings());

        assertThat(Files.readString(file))
                .contains("repo-root: /photos/work")
                .contains("library-root: /photos/library")
                .contains("inbox: /photos/work/Inbox")
                .contains("tile-size: 96")
                .contains("tiles-per-row: 7")
                .contains("provider: anthropic")
                .contains("model: claude-sonnet-5")
                .contains("max-retries: 4")
                .contains("mode: watch")
                .contains("name: junk");
    }

    @Test
    void writesIntoAConfigDirectoryThatDoesNotExistYet(@TempDir final Path dir) {
        final Path file = dir.resolve("nested").resolve("config.yml");

        new YamlSettingsStore(file).save(settings());

        assertThat(file).isRegularFile();
    }

    @Test
    void leavesTheTemporaryFileNowhereToBeFound(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");

        new YamlSettingsStore(file).save(settings());

        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void everySaveWritesThroughATemporaryNameOfItsOwn(@TempDir final Path dir) {
        final Path file = dir.resolve("config.yml");
        final var document = new RecordingTemporaryDocument(file);
        final var store = new YamlSettingsStore(document);

        store.save(settings());
        store.save(settings());

        assertThat(document.written).hasSize(2).doesNotHaveDuplicates().doesNotContain(file)
                .allSatisfy(temporary -> assertThat(temporary.getParent()).isEqualTo(dir));
    }

    // The write dies once the temporary file exists, which is the mess a volume filling up leaves.
    @Test
    void aFailedWriteLeavesNeitherALeftoverNorAChangedConfigFile(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = "sluice:\n  cull:\n    provider: manual\n";
        Files.writeString(file, original);

        assertThatThrownBy(() -> new YamlSettingsStore(new FailingWriteDocument(file)).save(settings()))
                .isInstanceOf(UncheckedIOException.class);

        assertThat(Files.readString(file)).isEqualTo(original);
        try (final var entries = Files.list(dir)) {
            assertThat(entries).containsExactly(file);
        }
    }

    @Test
    void aSavedFileReadsBackAsTheSameSettings(@TempDir final Path dir) {
        final Path file = dir.resolve("config.yml");
        final var store = new YamlSettingsStore(file);
        store.save(settings());

        store.save(settings());

        assertThat(reloaded(file)).isEqualTo(settings());
    }

    // Every group on the way down is merged into, not replaced, so depth is what this checks. The
    // shallow case alone would pass against a writer that rebuilds the leaf groups from scratch.
    @Test
    void keepsAKeyItKnowsNothingAboutInsideAGroupItRewrites(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  cull:
                    external-agent:
                      poll-interval: 30s
                    provider-settings:
                      organisation: acme
                """);

        new YamlSettingsStore(file).save(settings());

        assertThat(Files.readString(file))
                .contains("poll-interval: 30s")
                .contains("organisation: acme")
                .contains("mode: watch")
                .contains("model: claude-sonnet-5");
    }

    @Test
    void aRefusedSaveLeavesTheConfigFileExactlyAsItWas(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        final String original = """
                sluice:
                  paths:
                 - broken: [
                """;
        Files.writeString(file, original);

        assertThatThrownBy(() -> new YamlSettingsStore(file).save(settings()))
                .isInstanceOf(MalformedSettingsException.class);

        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void keepsAKeyItKnowsNothingAbout(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  imaging:
                    heif-decoder-command: heif-convert
                unrelated:
                  kept: true
                """);

        new YamlSettingsStore(file).save(settings());

        assertThat(Files.readString(file))
                .contains("heif-decoder-command: heif-convert")
                .contains("kept: true");
    }

    @Test
    void replacesAKeyWrittenInAnotherAcceptedSpelling(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  montage:
                    tilesPerRow: 5
                """);

        new YamlSettingsStore(file).save(settings());

        assertThat(Files.readString(file)).doesNotContain("tilesPerRow").contains("tiles-per-row: 7");
    }

    @Test
    void removesTheKeyForAFolderRootThatIsNotConfigured(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        new YamlSettingsStore(file).save(settings());

        new YamlSettingsStore(file).save(SettingsFixture.settings(new PathSettings(null, null, null)));

        assertThat(Files.readString(file)).doesNotContain("repo-root");
    }

    @Test
    void refusesAConfigFileThatIsNotValidYaml(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  paths:\n - broken: [\n");

        assertThatThrownBy(() -> new YamlSettingsStore(file).save(settings()))
                .isInstanceOf(MalformedSettingsException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void refusesAConfigFileWhoseRootIsNotAGroupOfSettings(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "just a string\n");

        assertThatThrownBy(() -> new YamlSettingsStore(file).save(settings()))
                .isInstanceOf(MalformedSettingsException.class)
                .hasMessageContaining("not a group of settings");
    }

    @Test
    void refusesAnEntryTheUserWroteAsSomethingOtherThanAGroup(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice: off\n");

        assertThatThrownBy(() -> new YamlSettingsStore(file).save(settings()))
                .isInstanceOf(MalformedSettingsException.class)
                .hasMessageContaining("sluice");
    }

    // The entry branch rather than the top-level one, because that branch reports a key rather than
    // the file. A store passing the wrong thing there would still look right.
    @Test
    void aRefusalNamesTheConfigFileItWasReading(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice: off\n");

        assertThatThrownBy(() -> new YamlSettingsStore(file).save(settings()))
                .isInstanceOfSatisfying(MalformedSettingsException.class,
                        e -> assertThat(e.settingsFile()).isEqualTo(file));
    }

    @Test
    void treatsAnEmptyConfigFileAsNothingConfigured(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "");

        new YamlSettingsStore(file).save(settings());

        assertThat(reloaded(file)).isEqualTo(settings());
    }

    @Test
    void treatsAnEntryWithNoValueAsAbsent(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, "sluice:\n  paths:\n");

        new YamlSettingsStore(file).save(settings());

        assertThat(reloaded(file).paths()).isEqualTo(settings().paths());
    }

    // Each provider keeps its own block, and a save carries whichever ones the settings hold. A
    // provider this install does not have is one of them, so its block has to come back untouched.
    @Test
    void aBlockBelongingToAnotherProviderSurvivesASave(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                sluice:
                  cull:
                    provider-settings:
                      some-other-provider:
                        model: their-model
                        endpoint: https://theirs.invalid
                """);

        new YamlSettingsStore(file).save(settings());

        final var providerSettings = asMap(asMap(asMap(
                new Yaml().<Map<String, Object>>load(read(file)).get("sluice")).get("cull")).get("provider-settings"));
        assertThat(asMap(providerSettings.get("some-other-provider")))
                .containsEntry("model", "their-model")
                .containsEntry("endpoint", "https://theirs.invalid");
        assertThat(asMap(providerSettings.get("anthropic"))).containsEntry("model", "claude-sonnet-5");
    }

    // The one shape no other test reaches: what this writer produces, read back by the binder that
    // reads it at launch. An install where nobody has configured a provider saves an empty map, and
    // a mapping with nothing under it is not a mapping the binder accepts.
    @Test
    void whatASaveWritesStillBindsWhenNoProviderIsConfigured(@TempDir final Path dir) {
        final Path file = dir.resolve("config.yml");
        final Settings noProviderConfigured = new Settings(settings().paths(), "external-agent", Map.of(),
                settings().categories(), settings().externalAgent(), settings().montage(), settings().theme());

        new YamlSettingsStore(file).save(noProviderConfigured);

        assertThat(boundCull(file).providerSettings()).isEmpty();
    }

    /**
     * Binds a saved file the way the app binds it at launch.
     *
     * @param file {@link Path} the config file to read
     * @return {@link CullConfig} the cull settings it carries
     */
    private static CullConfig boundCull(final Path file) {
        final List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader().load("config.yml", new FileSystemResource(file));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final var propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("sluice.cull", CullConfig.class)
                .orElseThrow(() -> new AssertionError("the saved file carries no sluice.cull block"));
    }

    private static Settings settings() {
        return new Settings(new PathSettings("/photos/work", "/photos/library", "/photos/work/Inbox"),
                "anthropic", Map.of("anthropic",
                        new CullProviderSettings("claude-sonnet-5", "https://example.invalid", 4)),
                List.of(new CullCategory("junk", "objectively worthless shots")),
                new ExternalAgentSettings(WatchMode.WATCH), new MontageConfig(96, 7), ThemeChoice.DARK);
    }

    // Reads a saved file back the way config binding would, so a test compares settings values
    // rather than YAML text.
    private static Settings reloaded(final Path file) {
        final var loaded = new Yaml().<Map<String, Object>>load(read(file));
        final var sluice = asMap(loaded.get("sluice"));
        final var paths = asMap(sluice.get("paths"));
        final var montage = asMap(sluice.get("montage"));
        final var cull = asMap(sluice.get("cull"));
        final var ui = asMap(sluice.get("ui"));
        final var providerSettings = asMap(cull.get("provider-settings"));
        final var anthropic = asMap(providerSettings.get("anthropic"));
        final List<CullCategory> categories = ((List<?>) cull.get("categories")).stream()
                .map(YamlSettingsStoreTest::asMap)
                .map(card -> new CullCategory((String) card.get("name"), (String) card.get("description")))
                .toList();
        return new Settings(
                new PathSettings((String) paths.get("repo-root"), (String) paths.get("library-root"),
                        (String) paths.get("inbox")),
                (String) cull.get("provider"),
                Map.of("anthropic", new CullProviderSettings((String) anthropic.get("model"),
                        (String) anthropic.get("endpoint"), (Integer) anthropic.get("max-retries"))),
                categories,
                new ExternalAgentSettings(WatchMode.valueOf(
                        ((String) asMap(cull.get("external-agent")).get("mode")).toUpperCase(Locale.ROOT))),
                new MontageConfig((Integer) montage.get("tile-size"), (Integer) montage.get("tiles-per-row")),
                ThemeChoice.valueOf(((String) ui.get("theme")).toUpperCase(Locale.ROOT)));
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // SnakeYAML hands every mapping back as a raw Map, so a reader has to state what it expects.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object node) {
        return (Map<String, Object>) node;
    }

    // Names the file each save worked through. A successful save moves that file away, so nothing
    // left on disk afterwards can say which name was used.
    private static final class RecordingTemporaryDocument extends YamlConfigFile {

        private final List<Path> written = new ArrayList<>();

        private RecordingTemporaryDocument(final Path configFile) {
            super(configFile);
        }

        @Override
        void dump(final Path target, final Map<String, Object> document) throws IOException {
            this.written.add(target);
            super.dump(target, document);
        }
    }

}
