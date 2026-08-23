package photos.sluice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SluiceApplicationTest {

    @Test
    void nothingToDoOpensTheWindow() {
        assertThat(SluiceApplication.opensTheWindow(new String[0])).isTrue();
    }

    @Test
    void aVerbRunsAsACommand() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"sort", "2019"})).isFalse();
    }

    // A settings override carries no verb, and still does not open the window. The window would
    // have taken it before this branch existed, so the choice is deliberate rather than incidental.
    @Test
    void aSettingsOverrideAloneRunsAsACommandToo() {
        assertThat(SluiceApplication.opensTheWindow(new String[]{"--sluice.paths.inbox=/photos"})).isFalse();
    }
}
