package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsHolderTest {

    private static final PathSettings PATHS = new PathSettings("repo", "library", "inbox");

    // Distinct values per field, so this fails if a future reordering of either record's fields
    // turns the mapping into a silent tileSize/tilesPerRow swap.
    @Test
    void theBoundGridMapsFieldsByNameNotPosition() {
        final Settings settings = SettingsHolder.bound(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5));

        assertThat(settings.montage().tileSize()).isEqualTo(224);
        assertThat(settings.montage().tilesPerRow()).isEqualTo(5);
    }

    @Test
    void theBoundValuesAreWhatTheAppStartsOn() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5));

        assertThat(holder.current().paths()).isEqualTo(PATHS);
        assertThat(holder.provider()).isEqualTo("external-agent");
        assertThat(holder.categories()).containsExactly(new CullCategory("junk", "junk description"));
        assertThat(holder.externalAgent().mode()).isEqualTo(WatchMode.MANUAL);
        assertThat(holder.providerSettings().model()).isNull();
    }

    @Test
    void everyValueItServesComesFromTheLastSave() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5));

        holder.apply(new Settings(PATHS, "anthropic", new CullProviderSettings("claude-sonnet-5", null, null, null),
                List.of(new CullCategory("food", "food description")),
                new ExternalAgentSettings(WatchMode.WATCH), new MontageConfig(96, 7)));

        assertThat(holder.provider()).isEqualTo("anthropic");
        assertThat(holder.providerSettings().model()).isEqualTo("claude-sonnet-5");
        assertThat(holder.categories()).containsExactly(new CullCategory("food", "food description"));
        assertThat(holder.externalAgent().mode()).isEqualTo(WatchMode.WATCH);
        assertThat(holder.montage()).isEqualTo(new MontageConfig(96, 7));
    }

    // Two cards under one name would silently alias a category. Binding accepts the list, and this
    // is the step that reads it.
    @Test
    void refusesTwoCategoryCardsSharingAName() {
        final var cull = new CullConfig("external-agent", new CullProviderSettings(null, null, null, null),
                List.of(new CullCategory("receipts", "paper receipts"),
                        new CullCategory("receipts", "till slips")),
                new ExternalAgentSettings(WatchMode.MANUAL));

        assertThatThrownBy(() -> SettingsHolder.bound(new PathsProperties("repo", "library", "inbox"),
                cull, new MontageProperties(224, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipts");
    }

    // Two cards under one name are refused when the bound list is read, and that read happens while
    // the context comes up. So a config file naming one category twice stops the app rather than
    // letting it half-cull.
    @Test
    void aConfigFileNamingOneCategoryTwiceStopsTheAppStarting() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SettingsContext.class)
                .withPropertyValues(
                        "sluice.cull.categories[0].name=receipts",
                        "sluice.cull.categories[0].description=Paper receipts and invoices",
                        "sluice.cull.categories[1].name=receipts",
                        "sluice.cull.categories[1].description=Photos of till slips")
                .run(context -> assertThat(context).hasFailed());
    }

    private static CullConfig cullConfig() {
        return new CullConfig("external-agent", new CullProviderSettings(null, null, null, null),
                List.of(new CullCategory("junk", "junk description")), new ExternalAgentSettings(WatchMode.MANUAL));
    }

    @Configuration
    @EnableConfigurationProperties({PathsProperties.class, CullConfig.class, MontageProperties.class})
    @Import(SettingsHolder.class)
    static class SettingsContext {
    }
}
