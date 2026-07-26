package photos.sluice.adapter.imaging;

import org.springframework.stereotype.Component;
import photos.sluice.domain.cull.PrepDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// PrepDir already mirrors index.json's field shape and order 1:1 (see PrepDir's own doc comment).
// This substitutes String for its Path-typed fields (basePath, prepDir, and each entry in
// unreviewable), for the same reason as SidecarWriter.
@Component
public class PrepIndexWriter {

    private final JsonMapper mapper;

    /**
     * Creates a writer using a default JsonMapper.
     */
    public PrepIndexWriter() {
        this(JsonMapper.builder().build());
    }

    /**
     * Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException
     * catch branch below, which a real write failure can't trigger deterministically.
     *
     * @param mapper {@link JsonMapper} the JSON mapper to write with
     */
    PrepIndexWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    private record Index(
            String scope, String basePath, int photos, List<String> unreviewable, int montages,
            String prepDir, List<String> entries) {
    }

    /**
     * Writes a prep directory's index as JSON.
     *
     * @param indexPath {@link Path} the file to write the index to
     * @param prepDir {@link PrepDir} the prep directory metadata to serialize
     */
    public void write(Path indexPath, PrepDir prepDir) {
        var document = new Index(
                prepDir.scope(),
                prepDir.basePath().toString(),
                prepDir.photos(),
                prepDir.unreviewable().stream().map(Path::toString).toList(),
                prepDir.montages(),
                prepDir.prepDir().toString(),
                prepDir.entries());
        try (var output = Files.newOutputStream(indexPath)) {
            mapper.writeValue(output, document);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write prep index " + indexPath, e);
        } catch (JacksonException e) {
            throw new UncheckedIOException("Failed to write prep index " + indexPath, new IOException(e));
        }
    }
}
