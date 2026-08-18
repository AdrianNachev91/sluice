package photos.sluice.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import photos.sluice.application.port.out.SettingOverride;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentSettingsSourcesTest {

    private static final String LIBRARY_ROOT = "sluice.paths.library-root";

    @Nested
    class TheBoundary {

        @Test
        void reportsASourceSittingAboveTheConfigFile() {
            final var sources = new EnvironmentSettingsSources(
                    environmentWith(plain("commandLineArgs", LIBRARY_ROOT), configFile(LIBRARY_ROOT)));

            assertThat(sources.overriddenAboveTheConfigFile(LIBRARY_ROOT))
                    .contains(new SettingOverride.ByAnotherSource(LIBRARY_ROOT, "A command-line argument"));
        }

        @Test
        void namesASourceInWordsRatherThanBySpringsIdentifier() {
            assertThat(this.describing("systemProperties")).isEqualTo("A Java system property");
            assertThat(this.describing("systemEnvironment")).isEqualTo("An environment variable");
            assertThat(this.describing("Config resource 'class path resource [application.yml]'"))
                    .isEqualTo("Something outside your settings file");
        }

        private String describing(final String sourceName) {
            final var sources = new EnvironmentSettingsSources(
                    environmentWith(plain(sourceName, LIBRARY_ROOT), configFile(LIBRARY_ROOT)));
            return ((SettingOverride.ByAnotherSource) sources.overriddenAboveTheConfigFile(LIBRARY_ROOT)
                    .orElseThrow()).source();
        }

        @Test
        void reportsNothingWhenOnlyAConfigFileSuppliesIt() {
            final var sources = new EnvironmentSettingsSources(
                    environmentWith(configFile(LIBRARY_ROOT), configFile(LIBRARY_ROOT)));

            assertThat(sources.overriddenAboveTheConfigFile(LIBRARY_ROOT)).isEmpty();
        }

        @Test
        void reportsNothingWhenASourceBelowTheConfigFileSuppliesIt() {
            final var sources = new EnvironmentSettingsSources(environmentWith(
                    configFile("sluice.cull.provider"), plain("belowEverything", LIBRARY_ROOT)));

            assertThat(sources.overriddenAboveTheConfigFile(LIBRARY_ROOT)).isEmpty();
        }

        @Test
        void reportsNothingWhenNothingSuppliesItAtAll() {
            final var sources = new EnvironmentSettingsSources(
                    environmentWith(plain("commandLineArgs", "sluice.cull.provider"), configFile(LIBRARY_ROOT)));

            assertThat(sources.overriddenAboveTheConfigFile("sluice.paths.inbox")).isEmpty();
        }

        @Test
        void reportsTheHighestSourceWhenSeveralAboveTheFileSupplyIt() {
            final var sources = new EnvironmentSettingsSources(environmentWith(
                    plain("commandLineArgs", LIBRARY_ROOT), plain("systemProperties", LIBRARY_ROOT),
                    configFile(LIBRARY_ROOT)));

            assertThat(sources.overriddenAboveTheConfigFile(LIBRARY_ROOT))
                    .contains(new SettingOverride.ByAnotherSource(LIBRARY_ROOT, "A command-line argument"));
        }
    }

    // PATH is the one variable every platform this ships on defines, so it is what a real context
    // can be asked about without arranging anything.
    @Nested
    @SpringBootTest
    class AgainstTheRunningApp {

        @Autowired
        private EnvironmentSettingsSources sources;

        @Test
        void namesTheEnvironmentVariableBehindAProperty() {
            assertThat(this.sources.overriddenAboveTheConfigFile("path"))
                    .get()
                    .isInstanceOfSatisfying(SettingOverride.ByEnvironmentVariable.class,
                            override -> assertThat(override.variableName().toLowerCase(Locale.ROOT))
                                    .isEqualTo("path"));
        }

        @Test
        void reportsNothingForASettingNobodyHasOverridden() {
            assertThat(this.sources.overriddenAboveTheConfigFile("sluice.montage.tile-size"))
                    .withFailMessage("Expected no override, so this machine has SLUICE_MONTAGE_TILE_SIZE "
                            + "set (or the property passed on the command line). The query is answering "
                            + "correctly; the test's premise is what is untrue here.")
                    .isEmpty();
        }
    }

    private static ConfigurableEnvironment environmentWith(final MapPropertySource... sources) {
        final var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (int i = sources.length - 1; i >= 0; i--) {
            environment.getPropertySources().addFirst(sources[i]);
        }
        return environment;
    }

    private static MapPropertySource plain(final String name, final String property) {
        return new MapPropertySource(name, Map.of(property, "from-" + name));
    }

    private static MapPropertySource configFile(final String property) {
        return new OriginTrackedMapPropertySource("Config resource 'file [config.yml]'",
                Map.of(property, "from-a-config-file"));
    }
}
