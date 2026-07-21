package photos.sluice.domain.cull;

public record MontageConfig(int tileSize, int tilesPerRow) {

    public MontageConfig {
        if (tileSize <= 0 || tilesPerRow <= 0) {
            throw new IllegalArgumentException(
                    "tileSize and tilesPerRow must be positive: tileSize=%d, tilesPerRow=%d"
                            .formatted(tileSize, tilesPerRow));
        }
    }

    // 224px tiles, 5x5 grid: image-token cost scales with tile pixel count, not grid size, so this
    // is the recall/cost balance point. A caller can size up tilesPerRow when a scope is known to
    // be all-keepers and round-trips matter more than per-tile recall.
    public static MontageConfig defaults() {
        return new MontageConfig(224, 5);
    }
}
