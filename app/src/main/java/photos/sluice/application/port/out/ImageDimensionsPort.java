package photos.sluice.application.port.out;

import photos.sluice.domain.model.Dimensions;

import java.nio.file.Path;
import java.util.Optional;

public interface ImageDimensionsPort {

    Optional<Dimensions> read(Path file);
}
