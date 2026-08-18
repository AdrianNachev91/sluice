package photos.sluice.adapter.fs;

import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.MalformedSettingsException;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.domain.cull.CullCategory;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saves settings into the user's YAML config file.
 *
 * <p>The file is read into a map, the keys Sluice owns are set on it, and the whole map is written
 * back. So a key this app knows nothing about survives a save untouched, however deeply nested:
 * every group on the way down is merged into rather than replaced. The one exception is a category
 * card, which is written whole, so an extra key on a card does not come back.
 *
 * <p>A key written in another accepted spelling is replaced rather than left beside the canonical
 * one. That does not reach a whole path written as one dotted key at the top of the file, which
 * this writer leaves alone.
 */
public class YamlSettingsStore implements SettingsStore {

    private final YamlConfigFile document;

    /**
     * Creates a store over the given config file, which need not exist yet.
     *
     * @param configFile {@link Path} the user's YAML config file
     */
    public YamlSettingsStore(final Path configFile) {
        this(new YamlConfigFile(configFile));
    }

    /**
     * Creates a store over the given document. Package-private so a test can supply a document
     * whose write fails, which on a real filesystem would mean breaking the volume.
     *
     * @param document {@link YamlConfigFile} the config file to read and write through
     */
    YamlSettingsStore(final YamlConfigFile document) {
        this.document = document;
    }

    /**
     * Writes the given settings into the config file.
     *
     * @param settings {@link Settings} the settings to persist
     * @throws MalformedSettingsException if the file that is there cannot be understood
     * @throws UncheckedIOException if the file cannot be read or written
     */
    @Override
    public void save(final Settings settings) {
        final Map<String, Object> root = this.document.read();
        final Map<String, Object> sluice = this.document.group(root, "sluice");

        final Map<String, Object> paths = this.document.group(sluice, "paths");
        YamlConfigFile.set(paths, "repo-root", settings.paths().repoRoot());
        YamlConfigFile.set(paths, "library-root", settings.paths().libraryRoot());
        YamlConfigFile.set(paths, "inbox", settings.paths().inbox());

        final Map<String, Object> montage = this.document.group(sluice, "montage");
        YamlConfigFile.set(montage, "tile-size", settings.montage().tileSize());
        YamlConfigFile.set(montage, "tiles-per-row", settings.montage().tilesPerRow());

        final Map<String, Object> cull = this.document.group(sluice, "cull");
        YamlConfigFile.set(cull, "provider", settings.provider());
        YamlConfigFile.set(cull, "categories", categories(settings.categories()));

        final CullProviderSettings provider = settings.providerSettings();
        final Map<String, Object> providerSettings = this.document.group(cull, "provider-settings");
        YamlConfigFile.set(providerSettings, "model", provider.model());
        YamlConfigFile.set(providerSettings, "endpoint", provider.endpoint());
        YamlConfigFile.set(providerSettings, "thinking", provider.thinking());
        YamlConfigFile.set(providerSettings, "max-retries", provider.maxRetries());

        final Map<String, Object> externalAgent = this.document.group(cull, "external-agent");
        YamlConfigFile.set(externalAgent, "mode", settings.externalAgent().mode().name().toLowerCase(Locale.ROOT));

        final Map<String, Object> ui = this.document.group(sluice, "ui");
        YamlConfigFile.set(ui, "theme", settings.theme().name().toLowerCase(Locale.ROOT));

        this.document.write(root);
    }

    /**
     * The category cards as a list of mappings, in the order they are configured.
     *
     * @param categories a {@link List} of {@link CullCategory}, the cards to write
     * @return a {@link List} of {@link Map} of {@link String} to {@link Object}, the mappings to store
     */
    private static List<Map<String, Object>> categories(final List<CullCategory> categories) {
        final List<Map<String, Object>> cards = new ArrayList<>();
        for (final CullCategory category : categories) {
            final Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", category.name());
            card.put("description", category.description());
            cards.add(card);
        }
        return cards;
    }
}
