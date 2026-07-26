package photos.sluice.application.port.out;

import photos.sluice.domain.model.Dimensions;

import java.nio.file.Path;
import java.util.Optional;

public interface ImageDimensionsPort {

    /**
     * Reads an image's pixel dimensions.
     *
     * @param file {@link Path} the image file to inspect
     * @return an {@link Optional} {@link Dimensions}, or empty if they could not be determined
     */
    Optional<Dimensions> read(Path file);
}
