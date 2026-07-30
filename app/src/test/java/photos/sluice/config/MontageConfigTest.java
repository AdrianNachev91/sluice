package photos.sluice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MontageConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfig.class);

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("sluice.montage.tiles-per-row");
    }

    @Test
    void bundledDefaultsBindFromApplicationYml() {
        this.runner.run(context -> {
            final MontageConfig config = context.getBean(MontageConfig.class);
            assertThat(config.tilesPerRow()).isEqualTo(5);
            assertThat(config.tileSize()).isEqualTo(224);
        });
    }

    @Test
    void explicitPropertyOverridesBundledDefault() {
        this.runner.withPropertyValues("sluice.montage.tiles-per-row=7")
                .run(context -> {
                    final MontageConfig config = context.getBean(MontageConfig.class);
                    assertThat(config.tilesPerRow()).isEqualTo(7);
                });
    }

    @Test
    void importedUserFileOverridesBundledDefault(@TempDir final Path tmp) throws IOException {
        final Path userFile = tmp.resolve("config.yml");
        Files.writeString(userFile, """
                sluice:
                  montage:
                    tiles-per-row: 7
                """);

        this.runner.withPropertyValues("spring.config.import=optional:file:" + userFile)
                .run(context -> {
                    final MontageConfig config = context.getBean(MontageConfig.class);
                    assertThat(config.tilesPerRow()).isEqualTo(7);
                    assertThat(config.tileSize()).isEqualTo(224);
                });
    }

    @Test
    void systemPropertyProxyForEnvVarOverridesImportedUserFile(@TempDir final Path tmp) throws IOException {
        final Path userFile = tmp.resolve("config.yml");
        Files.writeString(userFile, """
                sluice:
                  montage:
                    tiles-per-row: 7
                """);
        System.setProperty("sluice.montage.tiles-per-row", "9");

        this.runner.withPropertyValues("spring.config.import=optional:file:" + userFile)
                .run(context -> {
                    final MontageConfig config = context.getBean(MontageConfig.class);
                    assertThat(config.tilesPerRow()).isEqualTo(9);
                });
    }

    @Configuration
    @EnableConfigurationProperties(MontageConfig.class)
    static class TestConfig {
    }
}
