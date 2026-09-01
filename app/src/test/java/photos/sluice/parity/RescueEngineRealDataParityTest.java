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
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.service.RescueEngine;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.paths.SortFolderNames;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The Phase 7 rescue parity gate. It runs the reference rescue engine and RescueEngine on two
// identical copies of the same real Review folder. It then asserts MoveDiffer sees no difference
// in the resulting Sorted trees or in what was left behind. Opt-in only, never running on CI, and
// requiring a real Review folder on disk that this test only ever copies from, never writes to.
//
// The reference is invoked with its own -ToSorted switch, which is the mode this engine matches.
// Keepers land in Sorted\Photos or Sorted\Videos under <yyyy>\<MM>, and neither side writes a
// hash-index row. So there is no divergence from the reference to record here.
//
// Two deliberate divergences, and this test asserts them rather than scoping around them.
//
// The reference leaves an undatable file where it found it. This engine moves what it cannot date
// into Sorted\Unsorted, so the two sides together have to account for the same set of files.
//
// The reference dates exif first and the folder last. This engine reads the date its own path
// spells first, in both shapes, and only then falls through to exif. So a file under
// Unsorted\<yyyy>\<MM>\ carrying an exif date that disagrees lands in a different month on the two
// sides. Point sluice.parity.sourceDir at a folder holding that shape and the dated-tree
// assertion is where it shows.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=RescueEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="D:\path\to\a\Review\2019-06"
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class RescueEngineRealDataParityTest {

    private static final String UNDATED_PREFIX = SortFolderNames.UNDATED + "/";

    private static final String REASONS_FILE = "_reasons.txt";

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
        rescueEngine(rootB).rescue(RescueRoot.REVIEW, leaf);

        final MoveDiffer differ = new MoveDiffer();
        final Set<String> datedA = dated(differ.relativeFilePaths(rootA.resolve("Sorted")));
        final Set<String> datedB = dated(differ.relativeFilePaths(rootB.resolve("Sorted")));
        final MoveDiffer.Diff sortedDiff = differ.diff(datedA, datedB);
        assertThat(sortedDiff.identical())
                .as("dated Sorted trees diverged (only-in-reference=%s, only-in-Java=%s)",
                        sortedDiff.onlyInA(), sortedDiff.onlyInB())
                .isTrue();

        // The one deliberate divergence. What the reference cannot date it leaves in Review, and
        // this engine moves into Sorted\Unsorted. So the two together have to account for exactly
        // what the reference left behind.
        //
        // Compared by filename, because the reference keeps the folder structure and this engine
        // writes flat. The reason marker is excluded from both sides. It survives on the reference
        // wherever anything was skipped, and this engine deletes it whenever it empties a folder.
        // That is a difference about markers rather than about anybody's photos.
        final Set<String> leftBehindByReference =
                namesBesidesTheMarker(differ.relativeFilePaths(rootA.resolve("Review")));
        final Set<String> stillWaitingInJava = new HashSet<>();
        stillWaitingInJava.addAll(namesBesidesTheMarker(differ.relativeFilePaths(rootB.resolve("Review"))));
        stillWaitingInJava.addAll(namesBesidesTheMarker(undated(differ.relativeFilePaths(rootB.resolve("Sorted")))));
        assertThat(stillWaitingInJava)
                .as("what the reference left in Review, against Review plus Sorted\\Unsorted here")
                .isEqualTo(leftBehindByReference);

        // Neither side writes the index on this route, and a row on either would be the defect.
        assertThat(new CsvLibraryHashIndex(SettingsFixture.workingRoot(rootA)).load())
                .as("reference hash index under -ToSorted")
                .isEmpty();
        assertThat(new CsvLibraryHashIndex(SettingsFixture.workingRoot(rootB)).load())
                .as("hash index after a rescue")
                .isEmpty();
    }

    private static Set<String> dated(final Set<String> paths) {
        return paths.stream().filter(path -> !path.startsWith(UNDATED_PREFIX)).collect(Collectors.toSet());
    }

    private static Set<String> undated(final Set<String> paths) {
        return paths.stream().filter(path -> path.startsWith(UNDATED_PREFIX)).collect(Collectors.toSet());
    }

    // Two files of the same name in different folders collapse to one entry, which is what makes
    // this a comparison of names rather than of paths. The two sides write different folder
    // structures, so their paths cannot be compared at all.
    private static Set<String> namesBesidesTheMarker(final Set<String> paths) {
        return paths.stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .filter(name -> !REASONS_FILE.equals(name))
                .collect(Collectors.toSet());
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
                // Passed although -ToSorted makes it unreachable as a destination. The script
                // defaults it to a real library path, and nothing about this test may point there.
                "-LibraryRoot", rootA.resolve("Library").toString(),
                "-ToSorted",
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
        final var pathsConfig = SettingsFixture.pathsConfig(root, root.resolve("Library"), root.resolve("Inbox"));
        final var rescueDateResolver = new RescueDateResolver(new ExifSource(), new FilenameSource());
        return new RescueEngine(pathsConfig, new NioMediaStore(), rescueDateResolver, new Sha256Hasher());
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
