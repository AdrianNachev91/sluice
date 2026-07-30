package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sha256HasherTest {

    private static final String FIXTURE_CONTENT = "sluice-hash-fixture\n";
    private static final String FIXTURE_SHA256 =
            "0589AB8077386B804C8C7734B058DDC089CCC72B86032F53B759FC6368960284";

    private final Sha256Hasher hasher = new Sha256Hasher();

    @Test
    void hashesFixtureFileToKnownUpperHexSha256(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("fixture.txt");
        Files.writeString(file, FIXTURE_CONTENT, StandardCharsets.UTF_8);

        assertThat(hasher.hash(file)).isEqualTo(FIXTURE_SHA256);
    }

    @Test
    void missingFileWrapsIoExceptionUnchecked(@TempDir final Path dir) {
        final Path missing = dir.resolve("does-not-exist.txt");

        assertThatThrownBy(() -> hasher.hash(missing)).isInstanceOf(UncheckedIOException.class);
    }
}
