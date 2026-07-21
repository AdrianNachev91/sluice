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

// PrepDir already mirrors index.json's field shape and order 1:1 (see PrepDir's own doc comment);
// this just substitutes String for its two Path fields, for the same reason as SidecarWriter.
@Component
public class PrepIndexWriter {

    private final JsonMapper mapper;

    public PrepIndexWriter() {
        this(JsonMapper.builder().build());
    }

    // Package-private: lets a test inject a mock JsonMapper to exercise the JacksonException
    // catch branch below, which a real write failure can't trigger deterministically.
    PrepIndexWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    private record Index(
            String scope, String basePath, int photos, int montages, String prepDir, List<String> entries) {
    }

    public void write(Path indexPath, PrepDir prepDir) {
        var document = new Index(
                prepDir.scope(),
                prepDir.basePath().toString(),
                prepDir.photos(),
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
