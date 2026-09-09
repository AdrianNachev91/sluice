package photos.sluice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SluiceDesktopTest {

    @Test
    void theInstalledIconOpensTheWindow() {
        assertThat(SluiceApplication.opensTheWindow(SluiceDesktop.WINDOW_ARGUMENTS)).isTrue();
    }
}
