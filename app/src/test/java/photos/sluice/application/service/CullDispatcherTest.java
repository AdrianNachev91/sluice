package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullOptions;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.VisionCuller;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CullDispatcherTest {

    private static final PrepDir PREP =
            new PrepDir("2019", List.of(CullCategory.of("junk", "objectively worthless")), Path.of("base"), 0,
                    List.of(), 0, Path.of("prep"), List.of());
    private static final CullOptions OPTIONS = CullOptions.unbounded(false);

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

    // Two registered cullers whose types differ, so answering off the wrong one is a visible
    // failure rather than a coincidence. CullEngine reads this to tell a manual pause from a
    // failed run, and CullWatchers to decide whether a run is worth watching.
    @Test
    void answersTheTypeOfTheCullerTheConfiguredProviderNames() {
        final List<VisionCuller> cullers = List.of(new RecordingCuller("waits-for-a-person", ProviderType.MANUAL),
                new RecordingCuller("calls-a-model", ProviderType.API));

        final var waiting = new CullDispatcher(cullers, settingsFor("waits-for-a-person"));
        assertThat(waiting.configuredProviderIs(ProviderType.MANUAL)).isTrue();
        assertThat(waiting.configuredProviderIs(ProviderType.API)).isFalse();

        final var calling = new CullDispatcher(cullers, settingsFor("calls-a-model"));
        assertThat(calling.configuredProviderIs(ProviderType.API)).isTrue();
        assertThat(calling.configuredProviderIs(ProviderType.MANUAL)).isFalse();
    }

    // Both callers ask in order to decide whether to do something extra. An id this build cannot
    // cull with is told no rather than refused, so a watcher declines to arm rather than failing.
    @Test
    void answersNoForAProviderNoRegisteredCullerClaims() {
        final var dispatcher = new CullDispatcher(
                List.of(new RecordingCuller("calls-a-model", ProviderType.API)), settingsFor("nothing-registered"));

        assertThat(dispatcher.configuredProviderIs(ProviderType.API)).isFalse();
        assertThat(dispatcher.configuredProviderIs(ProviderType.MANUAL)).isFalse();
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
        final ProgressCallback progress = (_, _) -> {
        };

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
        public MontageConfig montage() {
            return MontageConfig.defaults();
        }

        @Override
        public List<CullCategory> categories() {
            return List.of();
        }

        @Override
        public CullProviderSettings providerSettings() {
            return CullProviderSettings.unset();
        }

        @Override
        public CullProviderSettings providerSettings(final String providerId) {
            return CullProviderSettings.unset();
        }

        @Override
        public ExternalAgentSettings externalAgent() {
            return new ExternalAgentSettings(WatchMode.MANUAL);
        }
    }

    private static final class RecordingCuller implements VisionCuller {

        private final String id;
        private final ProviderType type;
        private final CullReport report = CullReport.nothingSpent("anthropic", 0);
        private @Nullable PrepDir receivedPrep;
        private @Nullable CullOptions receivedOptions;
        private @Nullable ProgressCallback receivedProgress;

        private RecordingCuller(final String id) {
            this(id, ProviderType.API);
        }

        private RecordingCuller(final String id, final ProviderType type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public VisionProviderDescriptor describe() {
            return PipelineTestSupport.describing(this.id);
        }

        @Override
        public ProviderType type() {
            return this.type;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.Counted(7);
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
        public VisionProviderDescriptor describe() {
            return PipelineTestSupport.describing(this.id);
        }

        @Override
        public ProviderType type() {
            return ProviderType.API;
        }

        @Override
        public ProviderCheck check() {
            return new ProviderCheck.NotApplicable();
        }

        @Override
        public SpendForecast forecast(final PrepDir prep) {
            return new SpendForecast.NoSpend();
        }

        @Override
        public CullReport cull(final PrepDir prep, final CullOptions opts) throws CullException {
            throw new CullException("shards missing");
        }
    }
}
