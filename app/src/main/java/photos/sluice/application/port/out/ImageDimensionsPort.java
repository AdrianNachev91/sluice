package photos.sluice.application.port.out;

import photos.sluice.domain.model.Dimensions;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The effect boundary application services use to read an image's pixel dimensions without
 * decoding the whole file into memory. Returns empty when the dimensions cannot be determined with
 * enough confidence to trust, letting a caller degrade gracefully rather than fail the whole run.
 */
public interface ImageDimensionsPort {

    /**
     * Reads an image's pixel dimensions.
     *
     * @param file {@link Path} the image file to inspect
     * @return an {@link Optional} {@link Dimensions}, or empty if they could not be determined with
     *         enough confidence to trust
     */
    Optional<Dimensions> read(Path file);
}
