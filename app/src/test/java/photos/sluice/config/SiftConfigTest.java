package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.domain.sift.SiftCategory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class SiftConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void bundledDefaultProviderIsExternalAgent() {
        this.runner.run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.provider()).isEqualTo("external-agent");
            assertThat(config.providerSettings()).isEmpty();
        });
    }

    @Test
    void explicitPropertyOverridesProvider() {
        this.runner.withPropertyValues("sluice.sift.provider=anthropic")
                .run(context -> {
                    final SiftConfig config = context.getBean(SiftConfig.class);
                    assertThat(config.provider()).isEqualTo("anthropic");
                });
    }

    @Test
    void bundledDefaultCategoriesAreTheStandardThreeCards() {
        this.runner.run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories()).extracting(SiftCategory::name)
                    .containsExactly("scenery", "food", "funny");
        });
    }

    @Test
    void explicitPropertiesOverrideCategories() {
        this.runner.withPropertyValues(
                "sluice.sift.categories[0].name=receipts",
                "sluice.sift.categories[0].description=Paper receipts and invoices",
                "sluice.sift.categories[1].name=pets",
                "sluice.sift.categories[1].description=Photos of the family dog"
        ).run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories()).containsExactly(
                    SiftCategory.of("receipts", "Paper receipts and invoices"),
                    SiftCategory.of("pets", "Photos of the family dog"));
        });
    }

    @Test
    void everyBundledCardShipsSwitchedOn() {
        this.runner.run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories()).allMatch(SiftCategory::enabled);
        });
    }

    @Test
    void aCardCanBeSwitchedOffFromTheConfigFile() {
        this.runner.withPropertyValues(
                "sluice.sift.categories[0].name=pets",
                "sluice.sift.categories[0].description=Photos of the family dog",
                "sluice.sift.categories[0].enabled=false"
        ).run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories().getFirst().enabled()).isFalse();
        });
    }

    @Test
    void aCardWithNoEnabledKeyIsOn() {
        this.runner.withPropertyValues(
                "sluice.sift.categories[0].name=pets",
                "sluice.sift.categories[0].description=Photos of the family dog"
        ).run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories().getFirst().enabled()).isTrue();
        });
    }

    @Test
    void aCardsExamplesBindAsWrittenAndDropTheBlankOnes() {
        this.runner.withPropertyValues(
                "sluice.sift.categories[0].name=food",
                "sluice.sift.categories[0].description=Food and meal photos",
                "sluice.sift.categories[0].examples[0]=restaurant plates",
                "sluice.sift.categories[0].examples[1]=   ",
                "sluice.sift.categories[0].examples[2]=home dinners"
        ).run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.categories().getFirst().examples())
                    .containsExactly("restaurant plates", "home dinners");
        });
    }

    @Test
    void providerSettingsBindWhenAllFieldsPresent() {
        this.runner.withPropertyValues(
                "sluice.sift.provider=anthropic",
                "sluice.sift.provider-settings.anthropic.model=claude-sonnet-5",
                "sluice.sift.provider-settings.anthropic.endpoint=https://api.anthropic.com",
                "sluice.sift.provider-settings.anthropic.max-retries=5"
        ).run(context -> {
            final SiftConfig config = context.getBean(SiftConfig.class);
            assertThat(config.providerSettings()).containsExactly(entry("anthropic",
                    new SiftProviderSettings("claude-sonnet-5", "https://api.anthropic.com", 5)));
        });
    }

    @Test
    void aProviderWhoseOnlyValueIsBlankStillBindsAsItsOwnEntry() {
        this.runner.withPropertyValues("sluice.sift.provider-settings.anthropic.model=")
                .run(context -> {
                    final SiftConfig config = context.getBean(SiftConfig.class);
                    assertThat(config.providerSettings()).containsOnlyKeys("anthropic");
                    assertThat(config.providerSettings().get("anthropic").endpoint()).isNull();
                });
    }

    // Provider ids carry hyphens, and Spring's relaxed binding is what has to leave a map key
    // alone rather than reading it as a word boundary.
    @Test
    void aHyphenatedProviderIdSurvivesAsItsOwnKey() {
        this.runner.withPropertyValues("sluice.sift.provider-settings.external-agent.model=their-model")
                .run(context -> {
                    final SiftConfig config = context.getBean(SiftConfig.class);
                    assertThat(config.providerSettings()).containsOnlyKeys("external-agent");
                });
    }

    @Configuration
    @EnableConfigurationProperties(SiftConfig.class)
    static class TestConfig {
    }
}
