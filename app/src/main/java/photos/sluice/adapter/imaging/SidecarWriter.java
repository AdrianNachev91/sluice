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

// Path and Instant serialize as plain strings via these private record fields, not Jackson's own
// Path/Instant handling - Jackson's default Path serializer emits a file:// URI, not the plain
// path string this project's on-disk contract (and the culler agent reading it) expects.
@Component
public class SidecarWriter {

    private final JsonMapper mapper;

    public SidecarWriter() {
        this(JsonMapper.builder().build());
    }

    // Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException
    // catch branch below, which a real write failure can't trigger deterministically.
    SidecarWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    private record Photo(String src, String name, String time, boolean received) {
    }

    private record Sidecar(String montage, List<Photo> photos) {
    }

    public void write(Path sidecarPath, Path montagePath, List<SidecarPhotoEntry> photos) {
        var document = new Sidecar(
                montagePath.toString(),
                photos.stream()
                        .map(p -> new Photo(p.src().toString(), p.name(), p.time().toString(), p.received()))
                        .toList());
        try (var output = Files.newOutputStream(sidecarPath)) {
            mapper.writeValue(output, document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write sidecar " + sidecarPath, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to write sidecar " + sidecarPath, new IOException(e));
        }
    }
}
