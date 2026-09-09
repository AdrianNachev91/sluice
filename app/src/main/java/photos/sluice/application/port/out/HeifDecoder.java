package photos.sluice.application.port.out;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The effect boundary application services use to decode HEIF/AVIF/HEIC images into a standard
 * in-memory form. AVIF shares HEIF's ISOBMFF-derived structure and is decoded by the same
 * underlying library ({@code libheif} brands itself as "an HEIF and AVIF decoder"). One port
 * covers all three formats rather than one per extension.
 */
public interface HeifDecoder {

    /**
     * Empty means no decoder is available or the file could not be decoded, letting a caller
     * degrade rather than fail the whole run.
     *
     * @param file {@link Path} the HEIF/AVIF/HEIC file to decode
     * @return an {@link Optional} {@link BufferedImage}, or empty if decoding is unavailable or fails
     */
    Optional<BufferedImage> decode(Path file);
}
