package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

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
        var target = new RecordingCuller("anthropic");
        var other = new RecordingCuller("external-agent");
        var dispatcher = new CullDispatcher(List.of(other, target), settingsFor("anthropic"));

        CullReport report = dispatcher.cull(PREP, OPTIONS);

        assertThat(target.receivedPrep).isSameAs(PREP);
        assertThat(target.receivedOptions).isSameAs(OPTIONS);
        assertThat(report).isSameAs(target.report);
        assertThat(other.receivedPrep).isNull();
    }

    @Test
    void failsLoudWhenNoCullerMatchesTheProvider() {
        var dispatcher = new CullDispatcher(List.of(new RecordingCuller("anthropic")), settingsFor("ollama"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
    }

    @Test
    void failsLoudWhenNoCullersAreRegisteredAtAll() {
        var dispatcher = new CullDispatcher(List.of(), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external-agent");
    }

    @Test
    void propagatesTheCullExceptionFromTheSelectedCuller() {
        var dispatcher = new CullDispatcher(
                List.of(new ThrowingCuller("external-agent")), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.cull(PREP, OPTIONS))
                .isInstanceOf(CullException.class)
                .hasMessage("shards missing");
    }

    @Test
    void resolvesTheProviderFreshOnEachCall() throws CullException {
        var external = new RecordingCuller("external-agent");
        var anthropic = new RecordingCuller("anthropic");
        var settings = new MutableSettings("external-agent");
        var dispatcher = new CullDispatcher(List.of(external, anthropic), settings);

        dispatcher.cull(PREP, OPTIONS);
        settings.provider = "anthropic";
        dispatcher.cull(PREP, OPTIONS);

        assertThat(external.receivedPrep).isSameAs(PREP);
        assertThat(anthropic.receivedPrep).isSameAs(PREP);
    }

    @Test
    void routesTheProgressCallbackToTheSelectedCuller() throws CullException {
        var target = new RecordingCuller("anthropic");
        var dispatcher = new CullDispatcher(List.of(target), settingsFor("anthropic"));
        ProgressCallback progress = (_, _) -> { };

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

    private static CullSettings settingsFor(String provider) {
        return new MutableSettings(provider);
    }

    private static final class MutableSettings implements CullSettings {

        private String provider;

        private MutableSettings(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public List<CullCategory> categories() {
            return List.of();
        }

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }
    }

    private static final class RecordingCuller implements VisionCuller {

        private final String id;
        private final CullReport report = new CullReport(0, 0, 0, 0);
        private @Nullable PrepDir receivedPrep;
        private @Nullable CullOptions receivedOptions;
        private @Nullable ProgressCallback receivedProgress;

        private RecordingCuller(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) {
            this.receivedPrep = prep;
            this.receivedOptions = opts;
            return report;
        }

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts, ProgressCallback progress) {
            this.receivedProgress = progress;
            return cull(prep, opts);
        }
    }

    private record ThrowingCuller(String id) implements VisionCuller {

        @Override
        public CullReport cull(PrepDir prep, CullOptions opts) throws CullException {
            throw new CullException("shards missing");
        }
    }
}
