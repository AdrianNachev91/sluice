package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.job.WatchMode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAgentSettingsTest {

    @Test
    void carriesTheGivenModeAndWatchTimeout() {
        var settings = new ExternalAgentSettings(WatchMode.WATCH, Duration.ofMinutes(5));

        assertThat(settings.mode()).isEqualTo(WatchMode.WATCH);
        assertThat(settings.watchTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void defaultsModeToManualWhenNull() {
        // Deliberately violates the non-null contract: Spring's reflective config binding can pass
        // null past the annotation when sluice.cull.external-agent.mode is absent.
        //noinspection DataFlowIssue
        var settings = new ExternalAgentSettings(null, null);

        assertThat(settings.mode()).isEqualTo(WatchMode.MANUAL);
        assertThat(settings.watchTimeout()).isNull();
    }
}
