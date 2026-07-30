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

/**
 * Writes a {@link PrepDir}'s metadata to disk as {@code index.json}.
 *
 * <p>{@link PrepDir} already mirrors the file's field shape and order (see its own doc comment).
 * This class substitutes {@link String} for its {@link java.nio.file.Path}-typed fields
 * ({@code basePath}, {@code prepDir}, and each entry in {@code unreviewable}), for the same reason
 * as {@link SidecarWriter}.
 */
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
    PrepIndexWriter(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The on-disk shape of {@code index.json}, mirroring {@link PrepDir} field-for-field with
     * path values substituted as plain strings.
     */
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
    public void write(final Path indexPath, final PrepDir prepDir) {
        final var document = new Index(
                prepDir.scope(),
                prepDir.basePath().toString(),
                prepDir.photos(),
                prepDir.unreviewable().stream().map(Path::toString).toList(),
                prepDir.montages(),
                prepDir.prepDir().toString(),
                prepDir.entries());
        try (final var output = Files.newOutputStream(indexPath)) {
            mapper.writeValue(output, document);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write prep index " + indexPath, e);
        } catch (final JacksonException e) {
            throw new UncheckedIOException("Failed to write prep index " + indexPath, new IOException(e));
        }
    }
}
