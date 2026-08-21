package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsHolderTest {

    private static final PathSettings PATHS = new PathSettings("repo", "library", "inbox");

    // Distinct values per field, so this fails if a future reordering of either record's fields
    // turns the mapping into a silent tileSize/tilesPerRow swap.
    @Test
    void theBoundGridMapsFieldsByNameNotPosition() {
        final Settings settings = SettingsHolder.bound(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        assertThat(settings.montage().tileSize()).isEqualTo(224);
        assertThat(settings.montage().tilesPerRow()).isEqualTo(5);
    }

    @Test
    void theBoundValuesAreWhatTheAppStartsOn() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        assertThat(holder.current().paths()).isEqualTo(PATHS);
        assertThat(holder.provider()).isEqualTo("external-agent");
        assertThat(holder.categories()).containsExactly(CullCategory.of("junk", "junk description"));
        assertThat(holder.externalAgent().mode()).isEqualTo(WatchMode.MANUAL);
        assertThat(holder.providerSettings().model()).isNull();
    }

    @Test
    void theActiveSetLeavesOutTheCardsSwitchedOffAndTheWholeSetKeepsThem() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        holder.apply(new Settings(PATHS, "anthropic", Map.of(),
                List.of(CullCategory.of("junk", "junk description"),
                        new CullCategory("food", "food description", List.of(), Boolean.FALSE),
                        CullCategory.of("funny", "funny description")),
                new ExternalAgentSettings(WatchMode.WATCH), new MontageConfig(224, 5), ThemeChoice.SYSTEM));

        assertThat(holder.activeCategories()).extracting(CullCategory::name)
                .containsExactly("junk", "funny");
        assertThat(holder.categories()).extracting(CullCategory::name)
                .containsExactly("junk", "food", "funny");
    }

    @Test
    void everyValueItServesComesFromTheLastSave() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                cullConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        holder.apply(new Settings(PATHS, "anthropic", Map.of("anthropic", new CullProviderSettings("claude-sonnet-5", null, null)),
                List.of(CullCategory.of("food", "food description")),
                new ExternalAgentSettings(WatchMode.WATCH), new MontageConfig(96, 7), ThemeChoice.DARK));

        assertThat(holder.provider()).isEqualTo("anthropic");
        assertThat(holder.providerSettings().model()).isEqualTo("claude-sonnet-5");
        assertThat(holder.categories()).containsExactly(CullCategory.of("food", "food description"));
        assertThat(holder.externalAgent().mode()).isEqualTo(WatchMode.WATCH);
        assertThat(holder.montage()).isEqualTo(new MontageConfig(96, 7));
    }

    // Two cards under one name would silently alias a category. Binding accepts the list, and this
    // is the step that reads it.
    @Test
    void refusesTwoCategoryCardsSharingAName() {
        final var cull = new CullConfig("external-agent", Map.of(),
                List.of(CullCategory.of("receipts", "paper receipts"),
                        CullCategory.of("receipts", "till slips")),
                new ExternalAgentSettings(WatchMode.MANUAL));

        assertThatThrownBy(() -> SettingsHolder.bound(new PathsProperties("repo", "library", "inbox"),
                cull, new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM)))
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
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("receipts"));
    }

    private static CullConfig cullConfig() {
        return new CullConfig("external-agent", Map.of(),
                List.of(CullCategory.of("junk", "junk description")), new ExternalAgentSettings(WatchMode.MANUAL));
    }

    // Every properties class SettingsHolder takes. One missing makes the context fail to build, and
    // a test asserting that it failed then passes without ever reaching what it meant to check.
    @Configuration
    @EnableConfigurationProperties({PathsProperties.class, CullConfig.class, MontageProperties.class,
            UiProperties.class})
    @Import(SettingsHolder.class)
    static class SettingsContext {
    }
}
