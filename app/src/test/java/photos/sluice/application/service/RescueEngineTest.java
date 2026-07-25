package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RescueEngineTest {

    @Test
    void rescuesADatedLeafFolderIntoTheLibraryAndRemovesTheFolder(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");

        RescueSummary summary = rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("2019-06");

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).isEmpty();
        assertThat(summary.folderRemoved()).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/IMG_1.jpg"))).isTrue();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @Test
    void rescuedFileAppendsAnIndexRow(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path source = root.resolve("Review/2019-06/IMG_1.jpg");
        writeFile(source, "keeper");
        String expectedHash = new Sha256Hasher().hash(source);
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));

        rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate()).rescue("2019-06");

        assertThat(hashIndex.load())
                .containsOnlyKeys(expectedHash)
                .containsEntry(expectedHash, List.of(libraryRoot.resolve("Photos/2019/06/IMG_1.jpg")));
    }

    @Test
    void aFileWithNoPlausibleDateIsSkippedInPlaceAndKeepsTheFolderAlive(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path source = root.resolve("Review/Food/IMG_1.jpg");
        writeFile(source, "keeper");

        RescueSummary summary = rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("Food");

        assertThat(summary.rescued()).isEqualTo(0);
        assertThat(summary.skipped()).containsExactly("IMG_1.jpg");
        assertThat(summary.folderRemoved()).isFalse();
        assertThat(Files.exists(source)).isTrue();
    }

    @Test
    void reasonsFileIsRemovedWhenTheWholeFolderDissolves(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/2019-06/IMG_1.jpg"), "keeper");
        writeFile(root.resolve("Review/2019-06/_reasons.txt"), "IMG_1.jpg - low-res");

        rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("2019-06");

        assertThat(Files.exists(root.resolve("Review/2019-06"))).isFalse();
    }

    @Test
    void reasonsFileSurvivesWhenAFileIsSkipped(@TempDir Path root) throws IOException {
        Path libraryRoot = root.resolve("Library");
        writeFile(root.resolve("Review/Food/IMG_1.jpg"), "keeper");
        Path reasonsFile = root.resolve("Review/Food/_reasons.txt");
        writeFile(reasonsFile, "IMG_1.jpg - low-res");

        rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("Food");

        assertThat(Files.exists(reasonsFile)).isTrue();
        assertThat(Files.exists(root.resolve("Review/Food"))).isTrue();
    }

    @Test
    void escapingTheReviewRootIsRejected(@TempDir Path root) {
        Path libraryRoot = root.resolve("Library");

        assertThatThrownBy(() -> rescueEngine(root, libraryRoot, noDate(), noDate()).rescue("../Sorted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCrashAfterTheFirstRescueLeavesItsIndexRowDurableAndResumeFinishesTheSecond(@TempDir Path root)
            throws IOException {
        Path libraryRoot = root.resolve("Library");
        Path first = root.resolve("Review/2019-06/a.jpg");
        Path second = root.resolve("Review/2019-06/b.jpg");
        writeFile(first, "keeper1");
        writeFile(second, "keeper2");
        var hashIndex = new CsvLibraryHashIndex(root.resolve("logs/library-hashes.csv"));
        String firstHash = new Sha256Hasher().hash(first);
        // Allows exactly one move to succeed, then throws - simulating a process crash right after
        // the first file's move-and-index but before the loop reaches the second.
        RescueEngine crashingEngine = rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate(),
                new FailingAfterMoves(1));

        assertThatThrownBy(() -> crashingEngine.rescue("2019-06"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated crash");

        Path firstDest = libraryRoot.resolve("Photos/2019/06/a.jpg");
        assertThat(Files.exists(firstDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash);
        // The crash lands on the second file's move itself, before it touches the filesystem at all
        // - it's still sitting in Review, and the folder never reached the dissolve check.
        assertThat(Files.exists(second)).isTrue();
        assertThat(Files.exists(libraryRoot.resolve("Photos/2019/06/b.jpg"))).isFalse();
        assertThat(Files.exists(root.resolve("Review/2019-06"))).isTrue();

        RescueSummary resumeSummary = rescueEngine(root, libraryRoot, hashIndex, noDate(), noDate()).rescue("2019-06");

        assertThat(resumeSummary.rescued()).isEqualTo(1);
        assertThat(resumeSummary.folderRemoved()).isTrue();
        Path secondDest = libraryRoot.resolve("Photos/2019/06/b.jpg");
        assertThat(Files.exists(secondDest)).isTrue();
        assertThat(hashIndex.load()).containsOnlyKeys(firstHash, new Sha256Hasher().hash(secondDest));
    }

    @Test
    void progressCallbackTicksOnceForEveryFileRegardlessOfWhetherItIsRescuedOrSkipped(@TempDir Path root)
            throws IOException {
        Path libraryRoot = root.resolve("Library");
        // "Food" carries no folder-derived date, so each file's outcome depends solely on
        // dateForOnly - one rescued, one skipped - unlike a dated leaf folder ("2019-06"), where
        // folderDate() would rescue both regardless of what the DateSource says.
        writeFile(root.resolve("Review/Food/rescued.jpg"), "keeper");
        writeFile(root.resolve("Review/Food/skipped.jpg"), "no date");

        List<String> ticks = new ArrayList<>();
        RescueSummary summary = rescueEngine(root, libraryRoot, dateForOnly("rescued.jpg"), noDate())
                .rescue("Food", (current, total) -> ticks.add(current + "/" + total));

        assertThat(summary.rescued()).isEqualTo(1);
        assertThat(summary.skipped()).containsExactly("skipped.jpg");
        assertThat(ticks).containsExactly("1/2", "2/2");
    }

    private static DateSource noDate() {
        return (_, _) -> Optional.empty();
    }

    private static DateSource dateForOnly(String filename) {
        return (file, _) -> file.path().getFileName().toString().equals(filename)
                ? Optional.of(LocalDateTime.of(2019, 6, 15, 12, 0))
                : Optional.empty();
    }

    private static RescueEngine rescueEngine(Path repoRoot, Path libraryRoot, DateSource exifSource, DateSource filenameSource) {
        return rescueEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv")),
                exifSource, filenameSource);
    }

    private static RescueEngine rescueEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex,
            DateSource exifSource, DateSource filenameSource) {
        return rescueEngine(repoRoot, libraryRoot, hashIndex, exifSource, filenameSource, new NioMediaStore());
    }

    private static RescueEngine rescueEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex,
            DateSource exifSource, DateSource filenameSource, MediaStore mediaStore) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
        var rescueDateResolver = new RescueDateResolver(exifSource, filenameSource);
        return new RescueEngine(pathsConfig, mediaStore, new Sha256Hasher(), hashIndex, rescueDateResolver);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Wraps the real NioMediaStore but throws after a fixed number of successful move() calls -
    // RescueEngine's own move step. Deterministically simulates a crash mid-run. listFiles() sorts
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
