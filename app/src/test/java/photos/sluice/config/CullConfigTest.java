package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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
            assertThat(config.providerSettings()).isNull();
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
    void bundledDefaultCategoriesAreTheStandardFour() {
        runner.run(context -> {
            CullConfig config = context.getBean(CullConfig.class);
            assertThat(config.categories()).containsExactly("junk", "scenery", "food", "funny");
        });
    }

    @Test
    void explicitPropertyOverridesCategories() {
        runner.withPropertyValues("sluice.cull.categories=junk,receipts,pets")
                .run(context -> {
                    CullConfig config = context.getBean(CullConfig.class);
                    assertThat(config.categories()).containsExactly("junk", "receipts", "pets");
                });
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
