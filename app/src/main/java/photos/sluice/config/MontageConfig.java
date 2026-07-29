package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sluice.montage} settings: the pixel size of each contact-sheet tile, and how
 * many tiles form a row.
 *
 * <p>Declares its fields in the same order as {@link photos.sluice.domain.cull.MontageConfig}.
 * Spring binds by name, so the order here is free to choose. Keeping the two records aligned means
 * a positional mapping mistake between them cannot arise.
 */
@ConfigurationProperties(prefix = "sluice.montage")
public record MontageConfig(int tileSize, int tilesPerRow) {
}
