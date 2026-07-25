package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.model.MonthRange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitEngineTest {

    @Test
    void movesSortedFileIntoLibraryAtTheSameRelativeStructure(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.All());

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(summary.byBucket()).containsEntry(LibraryBucket.PHOTOS, 1);
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/a.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06/a.jpg"))).isFalse();
    }

    @Test
    void appendsAnIndexRowForEveryCommittedFile(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path source = root.resolve("Sorted/Photos/2019/06/a.jpg");
        writeFile(source, "keeper");
        String expectedHash = new Sha256Hasher().hash(source);
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));

        commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(hashIndex.load())
                .containsOnlyKeys(expectedHash)
                .containsEntry(expectedHash, List.of(libraryRoot.resolve("Photos/2019/06/a.jpg")));
    }

    @Test
    void yearScopeCommitsOnlyMatchingYear(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/in.jpg"), "in");
        writeFile(root.resolve("Sorted/Photos/2020/01/out.jpg"), "out");

        CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null));

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/in.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Photos/2020/01/out.jpg"))).isTrue();
    }

    @Test
    void monthRangeNarrowsWithinAYear(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Videos/2019/07/in.mp4"), "in");
        writeFile(root.resolve("Sorted/Videos/2019/05/out.mp4"), "out");

        CommitSummary summary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, new MonthRange(6, 8)));

        assertThat(summary.committed()).isEqualTo(1);
        assertThat(Files.exists(libraryRoot.resolve("Videos/2019/07/in.mp4"))).isTrue();
        assertThat(Files.exists(root.resolve("Sorted/Videos/2019/05/out.mp4"))).isTrue();
    }

    @Test
    void allScopeIncludesUndatedFunnyButYearScopeExcludesIt(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Funny/joke.jpg"), "funny");

        CommitSummary yearSummary = commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null));
        assertThat(yearSummary.committed()).isEqualTo(0);
        assertThat(Files.exists(root.resolve("Sorted/Funny/joke.jpg"))).isTrue();

        CommitSummary allSummary = commitEngine(root, libraryRoot).commit(new CommitScope.All());
        assertThat(allSummary.committed()).isEqualTo(1);
        assertThat(allSummary.byBucket()).containsEntry(LibraryBucket.FUNNY, 1);
        assertThat(Files.exists(libraryRoot.resolve("Funny/joke.jpg"))).isTrue();
    }

    @Test
    void prunesSortedDirectoriesLeftEmptyAfterCommit(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/a.jpg"), "keeper");

        commitEngine(root, libraryRoot).commit(new CommitScope.All());

        assertThat(Files.exists(root.resolve("Sorted/Photos/2019/06"))).isFalse();
        assertThat(Files.exists(root.resolve("Sorted/Photos"))).isFalse();
    }

    @Test
    void committingAnEmptyScopeIsANoOp(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Files.createDirectories(root.resolve("Sorted"));
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));

        CommitSummary summary = commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(summary.committed()).isEqualTo(0);
        assertThat(summary.byBucket()).isEmpty();
        assertThat(Files.exists(root.resolve("logs/library-hashes.csv"))).isFalse();
    }

    @Test
    void committingWithNoSortedDirectoryAtAllFailsLoudly(@TempDir Path root) {
        Path libraryRoot = root.resolve("Library"); // Sorted itself is never created

        assertThatThrownBy(() -> commitEngine(root, libraryRoot).commit(new CommitScope.All()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aCrashAfterTheFirstMoveLeavesItsIndexRowDurableAndResumeFinishesTheSecond(@TempDir Path root)
            throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path first = root.resolve("Sorted/Photos/2019/06/a.jpg");
        Path second = root.resolve("Sorted/Photos/2019/06/b.jpg");
        writeFile(first, "keeper1");
        writeFile(second, "keeper2");
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        String firstHash = new Sha256Hasher().hash(first);
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first file's move-and-index but before the loop reaches the second.
        CommitEngine crashingEngine = commitEngine(root, libraryRoot, hashIndex, new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.commit(new CommitScope.All()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        Path firstDest = libraryRoot.resolve("Photos/2019/06/a.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash);
        // The crash lands on the second file's move itself, before it touches the filesystem at all
        // - it's still sitting in Sorted, exactly where an ordinary resumed commit would find it.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/b.jpg"))).isFalse();

        CommitSummary resumeSummary = commitEngine(root, libraryRoot, hashIndex).commit(new CommitScope.All());

        assertThat(resumeSummary.committed()).isEqualTo(1);
        Path secondDest = libraryRoot.resolve("Photos/2019/06/b.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash, new Sha256Hasher().hash(secondDest));
    }

    @Test
    void progressCallbackTicksOnceForEveryFileWalkedRegardlessOfScope(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Sorted/Photos/2019/06/in.jpg"), "in");
        writeFile(root.resolve("Sorted/Photos/2020/01/out.jpg"), "out");

        List<String> ticks = new ArrayList<>();
        commitEngine(root, libraryRoot).commit(new CommitScope.Year(2019, null),
                (current, total) -> ticks.add(current + "/" + total));

        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private static CommitEngine commitEngine(Path repoRoot, Path libraryRoot) {
        return commitEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv")));
    }

    private static CommitEngine commitEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex) {
        return commitEngine(repoRoot, libraryRoot, hashIndex, new NioMediaStore());
    }

    private static CommitEngine commitEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex,
            MediaStore mediaStore) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
        return new CommitEngine(pathsConfig, mediaStore, new Sha256Hasher(), hashIndex);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls -
    // CommitEngine's own move step. Deterministically simulates a crash mid-run. listFiles() sorts
    // the delegate's result so which file counts as "first" doesn't depend on filesystem walk order.
    private static final class FailingAfterMoves implements MediaStore {
        private final MediaStore delegate = new NioMediaStore();
        private int movesUntilFailure;

        FailingAfterMoves(int movesUntilFailure) {
            this.movesUntilFailure = movesUntilFailure;
        }

        @Override
        public List<Path> listFiles(Path root) {
            return delegate.listFiles(root).stream().sorted().toList();
        }

        @Override
        public Instant lastModifiedTime(Path path) {
            return delegate.lastModifiedTime(path);
        }

        @Override
        public Path move(Path source, Path destDir) {
            if (movesUntilFailure <= 0) {
                throw new RuntimeException("simulated crash");
            }
            movesUntilFailure--;
            return delegate.move(source, destDir);
        }

        @Override
        public Path resolveDestination(Path source, Path destDir) {
            return delegate.resolveDestination(source, destDir);
        }

        @Override
        public Path moveTo(Path source, Path destination) {
            return delegate.moveTo(source, destination);
        }

        @Override
        public Path copy(Path source, Path destDir) {
            return delegate.copy(source, destDir);
        }

        @Override
        public void delete(Path path) {
            delegate.delete(path);
        }

        @Override
        public void ensureDirectory(Path dir) {
            delegate.ensureDirectory(dir);
        }

        @Override
        public boolean exists(Path path) {
            return delegate.exists(path);
        }

        @Override
        public long size(Path path) {
            return delegate.size(path);
        }

        @Override
        public void appendLine(Path file, String line) {
            delegate.appendLine(file, line);
        }

        @Override
        public void write(Path file, String content) {
            delegate.write(file, content);
        }

        @Override
        public List<String> readLines(Path file) {
            return delegate.readLines(file);
        }

        @Override
        public void removeEmptyDirectories(Path root) {
            delegate.removeEmptyDirectories(root);
        }

        @Override
        public void removeIfEmptyOfFiles(Path dir) {
            delegate.removeIfEmptyOfFiles(dir);
        }
    }
}
