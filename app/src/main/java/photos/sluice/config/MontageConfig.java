package photos.sluice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Field order is reversed from photos.sluice.domain.cull.MontageConfig (tileSize, tilesPerRow) -
// both are structurally identical int pairs, so passing this record's fields to that one's
// constructor in the wrong order compiles cleanly. Whichever caller builds a domain MontageConfig
// from this one must map fields by name, not by position.
@ConfigurationProperties(prefix = "sluice.montage")
public record MontageConfig(int tilesPerRow, int tileSize) {
}
