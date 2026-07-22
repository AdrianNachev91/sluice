package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Declares its fields in the same order as photos.sluice.domain.cull.MontageConfig on purpose.
// Spring binds by name, so the order here is free. Keeping the two records aligned means a
// positional mapping mistake between them cannot arise.
@ConfigurationProperties(prefix = "sluice.montage")
public record MontageConfig(int tileSize, int tilesPerRow) {
}
