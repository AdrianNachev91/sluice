package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.MontageConfig;

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
        final Settings settings = SettingsHolder.boundSettings(new PathsProperties("repo", "library", "inbox"),
                siftConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        assertThat(settings.montage().tileSize()).isEqualTo(224);
        assertThat(settings.montage().tilesPerRow()).isEqualTo(5);
    }

    @Test
    void theBoundValuesAreWhatTheAppStartsOn() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                siftConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        assertThat(holder.current().paths()).isEqualTo(PATHS);
        assertThat(holder.provider()).isEqualTo("external-agent");
        assertThat(holder.categories()).containsExactly(SiftCategory.of("scenery", "scenery description"));
        assertThat(holder.providerSettings().model()).isNull();
    }

    @Test
    void theActiveSetLeavesOutTheCardsSwitchedOffAndTheWholeSetKeepsThem() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                siftConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        holder.apply(new Settings(PATHS, "anthropic", Map.of(),
                List.of(SiftCategory.of("scenery", "scenery description"),
                        new SiftCategory("food", "food description", List.of(), Boolean.FALSE),
                        SiftCategory.of("funny", "funny description")),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM));

        assertThat(holder.activeCategories()).extracting(SiftCategory::name)
                .containsExactly("scenery", "funny", "junk");
        assertThat(holder.categories()).extracting(SiftCategory::name)
                .containsExactly("scenery", "food", "funny");
    }

    @Test
    void junkJoinsTheActiveSetLastAndNeverTheConfiguredOne() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                siftConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        holder.apply(new Settings(PATHS, "anthropic", Map.of(), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM));

        assertThat(holder.activeCategories()).extracting(SiftCategory::name).containsExactly("junk");
        assertThat(holder.categoriesForRepair()).extracting(SiftCategory::name).containsExactly("junk");
        assertThat(holder.categories()).isEmpty();
    }

    @Test
    void everyValueItServesComesFromTheLastSave() {
        final var holder = new SettingsHolder(new PathsProperties("repo", "library", "inbox"),
                siftConfig(), new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM));

        holder.apply(new Settings(PATHS, "anthropic", Map.of("anthropic", new SiftProviderSettings("claude-sonnet-5", null, null)),
                List.of(SiftCategory.of("food", "food description")),
                new MontageConfig(96, 7), ThemeChoice.DARK));

        assertThat(holder.provider()).isEqualTo("anthropic");
        assertThat(holder.providerSettings().model()).isEqualTo("claude-sonnet-5");
        assertThat(holder.categories()).containsExactly(SiftCategory.of("food", "food description"));
        assertThat(holder.montage()).isEqualTo(new MontageConfig(96, 7));
    }

    // Two cards under one name would silently alias a category. Binding accepts the list, and this
    // is the step that reads it.
    @Test
    void refusesTwoCategoryCardsSharingAName() {
        final var sift = new SiftConfig("external-agent", Map.of(),
                List.of(SiftCategory.of("receipts", "paper receipts"),
                        SiftCategory.of("receipts", "till slips")));

        assertThatThrownBy(() -> SettingsHolder.boundSettings(new PathsProperties("repo", "library", "inbox"),
                sift, new MontageProperties(224, 5), new UiProperties(ThemeChoice.SYSTEM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receipts");
    }

    // Two cards under one name are refused when the bound list is read, and that read happens while
    // the context comes up. So a config file naming one category twice stops the app rather than
    // letting it half-sift.
    @Test
    void aConfigFileNamingOneCategoryTwiceStopsTheAppStarting() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SettingsContext.class)
                .withPropertyValues(
                        "sluice.sift.categories[0].name=receipts",
                        "sluice.sift.categories[0].description=Paper receipts and invoices",
                        "sluice.sift.categories[1].name=receipts",
                        "sluice.sift.categories[1].description=Photos of till slips")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("receipts"));
    }

    private static SiftConfig siftConfig() {
        return new SiftConfig("external-agent", Map.of(),
                List.of(SiftCategory.of("scenery", "scenery description")));
    }

    // Every properties class SettingsHolder takes. One missing makes the context fail to build, and
    // a test asserting that it failed then passes without ever reaching what it meant to check.
    @Configuration
    @EnableConfigurationProperties({PathsProperties.class, SiftConfig.class, MontageProperties.class,
            UiProperties.class})
    @Import(SettingsHolder.class)
    static class SettingsContext {
    }
}
