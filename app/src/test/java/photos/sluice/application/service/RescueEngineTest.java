package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescueEngineTest {

    @Test
    void rescuesADatedLeafFolderIntoTheLibraryAndRemovesTheFolder(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        final RescueSummary summary = rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("2019-06");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).isEmpty();
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @Test
    void rescuedFileAppendsAnIndexRow(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path source = root.resolve("Review/2019-06/IMG_1.jpg");
        writeFile(source, "keeper");
        final String expectedHash = new Sha256Hasher().hash(source);
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));

        rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate()).rescue("2019-06");

        assertThat(hashIndex.load())
                .containsOnlyKeys(expectedHash)
                .containsEntry(expectedHash, List.of(libraryRoot.resolve("Photos/2019/06/IMG_1.jpg")));
    }

    @Test
    void aFileWithNoPlausibleDateIsSkippedInPlaceAndKeepsTheFolderAlive(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path source = root.resolve("Review/Food/IMG_1.jpg");
        writeFile(source, "keeper");

        final RescueSummary summary = rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("Food");

        assertThat(summary.rescued()).isEqualTo(0);
        assertThat(summary.skipped()).containsExactly("IMG_1.jpg");
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(source)).isTrue();
    }

    @Test
    void aRescueStoppedWithFilesStillToReachSaysItStoppedShort(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "one");
        writeFile(root.resolve("Review/Food/IMG_2.jpg"), "two");

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, libraryRoot, someDate(), someDate())
                .rescue("Food", cancelAfterFirstTick, cancelled::get);

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isTrue();
    }

    // A stop the pass outran leaves the Review folder dissolved, where a real one keeps it whole.
    @Test
    void aRescueWhoseStopArrivedAfterTheLastFileDidNotStopShort(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "one");

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, libraryRoot, someDate(), someDate())
                .rescue("Food", cancelAfterFirstTick, cancelled::get);

        assertThat(cancelled).isTrue();
        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    void aTransferThatNeverLandedIsNeverRescuedIntoTheLibrary(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "whole");
        final Path part = root.resolve("Review/Food/IMG_2.jpg.sluice-part");
        writeFile(part, "half");

        final RescueSummary summary = rescueEngine(root, libraryRoot, someDate(), someDate()).rescue("Food");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
        assertThat(Files.exists(part)).isTrue();
    }

    @Test
    void reasonsFileIsRemovedWhenTheWholeFolderDissolves(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/2019-06/_reasons.txt"), "IMG_1.jpg - low-res");

        rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("2019-06");

        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @Test
    void reasonsFileSurvivesWhenAFileIsSkipped(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        final Path reasonsFile = root.resolve("Review/Food/_reasons.txt");
        writeFile(reasonsFile, "IMG_1.jpg - low-res");

        rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("Food");

        assertThat(Files.exists(reasonsFile)).isTrue();
        assertThat(Files.exists(root.resolve("Review/Food"))).isTrue();
    }

    @Test
    void escapingTheReviewRootIsRejected(@TempDir final Path root) {
        final Path libraryRoot = root.resolve("Library");

        assertThatThrownBy(() -> rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("../Sorted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrashAfterTheFirstRescueLeavesItsIndexRowDurableAndResumeFinishesTheSecond(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path first = root.resolve("Review/2019-06/a.jpg");
        final Path second = root.resolve("Review/2019-06/b.jpg");
        writeFile(first, "keeper1");
        writeFile(second, "keeper2");
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));
        final String firstHash = new Sha256Hasher().hash(first);
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first file's move-and-index but before the loop reaches the second.
        final RescueEngine crashingEngine = rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate(),
                new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.rescue("2019-06"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        final Path firstDest = libraryRoot.resolve("Photos/2019/06/a.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash);
        // The crash lands on the second file's move itself, before it touches the filesystem at
        // all. It's still sitting in Review, and the folder never reached the dissolve check.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/b.jpg"))).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();

        final RescueSummary resumeSummary = rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate()).rescue(
                "2019-06");

        assertThat(resumeSummary.rescued()).isEqualTo(1);
        assertThat(resumeSummary.folderRemoved()).isTrue();
        final Path secondDest = libraryRoot.resolve("Photos/2019/06/b.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash, new Sha256Hasher().hash(secondDest));
    }

    @Test
    void progressCallbackTicksOnceForEveryFileRegardlessOfWhetherItIsRescuedOrSkipped(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        // "Food" carries no folder-derived date, so each file's outcome depends solely on
        // dateForOnly - one rescued, one skipped. A dated leaf folder ("2019-06") would differ:
        // folderDate() would rescue both regardless of what the DateSource says.
        writeFile(root.resolve("Review/Food/rescued.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/skipped.jpg"), "no date");

        final List<String> ticks = new ArrayList<>();
        final RescueSummary summary = rescueEngine(root, libraryRoot, dateForOnly("rescued.jpg"), noDate())
                .rescue("Food", (current, total) -> ticks.add(current + "/" + total));

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).containsExactly("skipped.jpg");
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancelMidRescueStopsEarlyLeavingAlreadyRescuedFilesRescuedAndTheFolderIntact(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/2019-06/a.jpg"), "a");
        writeFile(root.resolve("Review/2019-06/b.jpg"), "b");
        final Path reasonsFile = root.resolve("Review/2019-06/_reasons.txt");
        writeFile(reasonsFile, "b.jpg - low-res");

        // Cancels once the first entry has ticked, so the loop stops before the other two are
        // even looked at. Neither photo is skipped - both would resolve a date via the folder
        // name. Scan order across the three entries isn't guaranteed, so exactly which one ticks
        // first varies; every assertion below holds regardless of which it is.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final RescueSummary summary = rescueEngine(root, libraryRoot, noDate(), noDate())
                .rescue("2019-06", cancelAfterFirstTick, cancelled::get);

        assertThat(summary.rescued()).isBetween(0, 1);
        assertThat(summary.skipped()).isEmpty();
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();
        // The actual claim: a pass that reached only none-skipped entries must not be treated as
        // safe to dissolve. Pre-fix, the gate only checked "nothing skipped" and would still fire
        // here, deleting this marker even though two of the three entries were never reached.
        assertThat(Files.exists(reasonsFile)).isTrue();
        assertThat(regularFileCount(root.resolve("Review/2019-06"))).isEqualTo(3 - summary.rescued());
        assertThat(regularFileCount(libraryRoot)).isEqualTo(summary.rescued());
    }

    // regularFileCount()'s own regression test: the cancellation test above calls it on
    // libraryRoot, which NioMediaStore only creates as a side effect of an actual move. When scan
    // order puts _reasons.txt first, zero files get rescued before cancellation and libraryRoot
    // never exists on disk. A bare Files.walk() throws NoSuchFileException in exactly that case.
    @Test
    void regularFileCountTreatsAMissingDirectoryAsZeroFilesInsteadOfThrowing(@TempDir final Path root) throws IOException {
        assertThat(regularFileCount(root.resolve("never-created"))).isZero();
    }

    // Treats a missing root as zero files rather than throwing NoSuchFileException. Callers may
    // pass a directory (e.g. the library root) that a test scenario never ends up creating.
    private static long regularFileCount(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static DateSource noDate() {
        return (_, _) -> Optional.empty();
    }

    private static DateSource someDate() {
        return (_, _) -> Optional.of(LocalDateTime.of(2019, 6, 15, 12, 0));
    }

    private static DateSource dateForOnly(final String filename) {
        return (file, _) -> file.path().getFileName().toString().equals(filename)
                ? Optional.of(LocalDateTime.of(2019, 6, 15, 12, 0))
                : Optional.empty();
    }

    private static RescueEngine rescueEngine(final Path repoRoot, final Path libraryRoot, final DateSource exifSource
            , final DateSource filenameSource) {
        return rescueEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(SettingsFixture.workingRoot(repoRoot)),
                exifSource, filenameSource);
    }

    private static RescueEngine rescueEngine(final Path repoRoot, final Path libraryRoot,
                                             final CsvLibraryHashIndex hashIndex,
                                             final DateSource exifSource, final DateSource filenameSource) {
        return rescueEngine(repoRoot, libraryRoot, hashIndex, exifSource, filenameSource, new NioMediaStore());
    }

    private static RescueEngine rescueEngine(final Path repoRoot, final Path libraryRoot,
                                             final CsvLibraryHashIndex hashIndex,
                                             final DateSource exifSource, final DateSource filenameSource,
                                             final MediaStore mediaStore) {
        final var pathsConfig = SettingsFixture.pathsConfig(repoRoot, libraryRoot, repoRoot.resolve("Inbox"));
        final var rescueDateResolver = new RescueDateResolver(exifSource, filenameSource);
        return new RescueEngine(pathsConfig, mediaStore, new Sha256Hasher(), hashIndex, rescueDateResolver);
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls -
    // RescueEngine's own move step. Deterministically simulates a crash mid-run. listFiles() sorts
    // the delegate's result so which file counts as "first" doesn't depend on filesystem walk order.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();

        @Override
        public Walk listFilesTolerating(final Path root) {
            return this.delegate.listFilesTolerating(root);
        }

        private int movesUntilFailure;

        FailingAfterMoves(final int movesUntilFailure) {
            this.movesUntilFailure = movesUntilFailure;
        }

        @Override
        public List<Path> listFiles(final Path root) {
            return this.delegate.listFiles(root).stream().sorted().toList();
        }

        @Override
        public List<Path> listChildDirectories(final Path root) {
            return this.delegate.listChildDirectories(root);
        }

        @Override
        public Instant lastModifiedTime(final Path path) {
            return this.delegate.lastModifiedTime(path);
        }

        @Override
        public Optional<Path> realDirectory(final Path path) {
            return this.delegate.realDirectory(path);
        }

        @Override
        public Path realFile(final Path path) {
            return this.delegate.realFile(path);
        }

        @Override
        public Path move(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress watching) {
            if (this.movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            this.movesUntilFailure--;
            return this.delegate.move(source, destDir, stop, watching);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.moveTo(source, destination, stop, watching);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.copy(source, destDir, stop, watching);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop,
                final TransferProgress watching) {
            return this.delegate.copyTo(source, destination, stop, watching);
        }

        @Override
        public void delete(final Path path) {
            this.delegate.delete(path);
        }

        @Override
        public void ensureDirectory(final Path dir) {
            this.delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(final Path path) {
            return this.delegate.exists(path);
        }

        @Override
        public long size(final Path path) {
            return this.delegate.size(path);
        }

        @Override
        public void appendLine(final Path file, final String line) {
            this.delegate.appendLine(file, line);
        }

        @Override
        public void write(final Path file, final String content) {
            this.delegate.write(file, content);
        }

        @Override
        public List<String> readLines(final Path file) {
            return this.delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(final Path root) {
            this.delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(final Path dir) {
            this.delegate.removeIfEmptyOfFiles(dir);
        }
    }
}
