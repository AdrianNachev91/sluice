package photos.sluice.adapter.imaging;

import org.springframework.stereotype.Component;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a montage's sidecar metadata to disk as JSON.
 *
 * <p>{@link java.nio.file.Path} and {@link java.time.Instant} values serialize as plain strings,
 * via this class's private record fields. Jackson's own {@code Path}/{@code Instant} handling is
 * not used, since its default {@code Path} serializer emits a {@code file://} URI. This project's
 * on-disk contract, and the culler agent reading it, expect a plain path string instead.
 */
@Component
public class SidecarWriter {

    private final JsonMapper mapper;

    /**
     * Creates a writer using a default JsonMapper.
     */
    public SidecarWriter() {
        this(JsonMapper.builder().build());
    }

    /**
     * Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException
     * catch branch below, which a real write failure can't trigger deterministically.
     *
     * @param mapper {@link JsonMapper} the JSON mapper to write with
     */
    SidecarWriter(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The on-disk shape of one photo entry within a montage's sidecar: its source path, file
     * name, timestamp, and whether it looks WhatsApp-received, all as plain strings/primitives.
     */
    private record Photo(String src, String name, String time, boolean received) {
    }

    /**
     * The on-disk shape of a montage's sidecar file: the montage image's path and the list of
     * photo entries it contains.
     */
    private record Sidecar(String montage, List<Photo> photos) {
    }

    /**
     * Writes a montage's sidecar metadata as JSON.
     *
     * @param sidecarPath {@link Path} the file to write the sidecar to
     * @param montagePath {@link Path} the montage image this sidecar describes
     * @param photos a {@link List} of {@link SidecarPhotoEntry}, the photo entries to record
     */
    public void write(final Path sidecarPath, final Path montagePath, final List<SidecarPhotoEntry> photos) {
        final var document = new Sidecar(
                montagePath.toString(),
                photos.stream()
                        .map(p -> new Photo(p.src().toString(), p.name(), p.time().toString(), p.received()))
                        .toList());
        try (final var output = Files.newOutputStream(sidecarPath)) {
            mapper.writeValue(output, document);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write sidecar " + sidecarPath, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to write sidecar " + sidecarPath, new IOException(e));
        }
    }
}
