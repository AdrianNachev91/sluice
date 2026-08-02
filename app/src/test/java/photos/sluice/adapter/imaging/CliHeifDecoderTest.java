package photos.sluice.adapter.imaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CliHeifDecoderTest {

    private static final Path FIXTURES = Path.of("src/test/resources");

    private static final Path DECODABLE_FIXTURE = FIXTURES.resolve("dating/iphone-exif.heic");

    private final CliHeifDecoder decoder = new CliHeifDecoder("heif-convert");

    // Reuses the existing HEIC fixture from the dating tests - a real iPhone photo, no new
    // fixture needed. Confirms the adapter actually decodes real HEVC pixel data end to end,
    // not just that a process gets launched.
    @Test
    void decodesARealHeicFixtureToItsFullResolution() {
        final Optional<BufferedImage> result = this.decoder.decode(DECODABLE_FIXTURE);

        assertThat(result).isPresent();
        assertThat(result.get().getWidth()).isEqualTo(4032);
        assertThat(result.get().getHeight()).isEqualTo(3024);
    }

    // arctic-sky.avif (real, public-domain, verified genuine ftyp/avif box structure - see
    // cull/README.md) exercises the AV1 payload path through the same HeifDecoder port, unlike
    // the HEVC fixture above. Dimensions cross-checked with `magick identify` (1600x1063).
    @Test
    void decodesARealAvifFixtureToItsFullResolution() {
        final Optional<BufferedImage> result = this.decoder.decode(FIXTURES.resolve("cull/arctic-sky.avif"));

        assertThat(result).isPresent();
        assertThat(result.get().getWidth()).isEqualTo(1600);
        assertThat(result.get().getHeight()).isEqualTo(1063);
    }

    // The command is resolved against PATH at process-launch time. A nonexistent command name
    // reproduces exactly what happens on a machine with no decoder installed at all - the case
    // TileRenderer's placeholder fallback exists for.
    @Test
    void missingBinaryDegradesGracefullyToEmpty(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("photo.heic");
        Files.createFile(file);
        final CliHeifDecoder withMissingBinary = new CliHeifDecoder("sluice-test-nonexistent-heif-decoder");

        final Optional<BufferedImage> result = withMissingBinary.decode(file);

        assertThat(result).isEmpty();
    }

    // Scratch-file creation can fail unchecked as well as checked, and the injectable factory is
    // the only seam that reproduces the unchecked half deterministically. SecurityException stands
    // in for any unchecked failure a filesystem provider can raise there. The fixture is a real,
    // decodable HEIC, so the refused scratch file is the only thing that can turn this result
    // empty.
    @Test
    void anUncheckedScratchFileFailureDegradesGracefullyToEmpty() {
        final var withFailingScratchFiles = new CliHeifDecoder("heif-convert", () -> {
            throw new SecurityException("scratch file refused");
        });

        final Optional<BufferedImage> result = withFailingScratchFiles.decode(DECODABLE_FIXTURE);

        assertThat(result).isEmpty();
    }

    // A real heif-convert binary, given a file that isn't actually HEIF/AVIF at all, exits
    // non-zero (verified empirically: "Input file is not an HEIF/AVIF file"). Distinguishes this
    // from the missing-binary case above - here the process runs, but fails.
    @Test
    void corruptFileDegradesGracefullyToEmpty(@TempDir final Path tempDir) throws IOException {
        final Path file = tempDir.resolve("garbage.heic");
        Files.writeString(file, "not a real heic file", StandardCharsets.US_ASCII);

        final Optional<BufferedImage> result = this.decoder.decode(file);

        assertThat(result).isEmpty();
    }
}
