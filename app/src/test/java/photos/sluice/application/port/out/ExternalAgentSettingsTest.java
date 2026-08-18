package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.job.WatchMode;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAgentSettingsTest {

    @Test
    void carriesTheGivenMode() {
        final var settings = new ExternalAgentSettings(WatchMode.WATCH);

        assertThat(settings.mode()).isEqualTo(WatchMode.WATCH);
    }

    @Test
    void defaultsModeToWatchWhenNull() {
        // Deliberately violates the non-null contract: Spring's reflective config binding can pass
        // null past the annotation when sluice.cull.external-agent.mode is absent.
        //noinspection DataFlowIssue
        final var settings = new ExternalAgentSettings(null);

        assertThat(settings.mode()).isEqualTo(WatchMode.WATCH);
    }
}
