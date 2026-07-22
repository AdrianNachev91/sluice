package photos.sluice.adapter.vision;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Reads a montage-NNN.json sidecar and returns the source paths of the photos it lists - the
// authoritative in-scope file set ShardValidator checks decisions against. This is a projection,
// not a codec. It consumes only the fields it needs and ignores the rest, because the sidecar's
// full shape is owned by the writer that produces it. Any failure is loud and unchecked, never
// part of a cull's fixable-problem report. The sidecar is written by this app, so an unreadable
// or incomplete one means the prep directory itself is broken. No shard correction can repair
// that; the scope needs re-prepping.
@Component
class SidecarReader {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private record RawPhoto(@Nullable String src) {
    }

    private record RawSidecar(@Nullable List<@Nullable RawPhoto> photos) {
    }

    public List<Path> readSrcs(Path sidecarPath) {
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
                .map(photo -> srcOf(photo, sidecarPath))
                .toList();
    }

    private static Path srcOf(@Nullable RawPhoto photo, Path sidecarPath) {
        if (photo == null || photo.src() == null) {
            throw new UncheckedIOException("Sidecar " + sidecarPath + " has a photo entry without a src",
                    new IOException("missing src"));
        }
        return Path.of(photo.src());
    }
}
