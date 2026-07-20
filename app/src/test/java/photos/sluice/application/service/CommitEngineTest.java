package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.model.SortScope.MonthRange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static CommitEngine commitEngine(Path repoRoot, Path libraryRoot) {
        return commitEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv")));
    }

    private static CommitEngine commitEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
        return new CommitEngine(pathsConfig, new NioMediaStore(), new Sha256Hasher(), hashIndex);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
