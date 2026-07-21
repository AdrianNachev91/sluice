package photos.sluice.adapter.imaging;

import photos.sluice.application.port.out.HeifDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Shells to a libheif-based CLI decoder (heif-convert from the libheif project). This is the
// one permitted native dependency in the Global Constraints. No metadata/pixel library exists
// that can decode real HEVC/AV1 pixel data in pure Java.
// heif-convert is used rather than its newer heif-dec rename. The older name is what both
// Ubuntu's stock libheif-examples package and upstream's own compatibility alias still
// guarantee across versions. That avoids a cross-platform version-skew mismatch.
// The command is looked up on PATH by default (see ImagingConfig). A missing or failing binary
// degrades to Optional.empty() rather than throwing, matching the port's own contract.
public class CliHeifDecoder implements HeifDecoder {

    private static final long TIMEOUT_SECONDS = 30;

    private final String command;

    public CliHeifDecoder(String command) {
        this.command = command;
    }

    @Override
    public Optional<BufferedImage> decode(Path file) {
        Path output;
        try {
            output = Files.createTempFile("sluice-heif-", ".png");
        } catch (IOException _) {
            return Optional.empty();
        }
        try (Process process = new ProcessBuilder(command, "--quiet", file.toString(), output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()) {
            // --quiet suppresses the decoder's own per-percent progress lines on stdout. A bad
            // file still writes one diagnostic line. Both streams are discarded above rather than
            // read, since nothing here needs them, and leaving either unconsumed risks the child
            // blocking once its pipe buffer fills.
            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return Optional.empty();
            }
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(ImageIO.read(output.toFile()));
        } catch (IOException | RuntimeException _) {
            // IOException covers both the binary not being found on PATH (ProcessBuilder.start()
            // fails) and an I/O error reading the decoded output back. ImageIO.read is documented
            // to throw undocumented unchecked exceptions on a malformed/truncated image too, the
            // same reason every decode path in TileRenderer catches RuntimeException alongside
            // IOException. A corrupt PNG from a crashed or disk-full decoder run must degrade the
            // same way, not escape this method's "never throws" contract.
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException _) {
                // Best-effort cleanup of a temp file. Leaving a stray file in the OS temp dir on
                // the rare failure path isn't worth failing the whole decode over.
            }
        }
    }
}
