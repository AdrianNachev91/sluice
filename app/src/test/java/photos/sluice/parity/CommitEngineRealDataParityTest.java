package photos.sluice.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.service.CommitEngine;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.commit.CommitScope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The commit parity gate. It runs the reference commit engine and CommitEngine on two identical
// copies of the same real Sorted tree. It then asserts MoveDiffer sees no difference in the
// resulting Library trees, and that both runs appended the same set of content hashes to their
// index. Opt-in only, never running on CI. It requires a real, already-sorted directory on disk
// that this test only ever copies from, never writes to.
//
// Unlike the sort parity gate, the fixture copy below doesn't need to preserve file timestamps.
// A commit's scope decision (CommitScopeSelector) reads only the YYYY/MM segments already present
// in the Sorted-relative path string. It never reads a file's mtime or any other date signal. So
// neither engine's routing here can be affected by the copy dropping timestamps.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=CommitEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="D:\path\to\a\Sorted\directory"
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class CommitEngineRealDataParityTest {

    @Test
    void commitEngineMatchesReferenceEngineOnRealSortedData(@TempDir final Path rootA, @TempDir final Path rootB)
            throws IOException, InterruptedException {
        final String sourceDirProperty = System.getProperty("sluice.parity.sourceDir");
        Assumptions.assumeTrue(sourceDirProperty != null && !sourceDirProperty.isBlank(),
                "sluice.parity.sourceDir must be set to a Sorted directory when sluice.parity.realData=true");
        final Path sourceDir = Path.of(sourceDirProperty);
        Assumptions.assumeTrue(Files.isDirectory(sourceDir), "sluice.parity.sourceDir does not exist: " + sourceDir);

        copyRecursively(sourceDir, rootA.resolve("Sorted"));
        copyRecursively(sourceDir, rootB.resolve("Sorted"));

        final Path repoRoot = findRepoRoot();
        runReferenceEngine(repoRoot, rootA);
        commitEngine(rootB).commit(new CommitScope.All());

        final MoveDiffer differ = new MoveDiffer();
        final MoveDiffer.Diff libraryDiff = differ.diffTrees(rootA.resolve("Library"), rootB.resolve("Library"));
        assertThat(libraryDiff.identical())
                .as("Library trees diverged (only-in-reference=%s, only-in-Java=%s)",
                        libraryDiff.onlyInA(), libraryDiff.onlyInB())
                .isTrue();

        final MoveDiffer.Diff sortedDiff = differ.diffTrees(rootA.resolve("Sorted"), rootB.resolve("Sorted"));
        assertThat(sortedDiff.identical())
                .as("leftover Sorted trees diverged (only-in-reference=%s, only-in-Java=%s)",
                        sortedDiff.onlyInA(), sortedDiff.onlyInB())
                .isTrue();

        final var hashIndexA = new CsvLibraryHashIndex(SettingsFixture.workingRoot(rootA));
        final var hashIndexB = new CsvLibraryHashIndex(SettingsFixture.workingRoot(rootB));
        assertThat(hashIndexB.load().keySet())
                .as("appended index hashes")
                .isEqualTo(hashIndexA.load().keySet());
    }

    private static Path findRepoRoot() {
        final Path startingDirectory = Path.of("").toAbsolutePath();
        Path candidate = startingDirectory;
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts").resolve("commit.ps1"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/commit.ps1 above " + startingDirectory);
    }

    private static void runReferenceEngine(final Path repoRoot, final Path rootA) throws IOException,
            InterruptedException {
        try (final Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-File", repoRoot.resolve("scripts").resolve("commit.ps1").toString(),
                "-RepoRoot", rootA.toString(),
                "-LibraryRoot", rootA.resolve("Library").toString(),
                "-All")
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

    private static CommitEngine commitEngine(final Path root) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
        final var hashIndex = new CsvLibraryHashIndex(pathsConfig);
        return new CommitEngine(pathsConfig, new NioMediaStore(), new Sha256Hasher(), hashIndex);
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
