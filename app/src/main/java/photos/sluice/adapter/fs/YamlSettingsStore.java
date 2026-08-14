package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.MalformedSettingsException;
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
 * interrupted save cannot leave a half-written config behind. The temporary name is unique per
 * call. What keeps two Sluice processes off each other's files is the working-root claim, and this
 * file sits outside it. The config belongs to the user rather than to a working root. So two
 * processes can be saving at once, and one shared name would have them writing over each other.
 *
 * <p>What that closes is a corrupt file, not a lost change. Two saves that overlap still both read,
 * merge and rename, so the one that renames last wins outright and the other's edits are gone with
 * nothing said. Whole and stale beats half-written, which is the whole of the claim here.
 *
 * <p>Nothing here is flushed to the device. What this closes is a process that stops mid-write,
 * not a machine that loses power.
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
     * @throws MalformedSettingsException if the file that is there cannot be understood
     * @throws UncheckedIOException if the file cannot be read or written
     */
    @Override
    public void save(final Settings settings) {
        final Map<String, Object> document = this.read();
        final Map<String, Object> sluice = this.child(document, "sluice");

        final Map<String, Object> paths = this.child(sluice, "paths");
        put(paths, "repo-root", settings.paths().repoRoot());
        put(paths, "library-root", settings.paths().libraryRoot());
        put(paths, "inbox", settings.paths().inbox());

        final Map<String, Object> montage = this.child(sluice, "montage");
        put(montage, "tile-size", settings.montage().tileSize());
        put(montage, "tiles-per-row", settings.montage().tilesPerRow());

        final Map<String, Object> cull = this.child(sluice, "cull");
        put(cull, "provider", settings.provider());
        put(cull, "categories", categories(settings.categories()));

        final CullProviderSettings provider = settings.providerSettings();
        final Map<String, Object> providerSettings = this.child(cull, "provider-settings");
        put(providerSettings, "model", provider.model());
        put(providerSettings, "endpoint", provider.endpoint());
        put(providerSettings, "thinking", provider.thinking());
        put(providerSettings, "max-retries", provider.maxRetries());

        final Map<String, Object> externalAgent = this.child(cull, "external-agent");
        put(externalAgent, "mode", settings.externalAgent().mode().name().toLowerCase(Locale.ROOT));

        this.write(document);
    }

    /**
     * Serializes the document into the given file. Package-private for two reasons. A test can fail
     * the write once the temporary file exists, which on a real filesystem means breaking the
     * volume. And it is the only moment the temporary name is visible, since a save that works
     * renames that file away before returning.
     *
     * @param file {@link Path} the file to write to
     * @param document a {@link Map} of {@link String} to {@link Object}, the document to write
     * @throws IOException when the write fails
     */
    void dump(final Path file, final Map<String, Object> document) throws IOException {
        try (final BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            yaml().dump(document, writer);
        }
    }

    /**
     * Reads the config file into a mutable map, or an empty one when there is no file yet.
     *
     * @return a {@link Map} of {@link String} to {@link Object}, the parsed document
     * @throws MalformedSettingsException if what is there is not valid YAML
     * @throws UncheckedIOException if the file cannot be read
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
            throw new MalformedSettingsException(this.configFile, "The settings file " + this.configFile
                    + " is not valid YAML. Fix or remove it, then try again.", e);
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        return this.mapping(loaded, "The top level of the settings file " + this.configFile);
    }

    /**
     * Writes the document to a temporary file beside the config file, then moves it into place.
     *
     * <p>{@link Path#getParent()} answers null for a bare filename, which is why the absolute form
     * is taken first.
     *
     * <p>Removing the temporary file is attempted however the write went, a finished write whose
     * rename then failed included. Both halves of what it holds outlive the failure. The settings
     * came from the caller, and the file they were merged into is still on disk untouched. So the
     * same save runs again and builds the same document. Keeping it deliberately would leave a file
     * in a folder the user opens by hand, named closely enough to the config to be mistaken for it.
     *
     * <p>A process killed between the two steps leaves one behind that no cleanup could have run
     * for, and unique names mean those accumulate rather than being reused. Sweeping them at the top
     * of a save is what that argues for, and it is refused. A sweep cannot tell a dead process's
     * leftover from a live process's file mid-write. Deleting the second is the corruption the
     * unique name exists to prevent.
     *
     * @param document a {@link Map} of {@link String} to {@link Object}, the document to write
     */
    private void write(final Map<String, Object> document) {
        final Path target = this.configFile.toAbsolutePath();
        final Path directory = target.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, target.getFileName().toString(), TEMP_SUFFIX);
            this.dump(temporary, document);
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write the settings file " + this.configFile, e);
        } finally {
            discard(temporary);
        }
    }

    /**
     * Removes the temporary file a write worked through. A move into place leaves nothing here to
     * remove.
     *
     * <p>A failure to remove it is swallowed. A leftover is not what the caller needs to hear
     * about, whether their save failed or landed.
     *
     * @param temporary {@link Path} the temporary file to remove, or null when none was created
     */
    private static void discard(final @Nullable Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (final IOException ignored) {}
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
     * @throws MalformedSettingsException if what is stored under the key is not a group of settings
     */
    private Map<String, Object> child(final Map<String, Object> parent, final String key) {
        final Object existing = take(parent, key);
        final Map<String, Object> mapping = existing == null
                ? new LinkedHashMap<>()
                : this.mapping(existing, "The entry " + key + " in the settings file " + this.configFile);
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
     * @param subject {@link String} what the node is, as the opening of the failure sentence
     * @return a {@link Map} of {@link String} to {@link Object}, the node as a mapping
     * @throws MalformedSettingsException if the node is anything other than a mapping
     */
    private Map<String, Object> mapping(final Object node, final String subject) {
        if (!(node instanceof final Map<?, ?> map)) {
            throw new MalformedSettingsException(this.configFile,
                    subject + " is not a group of settings. Fix or remove it, then try again.");
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
