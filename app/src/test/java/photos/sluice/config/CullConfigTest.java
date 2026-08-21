package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.job.WatchMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class CullConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void bundledDefaultProviderIsExternalAgent() {
        this.runner.run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.provider()).isEqualTo("external-agent");
            assertThat(config.providerSettings()).isEmpty();
        });
    }

    @Test
    void explicitPropertyOverridesProvider() {
        this.runner.withPropertyValues("sluice.cull.provider=anthropic")
                .run(context -> {
                    final CullConfig config = context.getBean(CullConfig.class);
                    assertThat(config.provider()).isEqualTo("anthropic");
                });
    }

    @Test
    void bundledDefaultCategoriesAreTheStandardFourCards() {
        this.runner.run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).extracting(CullCategory::name)
                    .containsExactly("junk", "scenery", "food", "funny");
        });
    }

    @Test
    void bundledJunkCardKeepsThePhotoOfAScreenEmphasis() {
        // Photos of screens are the single most-missed junk class; the bundled card's description
        // is what teaches an automated provider to catch them. Guard the phrase so a future
        // rewording of the defaults can't silently drop the emphasis.
        this.runner.run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories().getFirst().description()).contains("photo of a screen");
        });
    }

    @Test
    void explicitPropertiesOverrideCategories() {
        this.runner.withPropertyValues(
                "sluice.cull.categories[0].name=receipts",
                "sluice.cull.categories[0].description=Paper receipts and invoices",
                "sluice.cull.categories[1].name=pets",
                "sluice.cull.categories[1].description=Photos of the family dog"
        ).run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).containsExactly(
                    CullCategory.of("receipts", "Paper receipts and invoices"),
                    CullCategory.of("pets", "Photos of the family dog"));
        });
    }

    @Test
    void everyBundledCardShipsSwitchedOn() {
        this.runner.run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).allMatch(CullCategory::enabled);
        });
    }

    @Test
    void aCardCanBeSwitchedOffFromTheConfigFile() {
        this.runner.withPropertyValues(
                "sluice.cull.categories[0].name=pets",
                "sluice.cull.categories[0].description=Photos of the family dog",
                "sluice.cull.categories[0].enabled=false"
        ).run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories().getFirst().enabled()).isFalse();
        });
    }

    @Test
    void aCardWithNoEnabledKeyIsOn() {
        this.runner.withPropertyValues(
                "sluice.cull.categories[0].name=pets",
                "sluice.cull.categories[0].description=Photos of the family dog"
        ).run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories().getFirst().enabled()).isTrue();
        });
    }

    @Test
    void aCardsExamplesBindAsWrittenAndDropTheBlankOnes() {
        this.runner.withPropertyValues(
                "sluice.cull.categories[0].name=food",
                "sluice.cull.categories[0].description=Food and meal photos",
                "sluice.cull.categories[0].examples[0]=restaurant plates",
                "sluice.cull.categories[0].examples[1]=   ",
                "sluice.cull.categories[0].examples[2]=home dinners"
        ).run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories().getFirst().examples())
                    .containsExactly("restaurant plates", "home dinners");
        });
    }

    @Test
    void externalAgentDefaultsToWatchModeWhenAbsent() {
        this.runner.run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.externalAgent().mode()).isEqualTo(WatchMode.WATCH);
        });
    }

    @Test
    void providerSettingsBindWhenAllFieldsPresent() {
        this.runner.withPropertyValues(
                "sluice.cull.provider=anthropic",
                "sluice.cull.provider-settings.anthropic.model=claude-sonnet-5",
                "sluice.cull.provider-settings.anthropic.endpoint=https://api.anthropic.com",
                "sluice.cull.provider-settings.anthropic.max-retries=5"
        ).run(context -> {
            final CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.providerSettings()).containsExactly(entry("anthropic",
                    new CullProviderSettings("claude-sonnet-5", "https://api.anthropic.com", 5)));
        });
    }

    @Test
    void aProviderWhoseOnlyValueIsBlankStillBindsAsItsOwnEntry() {
        this.runner.withPropertyValues("sluice.cull.provider-settings.anthropic.model=")
                .run(context -> {
                    final CullConfig config = context.getBean(CullConfig.class);
                    assertThat(config.providerSettings()).containsOnlyKeys("anthropic");
                    assertThat(config.providerSettings().get("anthropic").endpoint()).isNull();
                });
    }

    // Provider ids carry hyphens, and Spring's relaxed binding is what has to leave a map key
    // alone rather than reading it as a word boundary.
    @Test
    void aHyphenatedProviderIdSurvivesAsItsOwnKey() {
        this.runner.withPropertyValues("sluice.cull.provider-settings.external-agent.model=their-model")
                .run(context -> {
                    final CullConfig config = context.getBean(CullConfig.class);
                    assertThat(config.providerSettings()).containsOnlyKeys("external-agent");
                });
    }

    @Configuration
    @EnableConfigurationProperties(CullConfig.class)
    static class TestConfig {
    }
}
