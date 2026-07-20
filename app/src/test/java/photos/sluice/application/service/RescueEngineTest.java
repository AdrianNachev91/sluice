package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvLibraryHashIndex;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.adapter.fs.Sha256Hasher;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.PathsProperties;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static DateSource noDate() {
        return (_, _) -> Optional.empty();
    }

    private static RescueEngine rescueEngine(Path repoRoot, Path libraryRoot, DateSource exifSource, DateSource filenameSource) {
        return rescueEngine(repoRoot, libraryRoot, new CsvLibraryHashIndex(repoRoot.resolve("logs/library-hashes.csv")),
                exifSource, filenameSource);
    }

    private static RescueEngine rescueEngine(Path repoRoot, Path libraryRoot, CsvLibraryHashIndex hashIndex,
            DateSource exifSource, DateSource filenameSource) {
        var pathsConfig = new PathsConfig(
                new PathsProperties(repoRoot.toString(), libraryRoot.toString(), repoRoot.resolve("Inbox").toString()));
        var rescueDateResolver = new RescueDateResolver(exifSource, filenameSource);
        return new RescueEngine(pathsConfig, new NioMediaStore(), new Sha256Hasher(), hashIndex, rescueDateResolver);
    }

    private static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
