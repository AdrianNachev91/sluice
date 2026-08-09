package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UiLauncherTest {

    @Test
    void springArgsImportsTheConfigFile() {
        final Path configFile = Path.of("somewhere", "config.yml");

        final String[] args = UiLauncher.springArgs(configFile, new String[0]);

        assertThat(args).containsExactly("--spring.config.import=optional:file:" + configFile);
    }

    // The file is optional on purpose. A fresh install has none, and the app has to start anyway.
    @Test
    void springArgsImportsTheConfigFileOptionally() {
        final String[] args = UiLauncher.springArgs(Path.of("config.yml"), new String[0]);

        assertThat(args[0]).contains("optional:");
    }

    @Test
    void springArgsKeepsTheCallersOwnArgumentsAfterTheImport() {
        final String[] args = UiLauncher.springArgs(Path.of("config.yml"),
                new String[]{"--sluice.paths.inbox=/photos", "--debug"});

        assertThat(args).containsExactly("--spring.config.import=optional:file:config.yml",
                "--sluice.paths.inbox=/photos", "--debug");
    }
}
