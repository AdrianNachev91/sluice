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
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.service.SortEngine;
import photos.sluice.config.SettingsFixture;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The sort parity gate. It runs the reference sort engine and SortEngine on two identical copies
// of the same real Google Takeout export. It then asserts MoveDiffer sees no unexplained
// difference in the resulting Sorted, Review and Inbox trees. Opt-in only, never running on CI,
// since the gate property below is never set there. It requires a real, not-yet-processed Takeout
// export on disk that this test only ever copies from, never writes to.
//
// Local invocation:
//   mvn -f app/pom.xml test -Dtest=SortEngineRealDataParityTest ^
//     -Dsluice.parity.realData=true ^
//     -Dsluice.parity.sourceDir="<export root>/Takeout/Google Photos" ^
//     -Dsluice.parity.folders="Photos from 2016,Photos from 2024"
//
// The gate wants more than one independent scope, and which folders make a good scope depends on
// what a change touched. So the folder list is an input rather than a constant. Omit it for the
// two small year folders below.
@EnabledIfSystemProperty(named = "sluice.parity.realData", matches = "true")
class SortEngineRealDataParityTest {

    private static final String DEFAULT_SOURCE_FOLDERS = "Photos from 2014,Photos from 2016";
    // Large enough that OldestN takes the whole Inbox whatever the scope. A run that silently
    // sorted a subset would be meaningless.
    private static final int SCOPE_SIZE = 1_000_000;
    private static final int REFERENCE_TIMEOUT_MINUTES = 45;
    private static final String REASONS_NOTE = "_reasons.txt";

    @Test
    void sortEngineMatchesReferenceEngineOnRealTakeoutData(@TempDir final Path rootA, @TempDir final Path rootB)
            throws IOException, InterruptedException {
        final String sourceDirProperty = System.getProperty("sluice.parity.sourceDir");
        Assumptions.assumeTrue(sourceDirProperty != null && !sourceDirProperty.isBlank(),
                "sluice.parity.sourceDir must be set to the Takeout/Google Photos folder when sluice.parity" +
                        ".realData=true");
        final Path sourceDir = Path.of(sourceDirProperty);
        Assumptions.assumeTrue(Files.isDirectory(sourceDir), "sluice.parity.sourceDir does not exist: " + sourceDir);

        final String[] sourceFolders =
                System.getProperty("sluice.parity.folders", DEFAULT_SOURCE_FOLDERS).split(",");
        for (final String folderName : sourceFolders) {
            final String folder = folderName.trim();
            final Path source = sourceDir.resolve(folder);
            Assumptions.assumeTrue(Files.isDirectory(source), "Expected source folder missing: " + source);
            copyRecursively(source, rootA.resolve("Inbox").resolve(folder));
            copyRecursively(source, rootB.resolve("Inbox").resolve(folder));
        }
        System.out.printf("[parity] scope: %s%n", String.join(" | ", sourceFolders));

        final Path repoRoot = findRepoRoot();
        runReferenceEngine(repoRoot, rootA);
        sortEngine(rootB).sort(new SortScope.OldestN(SCOPE_SIZE));

        final MoveDiffer differ = new MoveDiffer();
        // The destination trees hold only media, so any difference there is real, .json or not.
        assertNoUnexplainedDiff("Sorted", differ.diffTrees(rootA.resolve("Sorted"), rootB.resolve("Sorted")), false);
        assertNoUnexplainedDiff("Review", differ.diffTrees(rootA.resolve("Review"), rootB.resolve("Review")), false);
        assertNoUnexplainedDiff("Inbox", differ.diffTrees(rootA.resolve("Inbox"), rootB.resolve("Inbox")), true);
    }

