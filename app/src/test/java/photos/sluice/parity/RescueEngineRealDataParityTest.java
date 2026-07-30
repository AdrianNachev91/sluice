package photos.sluice.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.application.service.RescueEngine;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.dating.RescueDateResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The Phase 7 rescue parity gate: runs the reference rescue engine and RescueEngine on two
// identical copies of the same real Review folder, then asserts MoveDiffer sees no difference in
// the resulting Library trees, the same set of files left behind (skipped), and both runs
// appended the same set of content hashes to their index. Opt-in only - never runs on CI, and
// requires a real Review folder on disk that this test only ever copies from, never writes to.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=RescueEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="D:\path\to\a\Review\2019-06"
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class RescueEngineRealDataParityTest {

    @Test
    void rescueEngineMatchesReferenceEngineOnRealReviewData(@TempDir final Path rootA, @TempDir final Path rootB)
            throws IOException, InterruptedException {
        final String sourceDirProperty = System.getProperty("sluice.parity.sourceDir");
        Assumptions.assumeTrue(sourceDirProperty != null && !sourceDirProperty.isBlank(),
                "sluice.parity.sourceDir must be set to a Review subfolder when sluice.parity.realData=true");
        final Path sourceDir = Path.of(sourceDirProperty);
        Assumptions.assumeTrue(Files.isDirectory(sourceDir), "sluice.parity.sourceDir does not exist: " + sourceDir);
        final String leaf = sourceDir.getFileName().toString();

        copyRecursively(sourceDir, rootA.resolve("Review").resolve(leaf));
        copyRecursively(sourceDir, rootB.resolve("Review").resolve(leaf));

        final Path repoRoot = findRepoRoot();
        runReferenceEngine(repoRoot, rootA, leaf);
        rescueEngine(rootB).rescue(leaf);

        final MoveDiffer differ = new MoveDiffer();
        final MoveDiffer.Diff libraryDiff = differ.diffTrees(rootA.resolve("Library"), rootB.resolve("Library"));
        assertThat(libraryDiff.identical())
                .as("Library trees diverged (only-in-reference=%s, only-in-Java=%s)",
                        libraryDiff.onlyInA(), libraryDiff.onlyInB())
                .isTrue();

        final MoveDiffer.Diff reviewDiff = differ.diffTrees(rootA.resolve("Review"), rootB.resolve("Review"));
        assertThat(reviewDiff.identical())
                .as("leftover Review trees diverged (only-in-reference=%s, only-in-Java=%s)",
                        reviewDiff.onlyInA(), reviewDiff.onlyInB())
                .isTrue();

        final var hashIndexA = new CsvLibraryHashIndex(rootA.resolve("logs").resolve("library-hashes.csv"));
        final var hashIndexB = new CsvLibraryHashIndex(rootB.resolve("logs").resolve("library-hashes.csv"));
        assertThat(hashIndexB.load().keySet())
                .as("appended index hashes")
                .isEqualTo(hashIndexA.load().keySet());
    }

    private static Path findRepoRoot() {
        final Path startingDirectory = Path.of("").toAbsolutePath();
        Path candidate = startingDirectory;
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts").resolve("rescue.ps1"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/rescue.ps1 above " + startingDirectory);
    }

    private static void runReferenceEngine(final Path repoRoot, final Path rootA, final String leaf) throws IOException, InterruptedException {
        final Path exifTool = repoRoot.resolve("tools").resolve("exiftool.exe");
        try (final Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-File", repoRoot.resolve("scripts").resolve("rescue.ps1").toString(),
                "-ReviewFolder", rootA.resolve("Review").resolve(leaf).toString(),
                "-RepoRoot", rootA.toString(),
                "-LibraryRoot", rootA.resolve("Library").toString(),
                "-ExifTool", exifTool.toString())
                .inheritIO()
                .start()) {
            final boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail("reference engine did not finish within 10 minutes - killed");
            }
            assertThat(process.exitValue()).as("reference engine exit code").isZero();
        }
    }

    private static RescueEngine rescueEngine(final Path root) {
        final var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), root.resolve("Library").toString(), root.resolve("Inbox").toString()));
        final var hashIndex = new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv"));
        final var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        return new RescueEngine(pathsConfig, new NioMediaStore(), new Sha256Hasher(), hashIndex, rescueDateResolver);
    }

    private static void copyRecursively(final Path source, final Path destination) throws IOException {
        try (final var walk = Files.walk(source)) {
            for (final Path path : (Iterable<Path>) walk::iterator) {
                final Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        } catch (final UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
