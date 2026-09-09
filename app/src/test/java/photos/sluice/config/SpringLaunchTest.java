package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpringLaunchTest {

    @Test
    void theConfigFileIsImported() {
        final Path configFile = Path.of("somewhere", "config.yml");

        final String[] args = SpringLaunch.argsWithConfigImport(configFile, new String[0]);

        assertThat(args).containsExactly("--spring.config.import=optional:file:" + configFile);
    }

    // The file is optional on purpose. A fresh install has none, and the app has to start anyway.
    @Test
    void theImportIsOptional() {
        final String[] args = SpringLaunch.argsWithConfigImport(Path.of("config.yml"), new String[0]);

        assertThat(args[0]).contains("optional:");
    }

    @Test
    void theCallersOwnArgumentsComeAfterTheImport() {
        final String[] args = SpringLaunch.argsWithConfigImport(Path.of("config.yml"),
                new String[]{"sort", "2019", "--sluice.paths.inbox=/photos", "--debug"});

        assertThat(args).containsExactly("--spring.config.import=optional:file:config.yml",
                "sort", "2019", "--sluice.paths.inbox=/photos", "--debug");
    }
}