    // Any entry here is a real divergence UNLESS it is a .json in the Inbox, where the two engines
    // decide which sidecars are spent by different rules.
    //
    // The bulk of that difference runs one way. Java refuses to delete a .json unless a media file
    // still in the Inbox has stopped owning it, and three rules widen its keep set. It reads the
    // scan's own pairing, so a sidecar matched only by prefix stays. It requires every co-owner of a
    // shared sidecar to have left. And it never touches a .json that could not have been a
    // per-photo sidecar at all, which covers album descriptors and unrelated app JSON.
    //
    // A narrow slice runs the other way. The reference keeps any sidecar whose owner key is a
    // prefix of a remaining media name, however short; Java requires an exact match. So the
    // reference keeps a few Java deletes.
    //
    // The reference engine is a proof-of-concept, not the specification (scripts/sort.ps1:399-414
    // deletes on a one-directional name comparison alone, contradicting its own header at :385-386).
    // Both directions are recorded divergences rather than regressions. The printed counts are the
    // audit trail: a run whose Inbox .json counts jump is worth reading, even though it passes.
    //
    // The second excuse covers a dating difference. The reference builds no EXIF map in Takeout mode
    // (scripts/sort.ps1:252), so its chain is sidecar, Shell, filename, mtime. DateResolver always
    // consults EXIF, second. The two only disagree for a file whose sidecar fails to pair, which is
    // what a truncated sidecar name always does. The reference then dates it by mtime, which on a
    // fresh export is the extraction date, while Java recovers the real capture date. Java is right.
    //
    // That excuse is deliberately narrow. It only forgives one file name landing in two different
    // folders, and only in the trees a file is sorted into. Nothing is lost, duplicated, or
    // diverted to Review without failing, and every pair it forgives is printed by name.
    private static void assertNoUnexplainedDiff(final String label, final MoveDiffer.Diff diff,
                                                final boolean sidecarRulesApply) {
        final Set<String> unexplainedOnlyInA = new HashSet<>();
        final Set<String> unexplainedOnlyInB = new HashSet<>();
        final Set<String> jsonOnlyInReference = new HashSet<>();
        final int explainedOnlyInA =
                partition(diff.onlyInA(), unexplainedOnlyInA, jsonOnlyInReference, sidecarRulesApply);
        final int explainedOnlyInB =
                partition(diff.onlyInB(), unexplainedOnlyInB, new HashSet<>(), sidecarRulesApply);
        // Only where a file was sorted. An Inbox leftover means an engine failed to process that
        // file, so two engines leaving different files behind is never one file refiled. Album
        // folders routinely repeat a leaf name, so draining the Inbox on name alone would pair up
        // two unrelated processing failures and pass them.
        final Set<String> refiled = sidecarRulesApply ? Set.of()
                : drainRefiledUnderADifferentDate(unexplainedOnlyInA, unexplainedOnlyInB);
        // Printed on every run, pass or fail, so a log read after the fact says whether the
        // divergence filter did anything on this run's real data.
        System.out.printf("[parity] %s: json-kept-only-by-reference=%d, json-kept-only-by-java=%d, "
                        + "refiled-under-a-different-date=%d, unexplained-only-in-reference=%d, "
                        + "unexplained-only-in-java=%d%n",
                label, explainedOnlyInA, explainedOnlyInB, refiled.size(),
                unexplainedOnlyInA.size(), unexplainedOnlyInB.size());
        if (!refiled.isEmpty()) {
            System.out.printf("[parity] %s: refiled by name = %s%n", label, refiled);
        }
        // Named rather than counted. This is the direction where a .json was deleted that the
        // reference kept, and a count alone would make a real one indistinguishable from the
        // expected handful.
        if (!jsonOnlyInReference.isEmpty()) {
            System.out.printf("[parity] %s: json kept only by the reference = %s%n", label, jsonOnlyInReference);
        }
        if (!unexplainedOnlyInA.isEmpty() || !unexplainedOnlyInB.isEmpty()) {
            fail("%s tree diverged (only-in-reference=%s, only-in-Java=%s, explained-json=%d, refiled=%d)",
                    label, unexplainedOnlyInA, unexplainedOnlyInB, explainedOnlyInA + explainedOnlyInB,
                    refiled.size());
        }
    }

    // Removes, from both sides, the entries that are the same file name under two different folders.
    // Those are the dating divergence. Both engines sorted the file and kept it, and only disagreed
    // about which month it belongs to.
    private static Set<String> drainRefiledUnderADifferentDate(final Set<String> onlyInReference,
                                                               final Set<String> onlyInJava) {
        final Set<String> onBothSides = new HashSet<>(fileNames(onlyInReference));
        onBothSides.retainAll(fileNames(onlyInJava));

        // A refiled file takes its reasons note with it. The engine that put it in a month of its
        // own is the only one with a note there, so that note has no counterpart to match. It is
        // a companion of the placement already forgiven above, not a divergence of its own. Only
        // the folders a refiled file actually touched are covered, so a stray note anywhere else
        // still fails.
        final Set<String> touchedFolders = new HashSet<>();
        collectRefiled(onlyInReference, onBothSides, touchedFolders);
        collectRefiled(onlyInJava, onBothSides, touchedFolders);
        onlyInReference.removeIf(path -> isReasonsNoteIn(path, touchedFolders));
        onlyInJava.removeIf(path -> isReasonsNoteIn(path, touchedFolders));
        return onBothSides;
    }

