package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sluice.montage")
public record MontageConfig(int tilesPerRow, int tileSize) {
}
