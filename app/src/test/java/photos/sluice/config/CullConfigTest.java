package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import photos.sluice.application.port.out.CullCategory;

import static org.assertj.core.api.Assertions.assertThat;

class CullConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @Test
    void bundledDefaultProviderIsExternalAgent() {
        runner.run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.provider()).isEqualTo("external-agent");
            // With sluice.cull.provider-settings.* absent, the settings object still exists (the
            // port promises never-null) and each field reports unset as null.
            assertThat(config.providerSettings().model()).isNull();
            assertThat(config.providerSettings().endpoint()).isNull();
        });
    }

    @Test
    void explicitPropertyOverridesProvider() {
        runner.withPropertyValues("sluice.cull.provider=anthropic")
                .run(context -> {
                    CullConfig config = context.getBean(CullConfig.class);
                    assertThat(config.provider()).isEqualTo("anthropic");
                });
    }

    @Test
    void bundledDefaultCategoriesAreTheStandardFourCards() {
        runner.run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).extracting(CullCategory::name)
                    .containsExactly("junk", "scenery", "food", "funny");
        });
    }

    @Test
    void bundledJunkCardKeepsThePhotoOfAScreenEmphasis() {
        // Photos of screens are the single most-missed junk class; the bundled card's description
        // is what teaches an automated provider to catch them. Guard the phrase so a future
        // rewording of the defaults can't silently drop the emphasis.
        runner.run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories().getFirst().description()).contains("photo of a screen");
        });
    }

    @Test
    void explicitPropertiesOverrideCategories() {
        runner.withPropertyValues(
                "sluice.cull.categories[0].name=receipts",
                "sluice.cull.categories[0].description=Paper receipts and invoices",
                "sluice.cull.categories[1].name=pets",
                "sluice.cull.categories[1].description=Photos of the family dog"
        ).run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).containsExactly(
                    new CullCategory("receipts", "Paper receipts and invoices"),
                    new CullCategory("pets", "Photos of the family dog"));
        });
    }

    @Test
    void rejectsTwoCardsSharingAName() {
        runner.withPropertyValues(
                "sluice.cull.categories[0].name=receipts",
                "sluice.cull.categories[0].description=Paper receipts and invoices",
                "sluice.cull.categories[1].name=receipts",
                "sluice.cull.categories[1].description=Photos of till slips"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void providerSettingsBindWhenBothFieldsPresent() {
        runner.withPropertyValues(
                "sluice.cull.provider=anthropic",
                "sluice.cull.provider-settings.model=claude-sonnet-5",
                "sluice.cull.provider-settings.endpoint=https://api.anthropic.com"
        ).run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.providerSettings().model()).isEqualTo("claude-sonnet-5");
            assertThat(config.providerSettings().endpoint()).isEqualTo("https://api.anthropic.com");
        });
    }

    @Configuration
    @EnableConfigurationProperties(CullConfig.class)
    static class TestConfig {
    }
}
