package photos.sluice.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.adapter.vision.JsonCullPrepStore;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.service.ApplyEngine;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.cull.PrepDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The Phase 11 apply parity gate. It runs the reference apply-cull.ps1 and ApplyEngine on two
// identical, path-rewritten copies of the same real, completed-but-not-yet-applied cull-prep
// directory (every montage's decisions-NNN.json shard present, nothing applied yet). It then
// asserts MoveDiffer sees no difference in the resulting Review/Duplicates/Library trees, and that
// both runs appended the same set of content hashes to their index. Opt-in only - never runs on
// CI, and requires real cull-prep output on disk that this test only ever copies from, never
// writes to.
//
// Unlike the sort/commit/rescue parity gates, a plain directory copy isn't enough here: every path
// a shard, sidecar, or index.json holds is absolute, rooted at the real machine's repo root. This
// test rewrites that literal root prefix, wherever it appears in the copied JSON text, to each temp
// root's own path. It also copies the referenced Sorted subtree (index.json's basePath) to the
// matching relative location, so both engines see the same files at the same (rewritten) paths.
//
// Unreviewable-file routing (Unreviewable/<yyyy>/<mm>/) has no PS precedent - apply-cull.ps1 has no
// concept of it - so it's asserted directly against rootB alone, never diffed against rootA.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=ApplyEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="D:\path\to\logs\cull-prep\<scope>"
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class ApplyEngineRealDataParityTest {

    @Test
    void applyEngineMatchesReferenceEngineOnRealCullPrepData(@TempDir Path rootA, @TempDir Path rootB)
            throws IOException, InterruptedException, ApplyException {
        String sourceDirProperty = System.getProperty("sluice.parity.sourceDir");
        Assumptions.assumeTrue(sourceDirProperty != null && !sourceDirProperty.isBlank(),
                "sluice.parity.sourceDir must be set to a completed cull-prep directory when sluice.parity.realData=true");
        Path sourceDir = Path.of(sourceDirProperty);
        Assumptions.assumeTrue(Files.isDirectory(sourceDir), "sluice.parity.sourceDir does not exist: " + sourceDir);
        String leaf = sourceDir.getFileName().toString();
        // sourceDir is <repoRoot>/logs/cull-prep/<leaf> - the fixed layout CLAUDE.md documents.
        Path sourceRepoRoot = sourceDir.getParent().getParent().getParent();

        PrepDir sourcePrepDir = new JsonCullPrepStore().readIndex(sourceDir);
        Path relativeBase = sourceRepoRoot.relativize(sourcePrepDir.basePath());

        Path prepDirA = rootA.resolve("logs/cull-prep").resolve(leaf);
        Path prepDirB = rootB.resolve("logs/cull-prep").resolve(leaf);
        copyPrepDirJson(sourceDir, prepDirA, sourceRepoRoot, rootA);
        copyPrepDirJson(sourceDir, prepDirB, sourceRepoRoot, rootB);
        copyRecursively(sourcePrepDir.basePath(), rootA.resolve(relativeBase));
        copyRecursively(sourcePrepDir.basePath(), rootB.resolve(relativeBase));

        Path scriptRepoRoot = findRepoRoot();
        runReferenceEngine(scriptRepoRoot, prepDirA, rootA);
        applyEngine(rootB).apply(prepDirB, new ApplyOptions(false));

        MoveDiffer differ = new MoveDiffer();
        assertTreesIdentical(differ, "Review", rootA.resolve("Review"), rootB.resolve("Review"));
        assertTreesIdentical(differ, "Duplicates", rootA.resolve("Duplicates"), rootB.resolve("Duplicates"));
        assertTreesIdentical(differ, "Library", rootA.resolve("Library"), rootB.resolve("Library"));

        var hashIndexA = new CsvLibraryHashIndex(rootA.resolve("logs").resolve("library-hashes.csv"));
        var hashIndexB = new CsvLibraryHashIndex(rootB.resolve("logs").resolve("library-hashes.csv"));
        assertThat(hashIndexB.load().keySet())
                .as("appended index hashes")
                .isEqualTo(hashIndexA.load().keySet());

        assertUnreviewableFilesRelocated(sourcePrepDir, sourceRepoRoot, rootB);
    }

    private static void assertTreesIdentical(MoveDiffer differ, String label, Path treeA, Path treeB) {
        MoveDiffer.Diff diff = differ.diffTrees(treeA, treeB);
        assertThat(diff.identical())
                .as("%s trees diverged (only-in-reference=%s, only-in-Java=%s)", label, diff.onlyInA(), diff.onlyInB())
                .isTrue();
    }

    // Unreviewable routing has no PS reference to diff against - checked directly instead: every
    // unreviewable path index.json recorded (rewritten into rootB's own coordinate space) has moved
    // out of Sorted and landed under Unreviewable/<yyyy>/<mm>/. The year/month derivation mirrors
    // ApplyEngine.yearMonthOf() exactly, UNDATED fallback included, so this stays a true assertion
    // against the engine's real behavior rather than an assumption that could silently diverge from it.
    private static void assertUnreviewableFilesRelocated(PrepDir sourcePrepDir, Path sourceRepoRoot, Path rootB) {
        for (Path sourceFile : sourcePrepDir.unreviewable()) {
            Path original = rootB.resolve(sourceRepoRoot.relativize(sourceFile));
            assertThat(Files.exists(original)).as("unreviewable file left behind: %s", original).isFalse();
            String[] yearMonth = yearMonthOf(original);
            Path expectedDest = rootB.resolve("Unreviewable")
                    .resolve(yearMonth[0])
                    .resolve(yearMonth[1])
                    .resolve(original.getFileName());
            assertThat(Files.exists(expectedDest)).as("unreviewable file not found at %s", expectedDest).isTrue();
        }
    }

    // Mirrors ApplyEngine.yearMonthOf()'s own parent/grandparent parsing and UNDATED fallback, so
    // this test's expectation can never diverge from what the engine itself actually does.
    private static String[] yearMonthOf(Path file) {
        Path monthDir = file.getParent();
        Path yearDir = monthDir == null ? null : monthDir.getParent();
        if (yearDir != null) {
            String month = monthDir.getFileName().toString();
            String year = yearDir.getFileName().toString();
            if (year.matches("\\d{4}") && month.matches("\\d{2}")) {
                return new String[] {year, month};
            }
        }
        return new String[] {"0000", "00"};
    }

    private static Path findRepoRoot() {
        Path startingDirectory = Path.of("").toAbsolutePath();
        Path candidate = startingDirectory;
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts").resolve("apply-cull.ps1"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/apply-cull.ps1 above " + startingDirectory);
    }

    private static void runReferenceEngine(Path scriptRepoRoot, Path prepDirA, Path rootA)
            throws IOException, InterruptedException {
        try (Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-File", scriptRepoRoot.resolve("scripts").resolve("apply-cull.ps1").toString(),
                "-PrepDir", prepDirA.toString(),
                "-RepoRoot", rootA.toString(),
                "-LibraryRoot", rootA.resolve("Library").toString())
                .inheritIO()
                .start()) {
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail("reference engine did not finish within 10 minutes - killed");
            }
            assertThat(process.exitValue()).as("reference engine exit code").isZero();
        }
    }

    private static ApplyEngine applyEngine(Path root) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(root.toString(), root.resolve("Library").toString(), root.resolve("Inbox").toString()));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs").resolve("library-hashes.csv"));
        return new ApplyEngine(pathsConfig, new NioMediaStore(), new JsonCullPrepStore(), fixedSettings(),
                new Sha256Hasher(), hashIndex);
    }

    private static CullSettings fixedSettings() {
        return new FixedSettings("external-agent", List.of(
                new CullCategory("junk", "junk description"),
                new CullCategory("scenery", "scenery description"),
                new CullCategory("food", "food description"),
                new CullCategory("funny", "funny description")));
    }

    private record FixedSettings(String provider, List<CullCategory> categories) implements CullSettings {

        @Override
        public CullProviderSettings providerSettings() {
            return new CullProviderSettings(null, null, null, null);
        }
    }

    // Copies index.json + every montage-*.json sidecar + every decisions-*.json shard from source
    // into dest, rewriting every occurrence of fromRoot's literal (JSON-escaped) path prefix to
    // toRoot's. This is the same relocation copyRecursively below applies to the referenced Sorted
    // files. Montage/tile images and any prior applied.log/decisions.json/move-records.log are
    // deliberately not copied: this test needs a completed-but-not-yet-applied prep dir, and
    // carrying over a stale merge record from a previous local run would corrupt the comparison.
    private static void copyPrepDirJson(Path source, Path dest, Path fromRoot, Path toRoot) throws IOException {
        Files.createDirectories(dest);
        try (Stream<Path> files = Files.list(source).filter(ApplyEngineRealDataParityTest::isPrepJson)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String rewritten = rewriteRoot(Files.readString(file), fromRoot, toRoot);
                Files.writeString(dest.resolve(file.getFileName()), rewritten);
            }
        }
    }

    private static boolean isPrepJson(Path file) {
        String name = file.getFileName().toString();
        return name.equals("index.json")
                || (name.startsWith("montage-") && name.endsWith(".json"))
                || (name.startsWith("decisions-") && name.endsWith(".json"));
    }

    private static String rewriteRoot(String json, Path fromRoot, Path toRoot) {
        String from = fromRoot.toString().replace("\\", "\\\\");
        String to = toRoot.toString().replace("\\", "\\\\");
        return json.replace(from, to);
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
