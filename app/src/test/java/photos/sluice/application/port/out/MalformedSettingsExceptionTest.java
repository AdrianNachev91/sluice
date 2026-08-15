package photos.sluice.application.port.out;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MalformedSettingsExceptionTest {

    private static final Path CONFIG = Path.of("config.yml").toAbsolutePath();

    @Test
    void theFileIsCarriedThroughAsAValue() {
        assertThat(new MalformedSettingsException(CONFIG, "broken").settingsFile()).isEqualTo(CONFIG);
    }

    @Test
    void aMalformedSettingsFileIsNotAnIllegalStateException() {
        assertThat(new MalformedSettingsException(CONFIG, "broken")).isNotInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMalformedSettingsFileIsNotAnIoFailure() {
        assertThat(new MalformedSettingsException(CONFIG, "broken")).isNotInstanceOf(UncheckedIOException.class);
    }

    @Test
    void theParseFailureUnderneathIsKept() {
        final var parseFailure = new IllegalArgumentException("could not scan a block mapping");

        assertThat(new MalformedSettingsException(CONFIG, "broken", parseFailure)).hasCause(parseFailure);
    }
}
