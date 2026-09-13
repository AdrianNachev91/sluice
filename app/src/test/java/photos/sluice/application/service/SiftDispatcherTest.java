package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SiftException;
import photos.sluice.application.port.out.SiftOptions;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.SiftSettings;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.VisionSieve;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.MontageConfig;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiftDispatcherTest {

    private static final PrepDir PREP =
            new PrepDir("2019", List.of(SiftCategory.of("junk", "objectively worthless")), Path.of("base"), 0,
                    List.of(), 0, Path.of("prep"), List.of());
    private static final SiftOptions OPTIONS = SiftOptions.unbounded(false);

    @Test
    void routesToTheSieveMatchingTheConfiguredProvider() throws SiftException {
        final var target = new RecordingSieve("anthropic");
        final var other = new RecordingSieve("external-agent");
        final var dispatcher = new SiftDispatcher(List.of(other, target), settingsFor("anthropic"));

        final SiftReport report = dispatcher.sift(PREP, OPTIONS);

        assertThat(target.receivedPrep).isSameAs(PREP);
        assertThat(target.receivedOptions).isSameAs(OPTIONS);
        assertThat(report).isSameAs(target.report);
        assertThat(other.receivedPrep).isNull();
    }

    // Two registered sieves whose types differ, so answering off the wrong one is a visible
    // failure rather than a coincidence.
    @Test
    void answersTheTypeOfTheSieveTheConfiguredProviderNames() {
        final List<VisionSieve> sieves = List.of(new RecordingSieve("waits-for-a-person", ProviderType.MANUAL),
                new RecordingSieve("calls-a-model", ProviderType.API));

        final var waiting = new SiftDispatcher(sieves, settingsFor("waits-for-a-person"));
        assertThat(waiting.configuredProviderIs(ProviderType.MANUAL)).isTrue();
        assertThat(waiting.configuredProviderIs(ProviderType.API)).isFalse();

        final var calling = new SiftDispatcher(sieves, settingsFor("calls-a-model"));
        assertThat(calling.configuredProviderIs(ProviderType.API)).isTrue();
        assertThat(calling.configuredProviderIs(ProviderType.MANUAL)).isFalse();
    }

    @Test
    void answersNoForAProviderNoRegisteredSieveClaims() {
        final var dispatcher = new SiftDispatcher(
                List.of(new RecordingSieve("calls-a-model", ProviderType.API)), settingsFor("nothing-registered"));

        assertThat(dispatcher.configuredProviderIs(ProviderType.API)).isFalse();
        assertThat(dispatcher.configuredProviderIs(ProviderType.MANUAL)).isFalse();
    }

    @Test
    void failsLoudWhenNoSieveMatchesTheProvider() {
        final var dispatcher = new SiftDispatcher(List.of(new RecordingSieve("anthropic")), settingsFor("ollama"));

        assertThatThrownBy(() -> dispatcher.sift(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
    }

    @Test
    void failsLoudWhenNoSievesAreRegisteredAtAll() {
        final var dispatcher = new SiftDispatcher(List.of(), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.sift(PREP, OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external-agent");
    }

    @Test
    void propagatesTheSiftExceptionFromTheSelectedSieve() {
        final var dispatcher = new SiftDispatcher(
                List.of(new ThrowingSieve("external-agent")), settingsFor("external-agent"));

        assertThatThrownBy(() -> dispatcher.sift(PREP, OPTIONS))
                .isInstanceOf(SiftException.class)
                .hasMessage("shards missing");
    }

    @Test
    void resolvesTheProviderFreshOnEachCall() throws SiftException {
        final var external = new RecordingSieve("external-agent");
        final var anthropic = new RecordingSieve("anthropic");
        final var settings = new MutableSettings("external-agent");
        final var dispatcher = new SiftDispatcher(List.of(external, anthropic), settings);

        dispatcher.sift(PREP, OPTIONS);
        settings.provider = "anthropic";
        dispatcher.sift(PREP, OPTIONS);

        assertThat(external.receivedPrep).isSameAs(PREP);
        assertThat(anthropic.receivedPrep).isSameAs(PREP);
    }

    @Test
    void routesTheProgressCallbackToTheSelectedSieve() throws SiftException {
        final var target = new RecordingSieve("anthropic");
        final var dispatcher = new SiftDispatcher(List.of(target), settingsFor("anthropic"));
        final ProgressCallback progress = (_, _) -> {
        };

        dispatcher.sift(PREP, OPTIONS, progress);

        assertThat(target.receivedProgress).isSameAs(progress);
    }

    @Test
    void failsLoudWhenTwoSievesShareAnId() {
        assertThatThrownBy(() -> new SiftDispatcher(
                List.of(new RecordingSieve("dup"), new RecordingSieve("dup")), settingsFor("dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    private static SiftSettings settingsFor(final String provider) {
        return new MutableSettings(provider);
    }

    private static final class MutableSettings implements SiftSettings {

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
        public List<SiftCategory> categories() {
            return List.of();
        }

        @Override
        public SiftProviderSettings providerSettings() {
            return SiftProviderSettings.unset();
        }

        @Override
        public SiftProviderSettings providerSettings(final String providerId) {
            return SiftProviderSettings.unset();
        }
    }

    private static final class RecordingSieve implements VisionSieve {

        private final String id;
        private final ProviderType type;
        private final SiftReport report = SiftReport.nothingSpent("anthropic", 0);
        private @Nullable PrepDir receivedPrep;
        private @Nullable SiftOptions receivedOptions;
        private @Nullable ProgressCallback receivedProgress;

        private RecordingSieve(final String id) {
            this(id, ProviderType.API);
        }

        private RecordingSieve(final String id, final ProviderType type) {
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
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) {
            this.receivedPrep = prep;
            this.receivedOptions = opts;
            return this.report;
        }

        @Override
        public SiftReport sift(final PrepDir prep, final SiftOptions opts, final ProgressCallback progress) {
            this.receivedProgress = progress;
            return this.sift(prep, opts);
        }
    }

    private record ThrowingSieve(String id) implements VisionSieve {

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
        public SiftReport sift(final PrepDir prep, final SiftOptions opts) throws SiftException {
            throw new SiftException("shards missing");
        }
    }
}
