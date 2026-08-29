package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.MonthRange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitEngineTest {

    @Test
    void movesSortedFileIntoLibraryAtTheSameRelativeStructure(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        final CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.All());

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(summary.byBucket()).containsEntry(LibraryBucket.PHOTOS, 1);
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/a.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/a.jpg"))).isFalse();
    }

    @Test
    void appendsAnIndexRowForEveryCommittedFile(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path source = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(source, "keeper");
        final String expectedHash = new Sha256Hasher().hash(source);
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));

        commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(hashIndex.load())
                .containsOnlyKeys(expectedHash)
                .containsEntry(expectedHash, List.of(libraryRoot.resolve("Photos/2019/06/a.jpg")));
    }

    @Test
    void yearScopeCommitsOnlyMatchingYear(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/in.jpg"), "in");
        writeFile(root.resolve("Sorted/Photos/2020/01/out.jpg"), "out");

        final CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null));

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/in.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/01/out.jpg"))).isTrue();
    }

    @Test
    void monthRangeNarrowsWithinAYear(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Videos/2019/07/in.mp4"), "in");
        writeFile(root.resolve("Sorted/Videos/2019/05/out.mp4"), "out");

        final CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019,
                new MonthRange(6, 8)));

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(libraryRoot.resolve("Videos/2019/07/in.mp4"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Videos/2019/05/out.mp4"))).isTrue();
    }

    @Test
    void allScopeIncludesUndatedFunnyButYearScopeExcludesIt(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Funny/joke.jpg"), "funny");

        final CommitSummary yearSummary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null));
        assertThat(yearSummary.committed()).isEqualTo(0);
        assertThat(Files.exists(root.resolve("Sorted/Funny/joke.jpg"))).isTrue();

        final CommitSummary allSummary = commitEngine(root, libraryRoot).commit(new CommitScope.All());
        assertThat(allSummary.committed()).isEqualTo(1);
        assertThat(allSummary.byBucket()).containsEntry(LibraryBucket.FUNNY, 1);
        assertThat(Files.exists(libraryRoot.resolve("Funny/joke.jpg"))).isTrue();
    }

    @Test
    void prunesSortedDirectoriesLeftEmptyAfterCommit(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        commitEngine(root, libraryRoot).commit(new CommitScope.All());

        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06"))).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos"))).isFalse();
    }

    @Test
    void committingAnEmptyScopeIsANoOp(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        Files.createDirectories(root.resolve("Sorted"));
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));

        final CommitSummary summary = commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(summary.committed()).isEqualTo(0);
        assertThat(summary.byBucket()).isEmpty();
        assertThat(Files.exists(root.resolve("logs/library-hashes.csv"))).isFalse();
    }

    @Test
    void committingWithNoSortedDirectoryAtAllFailsLoudly(@TempDir final Path root) {
        final Path libraryRoot = root.resolve("Library"); // Sorted itself is never created

        assertThatThrownBy(() -> commitEngine(root, libraryRoot).commit(new CommitScope.All()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aCrashAfterTheFirstMoveLeavesItsIndexRowDurableAndResumeFinishesTheSecond(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        final Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        final Path second = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(first, "keeper1");
        writeFile(second, "keeper2");
        final var hashIndex = new CsvLibraryHashIndex(SettingsFixture.workingRoot(root));
        final String firstHash = new Sha256Hasher().hash(first);
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first file's move-and-index but before the loop reaches the second.
        final CommitEngine crashingEngine = commitEngine(root, libraryRoot, hashIndex, new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.commit(new CommitScope.All()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        final Path firstDest = libraryRoot.resolve("Photos/2019/06/a.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash);
        // The crash lands on the second file's move itself, before it touches the filesystem at
        // all. It's still sitting in Sorted, exactly where an ordinary resumed commit would find
        // it.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/b.jpg"))).isFalse();

        final CommitSummary resumeSummary = commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(resumeSummary.committed()).isEqualTo(1);
        final Path secondDest = libraryRoot.resolve("Photos/2019/06/b.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash, new Sha256Hasher().hash(secondDest));
    }

    @Test
    void progressCallbackTicksOnceForEveryFileWalkedRegardlessOfScope(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/in.jpg"), "in");
        writeFile(root.resolve("Sorted/Photos/2020/01/out.jpg"), "out");

        final List<String> ticks = new ArrayList<>();
        commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    @Test
    void cancelMidCommitStopsEarlyLeavingAlreadyCommittedFilesCommittedAndSummaryPartial(@TempDir final Path root)
            throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/07/b.jpg"), "b");

        // Cancels once the first file's move has already ticked, so the loop stops before the
        // second file is even looked at. Scan order across the two files isn't guaranteed, so the
        // assertions below check counts rather than which specific file committed first.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final CommitSummary summary = commitEngine(root, libraryRoot)
                .commit(new CommitScope.All(), cancelAfterFirstTick, cancelled::get);

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(summary.cancelled()).isTrue();
        assertThat(summary.leftBehind()).isEqualTo(1);
        assertThat(regularFileCount(root.resolve("Sorted"))).isEqualTo(1);
        assertThat(regularFileCount(libraryRoot)).isEqualTo(1);
    }

    @Test
    void aStopAfterTheLastInScopeFileDidNotStopShort(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2020/06/b.jpg"), "b");

        // The double sorts its listing, so 2019 is walked before 2020 and the stop lands on the
        // out-of-scope tail with every in-scope file already moved. NioMediaStore does not sort,
        // so a real store here would pass on Windows and flake elsewhere.
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final ProgressCallback cancelAfterFirstTick = (current, _) -> cancelled.set(current == 1);

        final CommitSummary summary = commitEngine(root, libraryRoot,
                new CsvLibraryHashIndex(SettingsFixture.workingRoot(root)), new FailingAfterMoves(2))
                .commit(new CommitScope.Year(2019, null), cancelAfterFirstTick, cancelled::get);

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(summary.cancelled()).isFalse();
    }

    @Test
    void aTransferThatNeverLandedIsNeverMovedIntoTheLibrary(@TempDir final Path root) throws IOException {
        final Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "a");
        writeFile(root.resolve("Sorted/Photos/2019/06/b.jpg.sluice-part"), "half of b");

        final CommitSummary summary = commitEngine(root, libraryRoot)
                .commit(new CommitScope.All(), ProgressCallback.NO_OP, CancellationSignal.NEVER);

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(regularFileCount(libraryRoot)).isEqualTo(1);
        assertThat(root.resolve("Sorted/Photos/2019/06/b.jpg.sluice-part")).exists();
    }

    private static long regularFileCount(final Path root) throws IOException {
        try (final Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static CommitEngine commitEngine(final Path repoRoot, final Path libraryRoot) {
        return commitEngine(repoRoot, libraryRoot,
                new CsvLibraryHashIndex(SettingsFixture.workingRoot(repoRoot)));
    }

    private static CommitEngine commitEngine(final Path repoRoot, final Path libraryRoot,
                                             final CsvLibraryHashIndex hashIndex) {
        return commitEngine(repoRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    private static CommitEngine commitEngine(final Path repoRoot, final Path libraryRoot,
                                             final CsvLibraryHashIndex hashIndex,
                                             final MediaStore mediaStore) {
        final var pathsConfig = SettingsFixture.pathsConfig(repoRoot, libraryRoot, repoRoot.resolve("Inbox"));
        return new CommitEngine(pathsConfig, mediaStore, new Sha256Hasher(), hashIndex);
    }

    private static void writeFile(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls -
    // CommitEngine's own move step. Deterministically simulates a crash mid-run. listFiles() sorts
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
        public Path move(final Path source, final Path destDir, final CancellationSignal stop) {
            if (this.movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            this.movesUntilFailure--;
            return this.delegate.move(source, destDir, stop);
        }

        @Override
        public Path resolveDestination(final Path source, final Path destDir) {
            return this.delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(final Path source, final Path destination, final CancellationSignal stop) {
            return this.delegate.moveTo(source, destination, stop);
        }

        @Override
        public Path copy(final Path source, final Path destDir, final CancellationSignal stop) {
            return this.delegate.copy(source, destDir, stop);
        }

        @Override
        public Path copyTo(final Path source, final Path destination, final CancellationSignal stop) {
            return this.delegate.copyTo(source, destination, stop);
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
