package photos.sluice.adapter.vision;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Reads a {@code montage-NNN.json} sidecar and returns every photo entry it lists. Callers
 * project the fields they need. The srcs are the authoritative in-scope file set
 * {@link photos.sluice.domain.cull.ShardValidator} checks decisions against. Fields outside the
 * photo entries (the montage field, anything the writer grows later) stay ignored. The sidecar's
 * full shape is owned by the writer that produces it.
 *
 * <p>Any failure is loud and unchecked. The sidecar is written by this app, so an unreadable or
 * incomplete one means the prep directory itself is broken, and no shard correction can repair
 * that. Throwing keeps this class out of the business of deciding what to do about it.
 *
 * <p>Callers make that decision, and they differ. The apply phase turns it into a finding the user
 * answers, choosing between trusting the montage's existing shard and setting the montage aside. A
 * culler skips the montage. Two failure kinds are distinguished for callers that care: damaged
 * content throws {@link MalformedPrepJsonException}, while a read that merely failed throws a plain
 * {@link UncheckedIOException}.
 */
@Component
class SidecarReader {

    private final JsonMapper mapper;

    /**
     * Constructs the reader with the default JSON mapper.
     */
    SidecarReader() {
        this(JsonMapper.builder().build());
    }

    /**
     * Package-private: lets a test inject a mock JsonMapper to exercise the JacksonIOException
     * catch branch, which a real read failure can't trigger deterministically.
     *
     * @param mapper {@link JsonMapper} the JSON mapper used for sidecar I/O
     */
    SidecarReader(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The JSON shape one photo entry takes in the sidecar. Every field is nullable so a missing
     * one is reported by name rather than crashing the parse.
     */
    private record RawPhoto(@Nullable String src, @Nullable String name, @Nullable String time,
                            @Nullable Boolean received) {
    }

    /**
     * The full JSON document a montage's sidecar file holds: its list of photo entries.
     */
    private record RawSidecar(@Nullable List<@Nullable RawPhoto> photos) {
    }

    /**
     * Reads every photo entry from a montage's sidecar JSON.
     *
     * @param sidecarPath {@link Path} path of the sidecar JSON to read
     * @return a {@link List} of {@link SidecarPhotoEntry}, every photo entry the sidecar lists
     */
    public List<SidecarPhotoEntry> readEntries(final Path sidecarPath) {
        final RawSidecar raw;
        try (final var input = Files.newInputStream(sidecarPath)) {
            raw = this.mapper.readValue(input, RawSidecar.class);
        } catch (final NoSuchFileException e) {
            // Absent entirely is not a transient read failure - it will never resolve on retry,
            // just like a genuinely malformed one.
            throw new MalformedPrepJsonException("Sidecar " + sidecarPath + " does not exist", e);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read sidecar " + sidecarPath, e);
        } catch (final JacksonIOException e) {
            // The stream opened fine and failed on a later read - a lock or a dropped network mount
            // arriving mid-read, not malformed content. Jackson wraps the underlying IOException
            // rather than letting it propagate, so it needs its own clause ahead of JacksonException.
            throw new UncheckedIOException("Failed to read sidecar " + sidecarPath, e.getCause());
        } catch (final JacksonException e) {
            throw new MalformedPrepJsonException("Failed to parse sidecar " + sidecarPath, e);
        }
        // The IDE binds the generic result to the non-null RawSidecar type and can't see that a
        // literal null document deserializes to null.
        //noinspection ConstantValue
        if (raw == null || raw.photos() == null) {
            throw new MalformedPrepJsonException("Sidecar " + sidecarPath + " has no photos array",
                    new IOException("missing photos"));
        }
        // A montage only exists for a non-empty batch, so an empty photos array is corruption too.
        // Tolerating it would strip those files from the in-scope set, and the validator would then
        // blame the culling agent for out-of-scope decisions when the prep dir is what's broken.
        if (raw.photos().isEmpty()) {
            throw new MalformedPrepJsonException("Sidecar " + sidecarPath + " lists no photos",
                    new IOException("empty photos"));
        }
        return raw.photos().stream()
                .map(photo -> entryOf(photo, sidecarPath))
                .toList();
    }

    /**
     * Converts a raw photo DTO into a {@link SidecarPhotoEntry}, failing loud on any missing field.
     *
     * @param photo {@link RawPhoto} the raw photo entry to convert
     * @param sidecarPath {@link Path} the sidecar's path, used only for error messages
     * @return {@link SidecarPhotoEntry} the converted photo entry
     */
    private static SidecarPhotoEntry entryOf(final @Nullable RawPhoto photo, final Path sidecarPath) {
        if (photo == null) {
            throw new MalformedPrepJsonException("Sidecar " + sidecarPath + " has a null photo entry",
                    new IOException("null photo entry"));
        }
        return new SidecarPhotoEntry(
                srcOf(required(photo.src(), "src", sidecarPath), sidecarPath),
                required(photo.name(), "name", sidecarPath),
                timeOf(required(photo.time(), "time", sidecarPath), sidecarPath),
                required(photo.received(), "received", sidecarPath));
    }

    /**
     * Converts a photo entry's src string to a {@link Path}, failing loud on a value this platform
     * cannot make a path out of. That leaves no unchecked escape route out of a caller's read-failure
     * handling, the same as every other unusable field here.
     *
     * <p>A path naming a filesystem root and nothing else is refused for the same reason. It parses
     * cleanly and has no file name at all, so every later step that asks for one gets nothing back.
     *
     * @param src {@link String} the entry's src string
     * @param sidecarPath {@link Path} the sidecar's path, used only for error messages
     * @return {@link Path} the entry's source path
     */
    private static Path srcOf(final String src, final Path sidecarPath) {
        final Path source;
        try {
            source = Path.of(src);
        } catch (final InvalidPathException e) {
            throw new MalformedPrepJsonException("Sidecar " + sidecarPath + " has an unusable src: " + src,
                    new IOException(e));
        }
        if (source.getFileName() == null) {
            throw new MalformedPrepJsonException(
                    "Sidecar " + sidecarPath + " has a src naming a whole filesystem root rather than a file: " + src,
                    new IOException("no file name"));
        }
        return source;
    }

    /**
     * Returns a required field's value, failing loud if it's null.
     *
     * @param value T the field's value, possibly null
     * @param field {@link String} the field's name, used only for the error message
     * @param sidecarPath {@link Path} the sidecar's path, used only for error messages
     * @return T the non-null value
     */
    private static <T> T required(final @Nullable T value, final String field, final Path sidecarPath) {
        if (value == null) {
            throw new MalformedPrepJsonException(
                    "Sidecar " + sidecarPath + " has a photo entry missing '" + field + "'",
                    new IOException("missing " + field));
        }
        return value;
    }

    /**
     * Parses a raw timestamp string, failing loud if it's unparseable.
     *
     * @param time {@link String} the raw timestamp string to parse
     * @param sidecarPath {@link Path} the sidecar's path, used only for error messages
     * @return {@link Instant} the parsed instant
     */
    private static Instant timeOf(final String time, final Path sidecarPath) {
        try {
            return Instant.parse(time);
        } catch (final DateTimeParseException e) {
            throw new MalformedPrepJsonException(
                    "Sidecar " + sidecarPath + " has a photo entry with unparseable time '" + time + "'", e);
        }
    }
}
