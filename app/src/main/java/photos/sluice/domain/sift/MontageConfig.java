package photos.sluice.domain.sift;

/**
 * The pixel size of each square tile in a montage contact sheet, and how many tiles sit in each
 * row.
 */
public record MontageConfig(int tileSize, int tilesPerRow) {

    /**
     * Validates tileSize and tilesPerRow are positive.
     *
     * @param tileSize int pixel size of each square tile
     * @param tilesPerRow int tiles per montage row
     */
    public MontageConfig {
        if (tileSize <= 0 || tilesPerRow <= 0) {
            throw new IllegalArgumentException(
                    "tileSize and tilesPerRow must be positive: tileSize=%d, tilesPerRow=%d"
                            .formatted(tileSize, tilesPerRow));
        }
    }

    /**
     * 224px tiles, 5x5 grid: image-token cost scales with tile pixel count, not grid size, so this
     * is the recall/cost balance point. A denser grid trades per-tile recall for fewer round trips.
     *
     * @return {@link MontageConfig} the default montage configuration
     */
    public static MontageConfig defaults() {
        return new MontageConfig(224, 5);
    }
}
