package photos.sluice.application.port.out;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

public interface HeicDecoder {

    // Empty means no decoder is available or the file couldn't be decoded - callers degrade
    // gracefully (flag unreviewed-heic, still date-sort) rather than failing the whole run.
    Optional<BufferedImage> decode(Path file);
}
