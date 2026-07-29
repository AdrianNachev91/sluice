package photos.sluice.adapter.vision;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 * <p>Any failure is loud and unchecked, never part of a cull's fixable-problem report. The
 * sidecar is written by this app, so an unreadable or incomplete one means the prep directory
 * itself is broken. No shard correction can repair that. The scope needs re-prepping.
 */
@Component
class SidecarReader {

    private final JsonMapper mapper = JsonMapper.builder().build();

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
    public List<SidecarPhotoEntry> readEntries(Path sidecarPath) {
        final RawSidecar raw;
        try (var input = Files.newInputStream(sidecarPath)) {
            raw = mapper.readValue(input, RawSidecar.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read sidecar " + sidecarPath, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to read sidecar " + sidecarPath, new IOException(e));
        }
        // The IDE binds the generic result to the non-null RawSidecar type and can't see that a
        // literal null document deserializes to null.
        //noinspection ConstantValue
        if (raw == null || raw.photos() == null) {
            throw new UncheckedIOException("Sidecar " + sidecarPath + " has no photos array",
                    new IOException("missing photos"));
        }
        // A montage only exists for a non-empty batch, so an empty photos array is corruption too.
        // Tolerating it would strip those files from the in-scope set, and the validator would then
        // blame the culling agent for out-of-scope decisions when the prep dir is what's broken.
        if (raw.photos().isEmpty()) {
            throw new UncheckedIOException("Sidecar " + sidecarPath + " lists no photos",
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
    private static SidecarPhotoEntry entryOf(@Nullable RawPhoto photo, Path sidecarPath) {
        if (photo == null) {
            throw new UncheckedIOException("Sidecar " + sidecarPath + " has a null photo entry",
                    new IOException("null photo entry"));
        }
        return new SidecarPhotoEntry(
                Path.of(required(photo.src(), "src", sidecarPath)),
                required(photo.name(), "name", sidecarPath),
                timeOf(required(photo.time(), "time", sidecarPath), sidecarPath),
                required(photo.received(), "received", sidecarPath));
    }

    /**
     * Returns a required field's value, failing loud if it's null.
     *
     * @param value T the field's value, possibly null
     * @param field {@link String} the field's name, used only for the error message
     * @param sidecarPath {@link Path} the sidecar's path, used only for error messages
     * @return T the non-null value
     */
    private static <T> T required(@Nullable T value, String field, Path sidecarPath) {
        if (value == null) {
            throw new UncheckedIOException(
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
    private static Instant timeOf(String time, Path sidecarPath) {
        try {
            return Instant.parse(time);
        } catch (DateTimeParseException e) {
            throw new UncheckedIOException(
                    "Sidecar " + sidecarPath + " has a photo entry with unparseable time '" + time + "'",
                    new IOException(e));
        }
    }
}
