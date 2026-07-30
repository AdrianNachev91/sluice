package photos.sluice.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    // Distinct values per field, so this fails if a future reordering of either record's fields
    // turns the bean's mapping into a silent tileSize/tilesPerRow swap.
    @Test
    void montageConfigBeanMapsFieldsByNameNotPosition() {
        final var domainConfig = new AppConfig().montageConfig(new MontageConfig(224, 5));

        assertThat(domainConfig.tileSize()).isEqualTo(224);
        assertThat(domainConfig.tilesPerRow()).isEqualTo(5);
    }
}
