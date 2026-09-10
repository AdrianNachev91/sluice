package photos.sluice.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Where the packaging unpacks the decoder and where the app looks for it are set in two files that
// share no symbol. A disagreement costs nothing at build time. Every HEIC then silently becomes a
// placeholder on every installed machine. Surefire's working directory is app/, where conveyor.conf
// sits.
class BundledDecoderPathTest {

    @Test
    void theAppLooksWherePackagingUnpacksTheDecoder() throws IOException {
        final String packaging = Files.readString(Path.of("conveyor.conf"));
        final String destination = HeifDecoderLocator.installationSubdirectory().split("/")[0];

        assertThat(packaging).contains("to = " + destination);
    }
}
