package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import photos.sluice.application.port.out.MalformedSettingsException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user's YAML config file as a document: reading it, writing it back whole, and the rule config
 * binding compares keys by. The settings writer and the repair both work through this, so neither
 * can disagree with the other about any of the three.
 *
 * <p>The write goes to a temporary file in the same folder and is then moved into place, so an
 * interrupted write cannot leave a half-written config behind. The temporary name is unique per
 * call. What keeps two Sluice processes off each other's files is the working-root claim, and this
 * file sits outside it. The config belongs to the user rather than to a working root. So two
 * processes can be writing at once, and one shared name would have them writing over each other.
 *
 * <p>What that closes is a corrupt file, not a lost change. Two writes that overlap still both
 * read, change and rename, so the one that renames last wins outright and the other's edits are
 * gone with nothing said. Whole and stale beats half-written, which is the whole of the claim here.
 *
 * <p>Nothing here is flushed to the device. What this closes is a process that stops mid-write, not
 * a machine that loses power.
 *
 * <p>On Linux and macOS the config file ends up with the temporary file's permissions rather than
 * the umask's. {@link Files#createTempFile} restricts a new file to its owner, and the rename
 * carries that mode onto the config, so a file that read 644 reads 600 after the first write here.
 */
class YamlConfigFile {

    private static final String TEMP_SUFFIX = ".tmp";

    private final Path file;

    /**
     * Creates a document over the given file, which need not exist yet.
     *
     * @param file {@link Path} the user's YAML config file
     */
    YamlConfigFile(final Path file) {
        this.file = file;
    }

    /**
     * The file this reads and writes.
     *
     * @return {@link Path} the config file
     */
    Path path() {
        return this.file;
    }

    /**
     * Reads the file into a mutable map, or an empty one when there is no file yet.
     *
     * @return a {@link Map} of {@link String} to {@link Object}, the parsed document
     * @throws MalformedSettingsException if what is there is not valid YAML
     * @throws UncheckedIOException if the file cannot be read
     */
    Map<String, Object> read() {
        if (!Files.isRegularFile(this.file)) {
            return new LinkedHashMap<>();
        }
        final Object loaded;
        try (final Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            loaded = new Yaml().load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read the settings file " + this.file, e);
        } catch (final RuntimeException e) {
            throw new MalformedSettingsException(this.file, "The settings file " + this.file
                    + " is not valid YAML. Fix or remove it, then try again.", e);
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        return this.mapping(loaded, "The top level of the settings file " + this.file);
    }

    /**
     * Writes the document to a temporary file beside the config file, then moves it into place.
     *
     * <p>{@link Path#getParent()} answers null for a bare filename, which is why the absolute form
     * is taken first.
     *
     * <p>Removing the temporary file is attempted however the write went, a finished write whose
     * rename then failed included. Nothing is lost with it: the document came from the caller and
     * the file it was built from is untouched, so the same write runs again and builds the same
     * document. Keeping it deliberately would leave a file in a folder the user opens by hand,
     * named closely enough to the config to be mistaken for it.
     *
     * <p>A process killed between the two steps leaves one behind that no cleanup could have run
     * for, and unique names mean those accumulate rather than being reused. Sweeping them at the
     * top of a write is what that argues for, and it is refused. A sweep cannot tell a dead
     * process's leftover from a live process's file mid-write. Deleting the second is the
     * corruption the unique name exists to prevent.
     *
     * @param document a {@link Map} of {@link String} to {@link Object}, the document to write
     * @throws UncheckedIOException if the file cannot be written
     */
    void write(final Map<String, Object> document) {
        final Path target = this.file.toAbsolutePath();
        final Path directory = target.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, target.getFileName().toString(), TEMP_SUFFIX);
            this.dump(temporary, document);
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write the settings file " + this.file, e);
        } finally {
            discard(temporary);
        }
    }

    /**
     * Serializes the document into the given file. Package-private for two reasons. A test can fail
     * the write once the temporary file exists, which on a real filesystem means breaking the
     * volume. And it is the only moment the temporary name is visible, since a write that works
     * renames that file away before returning.
     *
     * @param target {@link Path} the file to write to
     * @param document a {@link Map} of {@link String} to {@link Object}, the document to write
     * @throws IOException when the write fails
     */
    void dump(final Path target, final Map<String, Object> document) throws IOException {
        try (final BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            yaml().dump(document, writer);
        }
    }

    /**
     * The child mapping under the given key, creating it when absent and reusing whatever is
     * already there when present. Reuse is what lets a key Sluice does not own survive a write.
     *
     * @param parent a {@link Map} of {@link String} to {@link Object}, the mapping to look in
     * @param key {@link String} the child's canonical key
     * @return a {@link Map} of {@link String} to {@link Object}, the child mapping
     * @throws MalformedSettingsException if what is stored under the key is not a group of settings
     */
    Map<String, Object> group(final Map<String, Object> parent, final String key) {
        final Object existing = remove(parent, key);
        final Map<String, Object> mapping = existing == null
                ? new LinkedHashMap<>()
                : this.mapping(existing, "The entry " + key + " in the settings file " + this.file);
        parent.put(key, mapping);
        return mapping;
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
    Map<String, Object> mapping(final Object node, final String subject) {
        if (!(node instanceof final Map<?, ?> map)) {
            throw new MalformedSettingsException(this.file,
                    subject + " is not a group of settings. Fix or remove it, then try again.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * Sets a key, dropping any equivalent spelling of it that was already there. A null value
     * removes the key instead, which is how an unconfigured folder root is stored.
     *
     * @param mapping a {@link Map} of {@link String} to {@link Object}, the mapping to set the key on
     * @param key {@link String} the canonical key
     * @param value {@link Object} the value to store, or null to remove the key
     */
    static void set(final Map<String, Object> mapping, final String key, final @Nullable Object value) {
        remove(mapping, key);
        if (value != null) {
            mapping.put(key, value);
        }
    }

    /**
     * Removes the canonical key and every equivalent spelling of it, returning whichever value was
     * found.
     *
     * <p>Every spelling goes, rather than the first match. Config binding treats
     * {@code tilesPerRow}, {@code tiles_per_row} and {@code tiles-per-row} as one key. A spelling
     * left behind could quietly win over what the caller meant to happen.
     *
     * @param mapping a {@link Map} of {@link String} to {@link Object}, the mapping to remove from
     * @param key {@link String} the canonical key
     * @return {@link Object} the value that was stored, or null when there was none
     */
    static @Nullable Object remove(final Map<String, Object> mapping, final String key) {
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
     * Removes the temporary file a write worked through. A move into place leaves nothing here to
     * remove.
     *
     * <p>A failure to remove it is swallowed. A leftover is not what the caller needs to hear
     * about, whether their write failed or landed.
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
     * Reduces a key to the form config binding compares by, so that separators and letter case stop
     * telling two spellings of one key apart.
     *
     * @param key {@link String} the key as written
     * @return {@link String} the comparable form
     */
    private static String relaxed(final String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
