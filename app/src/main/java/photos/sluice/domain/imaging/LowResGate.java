package photos.sluice.domain.imaging;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.Dimensions;
import photos.sluice.domain.model.MediaType;

/**
 * Decides whether a media file counts as low resolution, based on its file size and, when known,
 * its pixel dimensions.
 *
 * <p>Videos and SVGs are never considered low resolution. Everything else fails the gate below a
 * small file-size floor, or below a minimum dimension when its size is known.
 */
public final class LowResGate {

    // 1024-based (KiB), not a decimal 50000-byte threshold.
    private static final long SMALL_FILE_BYTES = 50 * 1024;

    // Public so a related but distinct question reads the same bar rather than duplicating the
    // literal. That question is whether a recovered preview is big enough to judge from.
    public static final int MIN_DIMENSION = 640;

    /**
     * Prevents instantiation of this static utility class.
     */
    private LowResGate() {
    }

    /**
     * extension must already be lowercase (matches MediaTypeDetector's own contract), since the
     * "svg" comparison here is exact rather than case-insensitive.
     *
     * @param fileSizeBytes long the file size in bytes
     * @param dimensions {@link Dimensions} the image dimensions, or null if unknown
     * @param type {@link MediaType} the media type
     * @param extension {@link String} the lowercase file extension
     * @return boolean true if the file counts as low resolution
     */
    public static boolean isLowRes(
            final long fileSizeBytes, final @Nullable Dimensions dimensions, final MediaType type,
            final String extension) {
        if (type == MediaType.VIDEO || extension.equals("svg")) {
            return false;
        }
        if (fileSizeBytes < SMALL_FILE_BYTES) {
            return true;
        }
        if (dimensions == null) {
            return false;
        }
        return Math.max(dimensions.width(), dimensions.height()) < MIN_DIMENSION;
    }
}
