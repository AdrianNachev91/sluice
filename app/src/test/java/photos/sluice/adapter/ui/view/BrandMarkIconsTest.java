package photos.sluice.adapter.ui.view;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrandMarkIconsTest {

    // Listed again rather than read from BrandMark, so that dropping a size from its list and
    // dropping the file cannot pass as a pair.
    private static final int[] EXPECTED_SIZES = {16, 24, 32, 48, 64, 128, 256};

    @Test
    void everyIconSizeTheWindowAsksForIsInTheBuild() {
        for (final int size : EXPECTED_SIZES) {
            final var icon = "/ui/icons/icon-" + size + "x" + size + ".png";
            assertThat(BrandMark.class.getResource(icon))
                    .describedAs("icon %s", icon)
                    .isNotNull();
        }
    }
}
