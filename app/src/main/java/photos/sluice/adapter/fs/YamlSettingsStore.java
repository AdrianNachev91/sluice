package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.domain.cull.CullCategory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * card, which is written whole, so an extra key on a card does not come back. Comments do not
 * survive either. YAML comments are not part of the parsed document, and nothing can put them back.
 *
 * <p>A key written in another accepted spelling is replaced rather than left beside the canonical
 * one. Config binding treats {@code tilesPerRow}, {@code tiles_per_row} and {@code tiles-per-row}
 * as one key. Leaving the other spelling in place could let it quietly win over the saved value.
 * That does not reach a whole path written as one dotted key at the top of the file, which this
 * writer leaves alone.
 *
 * <p>The write goes to a temporary file in the same folder and is then moved into place, so an
 * interrupted save cannot leave a half-written config behind.
 */
public class YamlSettingsStore implements SettingsStore {

    private static final String TEMP_SUFFIX = ".tmp";

    private final Path configFile;

    /**
     * Creates a store over the given config file, which need not exist yet.
     *
     * @param configFile {@link Path} the user's YAML config file
     */
    public YamlSettingsStore(final Path configFile) {
        this.configFile = configFile;
    }

    /**
     * Writes the given settings into the config file.
     *
     * @param settings {@link Settings} the settings to persist
     */
    @Override
    public void save(final Settings settings) {
        final Map<String, Object> document = this.read();
        final Map<String, Object> sluice = child(document, "sluice");

        final Map<String, Object> paths = child(sluice, "paths");
        put(paths, "repo-root", settings.paths().repoRoot());
        put(paths, "library-root", settings.paths().libraryRoot());
        put(paths, "inbox", settings.paths().inbox());

        final Map<String, Object> montage = child(sluice, "montage");
        put(montage, "tile-size", settings.montage().tileSize());
        put(montage, "tiles-per-row", settings.montage().tilesPerRow());

        final Map<String, Object> cull = child(sluice, "cull");
        put(cull, "provider", settings.provider());
        put(cull, "categories", categories(settings.categories()));

        final CullProviderSettings provider = settings.providerSettings();
        final Map<String, Object> providerSettings = child(cull, "provider-settings");
        put(providerSettings, "model", provider.model());
        put(providerSettings, "endpoint", provider.endpoint());
        put(providerSettings, "thinking", provider.thinking());
        put(providerSettings, "max-retries", provider.maxRetries());

        final Map<String, Object> externalAgent = child(cull, "external-agent");
        put(externalAgent, "mode", settings.externalAgent().mode().name().toLowerCase(Locale.ROOT));

        this.write(document);
    }

    /**
     * Reads the config file into a mutable map, or an empty one when there is no file yet.
     *
     * @return a {@link Map} of {@link String} to {@link Object}, the parsed document
     */
    private Map<String, Object> read() {
        if (!Files.isRegularFile(this.configFile)) {
            return new LinkedHashMap<>();
        }
        final Object loaded;
        try (final Reader reader = Files.newBufferedReader(this.configFile, StandardCharsets.UTF_8)) {
            loaded = new Yaml().load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read the settings file " + this.configFile, e);
        } catch (final RuntimeException e) {
            throw new IllegalStateException("The settings file " + this.configFile
                    + " is not valid YAML. Fix or remove it, then try again.", e);
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        return mapping(loaded, this.configFile.toString());
    }

    /**
     * Writes the document to a temporary file beside the config file, then moves it into place.
     *
     * @param document a {@link Map} of {@link String} to {@link Object}, the document to write
     */
    private void write(final Map<String, Object> document) {
        final Path temporary = this.configFile.resolveSibling(this.configFile.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(this.configFile.getParent());
            try (final BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                yaml().dump(document, writer);
            }
            Files.move(temporary, this.configFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write the settings file " + this.configFile, e);
        }
    }

    /**
     * A YAML writer in block style, so the file stays as readable by hand as the one it replaces.
     *
     * @return {@link Yaml} the configured writer
     */
    private static Yaml yaml() {
        final var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options);
    }

    /**
     * The child mapping under the given key, creating it when absent and reusing whatever is
     * already there when present. Reuse is what lets a key Sluice does not own survive a save.
     *
     * @param parent a {@link Map} of {@link String} to {@link Object}, the mapping to look in
     * @param key {@link String} the child's canonical key
     * @return a {@link Map} of {@link String} to {@link Object}, the child mapping
     */
    private static Map<String, Object> child(final Map<String, Object> parent, final String key) {
        final Object existing = take(parent, key);
        final Map<String, Object> mapping = existing == null
                ? new LinkedHashMap<>()
                : mapping(existing, key);
        parent.put(key, mapping);
        return mapping;
    }

    /**
     * Sets a key, dropping any equivalent spelling of it that was already there. A null value
     * removes the key instead, which is how an unconfigured folder root is stored.
     *
     * @param mapping a {@link Map} of {@link String} to {@link Object}, the mapping to set the key on
     * @param key {@link String} the canonical key
     * @param value {@link Object} the value to store, or null to remove the key
     */
    private static void put(final Map<String, Object> mapping, final String key, final @Nullable Object value) {
        take(mapping, key);
        if (value != null) {
            mapping.put(key, value);
        }
    }

    /**
     * Removes the canonical key and every equivalent spelling of it, returning whichever value was
     * found.
     *
     * @param mapping a {@link Map} of {@link String} to {@link Object}, the mapping to remove from
     * @param key {@link String} the canonical key
     * @return {@link Object} the value that was stored, or null when there was none
     */
    private static @Nullable Object take(final Map<String, Object> mapping, final String key) {
        final String canonical = relaxed(key);
        Object found = null;
        final var stored = List.copyOf(mapping.keySet());
        for (final String candidate : stored) {
            if (relaxed(candidate).equals(canonical)) {
                found = mapping.remove(candidate);
            }
        }
        return found;
    }

    /**
     * Reduces a key to the form config binding compares by, so that separators and letter case stop
     * telling two spellings of one key apart.
     *
     * @param key {@link String} the key as written
     * @return {@link String} the comparable form
     */
    private static String relaxed(final String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * Reads a loaded node as a mapping, refusing anything else. Overwriting a node the user wrote
     * as something other than a mapping would throw their file away without saying so.
     *
     * @param node {@link Object} the loaded node
     * @param name {@link String} what the node is, for the failure message
     * @return a {@link Map} of {@link String} to {@link Object}, the node as a mapping
     */
    private static Map<String, Object> mapping(final Object node, final String name) {
        if (!(node instanceof final Map<?, ?> map)) {
            throw new IllegalStateException("The settings file entry " + name
                    + " is not a group of settings. Fix or remove it, then try again.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
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