    // Removes one side's refiled entries, recording the folders they came from.
    private static void collectRefiled(final Set<String> side, final Set<String> refiledNames,
                                       final Set<String> touchedFolders) {
        side.stream()
                .filter(path -> refiledNames.contains(fileNameOf(path)))
                .map(SortEngineRealDataParityTest::folderOf)
                .forEach(touchedFolders::add);
        side.removeIf(path -> refiledNames.contains(fileNameOf(path)));
    }

    private static boolean isReasonsNoteIn(final String relativePath, final Set<String> folders) {
        return fileNameOf(relativePath).equals(REASONS_NOTE) && folders.contains(folderOf(relativePath));
    }

    private static String folderOf(final String relativePath) {
        final int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    private static Set<String> fileNames(final Set<String> paths) {
        return paths.stream().map(SortEngineRealDataParityTest::fileNameOf).collect(Collectors.toSet());
    }

    private static String fileNameOf(final String relativePath) {
        return relativePath.substring(relativePath.lastIndexOf('/') + 1);
    }

    // Drains one side of the diff into unexplained, collecting what the sidecar rules account for
    // instead, and returning how many that was.
    private static int partition(final Set<String> side, final Set<String> unexplained,
                                 final Set<String> excused, final boolean sidecarRulesApply) {
        int explained = 0;
        for (final String path : side) {
            if (sidecarRulesApply && path.toLowerCase(Locale.ROOT).endsWith(".json")) {
                explained++;
                excused.add(path);
            } else {
                unexplained.add(path);
            }
        }
        return explained;
    }

    // Walks upward until the reference engine's entry script is found, rather than assuming
    // Surefire's working directory is app/ or the mvn invocation directory.
    private static Path findRepoRoot() {
        final Path startingDirectory = Path.of("").toAbsolutePath();
        Path candidate = startingDirectory;
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("scripts").resolve("sort.ps1"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate scripts/sort.ps1 above " + startingDirectory);
    }

    private static void runReferenceEngine(final Path repoRoot, final Path rootA) throws IOException,
            InterruptedException {
        final Path exifTool = repoRoot.resolve("tools").resolve("exiftool.exe");
        // Process implements Closeable, closing its inherited-IO streams on exit. It neither waits
        // for nor kills the process, so waitFor and destroyForcibly below are both still needed.
        try (final Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-File", repoRoot.resolve("scripts").resolve("sort.ps1").toString(),
                "-RepoRoot", rootA.toString(),
                "-OldestN", String.valueOf(SCOPE_SIZE),
                "-ExifTool", exifTool.toString())
                .inheritIO()
                .start()) {
            // Bounded rather than an indefinite waitFor(), so a hung child process fails loudly
            // instead of hanging forever. Generous because the scope is an input, and a big one
            // spends most of its time in per-file metadata calls.
            final boolean finished = process.waitFor(REFERENCE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                fail("reference engine did not finish within %d minutes - killed", REFERENCE_TIMEOUT_MINUTES);
            }
            assertThat(process.exitValue()).as("reference engine exit code").isZero();
        }
    }

    private static SortEngine sortEngine(final Path root) {
        final var pathsConfig = SettingsFixture.pathsConfig(root, root, root.resolve("Inbox"));
        final var hashIndex = new CsvLibraryHashIndex(pathsConfig);
        final var dateResolver =
                new DateResolver(new TakeoutJsonSource(), new ExifSource(), new FilenameSource(), new MtimeSource());
        return new SortEngine(pathsConfig, new InboxScanner(), dateResolver, new Sha256Hasher(), hashIndex,
                new ImageDimensionsReader(), new NioMediaStore(), ProgressPort.NO_OP);
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
