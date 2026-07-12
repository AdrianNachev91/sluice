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

    private static final String FIXTURE_CONTENT = "sluice-phase2-fixture\n";
    private static final String FIXTURE_SHA256 =
            "30C9606C6DD07382446782C0CE33FA626DE13A71567E292B0D44C325E1FCD96E";

    private final Sha256Hasher hasher = new Sha256Hasher();

    @Test
    void hashesFixtureFileToKnownUpperHexSha256(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fixture.txt");
        Files.writeString(file, FIXTURE_CONTENT, StandardCharsets.UTF_8);

        assertThat(hasher.hash(file)).isEqualTo(FIXTURE_SHA256);
    }

    @Test
    void missingFileWrapsIoExceptionUnchecked(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.txt");

        assertThatThrownBy(() -> hasher.hash(missing)).isInstanceOf(UncheckedIOException.class);
    }
}
