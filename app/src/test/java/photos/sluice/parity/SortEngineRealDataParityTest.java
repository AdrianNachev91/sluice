package photos.sluice.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.InboxScanner;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.imaging.ImageDimensionsReader;
import photos.sluice.adapter.metadata.ExifSource;
import photos.sluice.adapter.metadata.FilenameSource;
import photos.sluice.adapter.metadata.MtimeSource;
import photos.sluice.adapter.metadata.TakeoutJsonSource;
import photos.sluice.application.service.SortEngine;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.model.SortScope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The Phase 6 parity gate: runs the reference sort engine and SortEngine on two identical copies of
// the same real Google Takeout export, then asserts MoveDiffer sees no unexplained difference in the
// resulting Sorted/Review/Inbox trees. Opt-in only - never runs on CI (the gate property below is
// never set there), and requires a real, not-yet-processed Takeout export on disk that this test
// only ever copies from, never writes to.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=SortEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="D:\path\to\Takeout\Google Photos"
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class SortEngineRealDataParityTest {

    private static final String[] SOURCE_YEAR_FOLDERS = {"Photos from 2014", "Photos from 2016"};
    private static final int SCOPE_SIZE = 10_000; // comfortably above the ~856-file real copy

    // Mirrors TakeoutSidecarPairer.ownerKeyOf's full derivation. Duplicated rather than reused
    // because the production method is deliberately package-private to domain.scan, and this filter
    // is independent oracle-side logic, not a production code path.
    private static final Pattern SUPPLEMENTAL = Pattern.compile("^(.+?)\\.supplemental.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIDECAR_DUP_NUMBERED = Pattern.compile("^(.+)\\.([^.]+)\\((\\d+)\\)$");
    private static final int MIN_TRUNCATED_OWNER_KEY_LENGTH = 46; // SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH

    @Test
    void sortEngineMatchesReferenceEngineOnRealTakeoutData(@TempDir Path rootA, @TempDir Path rootB)
            throws IOException, InterruptedException {
        String sourceDirProperty = System.getProperty("sluice.parity.sourceDir");
        Assumptions.assumeTrue(sourceDirProperty != null && !sourceDirProperty.isBlank(),
                "sluice.parity.sourceDir must be set to the Takeout/Google Photos folder when sluice.parity.realData=true");
        Path sourceDir = Path.of(sourceDirProperty);
        Assumptions.assumeTrue(Files.isDirectory(sourceDir), "sluice.parity.sourceDir does not exist: " + sourceDir);

        for (String yearFolder : SOURCE_YEAR_FOLDERS) {
            Path source = sourceDir.resolve(yearFolder);
            Assumptions.assumeTrue(Files.isDirectory(source), "Expected source year folder missing: " + source);
            copyRecursively(source, rootA.resolve("Inbox").resolve(yearFolder));
            copyRecursively(source, rootB.resolve("Inbox").resolve(yearFolder));
        }

        Path repoRoot = findRepoRoot();
        runReferenceEngine(repoRoot, rootA);
        newSortEngine(rootB).sort(new SortScope.OldestN(SCOPE_SIZE));

        MoveDiffer differ = new MoveDiffer();
        assertNoUnexplainedDiff("Sorted", differ.diffTrees(rootA.resolve("Sorted"), rootB.resolve("Sorted")));
        assertNoUnexplainedDiff("Review", differ.diffTrees(rootA.resolve("Review"), rootB.resolve("Review")));
        assertNoUnexplainedDiff("Inbox", differ.diffTrees(rootA.resolve("Inbox"), rootB.resolve("Inbox")));
    }

    // Any entry here is a real divergence UNLESS it's explained by the known, deliberate 46-char
    // sidecar-truncation floor (SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH): Java's sweep only
    // prefix-matches a truncated sidecar name when its owner key is at least 46 characters long; the
    // reference engine's sweep has no such floor and prefix-matches unconditionally. Java's keep
    // condition is therefore a strict subset of the reference's - anything Java keeps, the reference
    // also keeps - so the only possible divergence is the reference keeping a short-owner-key sidecar
    // (unconditional prefix match) that Java's floor makes it delete. That sidecar survives in the
    // reference's tree but not Java's, which is "onlyInA" (present in the reference's output, absent
    // from Java's), never "onlyInB".
    private static void assertNoUnexplainedDiff(String label, MoveDiffer.Diff diff) {
        Set<String> unexplainedOnlyInA = new HashSet<>();
        Set<String> unexplainedOnlyInB = diff.onlyInB();
        int explained = 0;
        for (String path : diff.onlyInA()) {
            if (isExplainedByKnownSidecarFloorDivergence(path)) {
                explained++;
            } else {
                unexplainedOnlyInA.add(path);
            }
        }
        // Printed on every run, pass or fail: a silent 0-diff pass and a filter-swallowed-a-real-bug
        // pass both print "explained=0" here, so anyone re-reading the log after the fact can tell
        // whether the 46-char divergence filter actually did anything on this run's real data.
        System.out.printf("[parity] %s: explained-by-46-char-floor=%d, unexplained-only-in-reference=%d, unexplained-only-in-java=%d%n",
                label, explained, unexplainedOnlyInA.size(), unexplainedOnlyInB.size());
        if (!unexplainedOnlyInA.isEmpty() || !unexplainedOnlyInB.isEmpty()) {
            fail("%s tree diverged (only-in-reference-unexplained=%s, only-in-Java=%s, explained-by-46-char-floor=%d)",
                    label, unexplainedOnlyInA, unexplainedOnlyInB, explained);
        }
    }

    private static boolean isExplainedByKnownSidecarFloorDivergence(String relativePath) {
        if (!relativePath.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return false;
        }
        String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return ownerKeyOf(fileName).length() < MIN_TRUNCATED_OWNER_KEY_LENGTH;
    }

    // Mirrors TakeoutSidecarPairer.ownerKeyOf's exact-match, supplemental-suffix, and dup-numbered
    // branches. The 46-char floor comparison below is only meaningful against the same owner key
    // production actually computes, so a dup-numbered sidecar needs its dup-numbering reversed here
    // too, not just its json/supplemental suffix stripped.
    private static String ownerKeyOf(String jsonFileName) {
        String base = jsonFileName.regionMatches(true, jsonFileName.length() - 5, ".json", 0, 5)
                ? jsonFileName.substring(0, jsonFileName.length() - 5)
                : jsonFileName;
        Matcher supplemental = SUPPLEMENTAL.matcher(base);
        if (supplemental.matches()) {
            return supplemental.group(1);
        }
        Matcher dup = SIDECAR_DUP_NUMBERED.matcher(base);
        if (dup.matches()) {
            return dup.group(1) + "(" + dup.group(3) + ")." + dup.group(2);
        }
        return base;
    }

    // Walks upward from the JVM's working directory until a directory containing the reference
    // engine's entry script is found - robust to Surefire's actual working directory rather than
    // assuming app/ or the mvn invocation directory.
    private static Path findRepoRoot() {
        Path startingDirectory = Path.of("").toAbsolutePath();
        Path candidate = startingDirectory;
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts").resolve("sort.ps1"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/sort.ps1 above " + startingDirectory);
    }

    private static void runReferenceEngine(Path repoRoot, Path rootA) throws IOException, InterruptedException {
        Path exifTool = repoRoot.resolve("tools").resolve("exiftool.exe");
        // Process implements Closeable (closes its inherited-IO streams on exit; does not itself wait
        // for or kill the process, so waitFor/destroyForcibly below are still needed).
        try (Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-File", repoRoot.resolve("scripts").resolve("sort.ps1").toString(),
                "-RepoRoot", rootA.toString(),
                "-OldestN", String.valueOf(SCOPE_SIZE),
                "-ExifTool", exifTool.toString())
                .inheritIO()
                .start()) {
            // Bounded rather than an indefinite waitFor(): a manually-gated local run should fail
            // loudly on a hung child process (e.g. a corrupt file wedging exiftool) instead of
            // hanging forever.
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail("reference engine did not finish within 10 minutes - killed");
            }
            assertThat(process.exitValue()).as("reference engine exit code").isZero();
        }
    }

    private static SortEngine newSortEngine(Path root) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), root.toString(), root.resolve("Inbox").toString()));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv"));
        var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        return new SortEngine(pathsConfig, new InboxScanner(), dateResolver, new Sha256Hasher(), hashIndex,
                new ImageDimensionsReader(), new NioMediaStore());
    }

    private static void copyRecursively(Path source, Path destination) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
