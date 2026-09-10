package photos.sluice.config;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// The two values the decoder command is composed from are both Strings, so swapping them compiles,
// and so does misspelling the property key. Either turns the bundled decoder off on every installed
// machine while the rest of the suite stays green.
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HeifCommandWiringTest {

    private static final String INSTALLATION_DIRECTORY = "app.dir";

    private final @Nullable String original = System.getProperty(INSTALLATION_DIRECTORY);

    @AfterEach
    void restoreTheProperty() {
        if (this.original == null) {
            System.clearProperty(INSTALLATION_DIRECTORY);
        } else {
            System.setProperty(INSTALLATION_DIRECTORY, this.original);
        }
    }

    @Test
    void theInstalledDecoderReachesTheBean(@TempDir final Path installation) throws IOException {
        final Path directory = Files.createDirectories(installation.resolve("heif/bin"));
        final Path installed = HeifDecoderLocatorTest.runnable(Files.createFile(directory.resolve("heif-convert")));
        System.setProperty(INSTALLATION_DIRECTORY, installation.toString());

        assertThat(AppConfig.heifCommand(new ImagingConfig("heif-convert")))
                .isEqualTo(installed.toString());
    }

    @Test
    void withNothingInstalledTheSettingReachesTheBean() {
        System.clearProperty(INSTALLATION_DIRECTORY);

        assertThat(AppConfig.heifCommand(new ImagingConfig("heif-convert"))).isEqualTo("heif-convert");
    }
}
