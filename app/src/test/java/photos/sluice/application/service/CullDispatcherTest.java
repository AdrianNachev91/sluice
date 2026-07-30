package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CullDispatcherTest {

    private static final PrepDir PREP =
            new PrepDir("2019", Path.of("base"), 0, List.of(), 0, Path.of("prep"), List.of());
    private static final CullOptions OPTIONS = new CullOptions(false, null);

    @Test
    void routesToTheCullerMatchingTheConfiguredProvider() throws CullException {
        final var target = new RecordingCuller("anthropic");
        final var other = new RecordingCuller("external-agent");
        final var dispatcher = new CullDispatcher(List.of(other, target), settingsFor("anthropic"));

        final CullReport report = dispatcher.cull(PREP, OPTIONS);

        assertThat(target.receivedPrep).isSameAs(PREP);
        assertThat(target.receivedOptions).isSameAs(OPTIONS);
        assertThat(report).isSameAs(target.report);
        assertThat(other.receivedPrep).isNull();
    }

    @Test
    void failsLoudWhenNoCullerMatchesTheProvider() {
        final var dispatcher = new CullDispatcher(List.of(new RecordingCuller("anthropic")), settingsFor("ollama"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
    }

    @Test
    void failsLoudWhenNoCullersAreRegisteredAtAll() {
        final var dispatcher = new CullDispatcher(List.of(), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external-agent");
    }

    @Test
    void propagatesTheCullExceptionFromTheSelectedCuller() {
        final var dispatcher = new CullDispatcher(
                List.of(new ThrowingCuller("external-agent")), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessage("shards missing");
    }

    @Test
    void resolvesTheProviderFreshOnEachCall() throws CullException {
        final var external = new RecordingCuller("external-agent");
        final var anthropic = new RecordingCuller("anthropic");
        final var settings = new MutableSettings("external-agent");
        final var dispatcher = new CullDispatcher(List.of(external, anthropic), settings);

        dispatcher.cull(PREP, OPTIONS);
        settings.provider = "anthropic";
        dispatcher.cull(PREP, OPTIONS);

        assertThat(external.receivedPrep).isSameAs(PREP);
        assertThat(anthropic.receivedPrep).isSameAs(PREP);
    }

    @Test
    void routesTheProgressCallbackToTheSelectedCuller() throws CullException {
        final var target = new RecordingCuller("anthropic");
        final var dispatcher = new CullDispatcher(List.of(target), settingsFor("anthropic"));
        final ProgressCallback progress = (_, _) -> { };

        dispatcher.cull(PREP, OPTIONS, progress);

        assertThat(target.receivedProgress).isSameAs(progress);
    }

    @Test
    void failsLoudWhenTwoCullersShareAnId() {
        assertThatThrownBy(() -> new CullDispatcher(
                List.of(new RecordingCuller("dup"), new RecordingCuller("dup")), settingsFor("dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    private static CullSettings settingsFor(final String provider) {
        return new MutableSettings(provider);
    }

    private static final class MutableSettings implements CullSettings {

        private String provider;

        private MutableSettings(final String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return this.provider;
        }

        @Override
        public List<CullCategory> categories() {
            return List.of();
        }

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL, null);
        }
    }

    private static final class RecordingCuller implements VisionCuller {

        private final String id;
        private final CullReport report = new CullReport(0, 0, 0, 0);
        private @Nullable PrepDir receivedPrep;
        private @Nullable CullOptions receivedOptions;
        private @Nullable ProgressCallback receivedProgress;

        private RecordingCuller(final String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) {
            this.receivedPrep = prep;
            this.receivedOptions = opts;
            return this.report;
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress) {
            this.receivedProgress = progress;
            return this.cull(prep, opts);
        }
    }

    private record ThrowingCuller(String id) implements VisionCuller {

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
            throw new CullException("shards missing");
        }
    }
}
