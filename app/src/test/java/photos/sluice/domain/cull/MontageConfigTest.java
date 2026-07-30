package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MontageConfigTest {

    @Test
    void defaultsAre224PixelTilesInA5x5Grid() {
        final MontageConfig config = MontageConfig.defaults();

        assertThat(config.tileSize()).isEqualTo(224);
        assertThat(config.tilesPerRow()).isEqualTo(5);
    }

    @Test
    void nonPositiveTileSizeThrows() {
        assertThatThrownBy(() -> new MontageConfig(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveTilesPerRowThrows() {
        assertThatThrownBy(() -> new MontageConfig(224, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
