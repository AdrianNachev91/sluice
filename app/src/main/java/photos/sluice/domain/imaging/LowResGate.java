package photos.sluice.domain.imaging;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.Dimensions;
import photos.sluice.domain.model.MediaType;

public final class LowResGate {

    // 1024-based (KiB), not a decimal 50000-byte threshold.
    private static final long SMALL_FILE_BYTES = 50 * 1024;

    // Public so other classes asking a related but distinct question - not "is this photo
    // low-res" but "is this specific recovered preview big enough to trust a vision judgment on"
    // (TileRenderer) - share the same bar instead of duplicating the literal.
    public static final int MIN_DIMENSION = 640;

    private LowResGate() {
    }

    // extension must already be lowercase (matches MediaTypeDetector's own contract), since the
    // "svg" comparison here is exact rather than case-insensitive.
    public static boolean isLowRes(
            long fileSizeBytes, @Nullable Dimensions dimensions, MediaType type, String extension) {
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
