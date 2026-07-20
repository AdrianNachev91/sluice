package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioMediaStoreTest {

    private final NioMediaStore store = new NioMediaStore();

    @Test
    void listFilesReturnsEveryRegularFileRecursivelyButNoDirectories(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("top.jpg"), "top");
        Path nested = Files.createDirectories(root.resolve("2019").resolve("06"));
        Files.writeString(nested.resolve("nested.jpg"), "nested");

        List<Path> files = store.listFiles(root);

        assertThat(files).containsExactlyInAnyOrder(root.resolve("top.jpg"), nested.resolve("nested.jpg"));
    }

    @Test
    void listFilesOnAnEmptyDirectoryReturnsEmpty(@TempDir Path root) {
        assertThat(store.listFiles(root)).isEmpty();
    }

    @Test
    void listFilesOnAMissingRootWrapsIoExceptionUnchecked(@TempDir Path root) {
        Path missing = root.resolve("does-not-exist");

        assertThatThrownBy(() -> store.listFiles(missing)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void moveCreatesDestDirAndPlacesFileUnderOriginalName(@TempDir Path root) throws IOException {
        Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "photo bytes", StandardCharsets.UTF_8);
        Path destDir = root.resolve("Sorted").resolve("2019").resolve("06");

        Path dest = store.move(source, destDir);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234.jpg"));
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("photo bytes");
    }

    @Test
    void moveResolvesCollisionByAppendingNumberBeforeExtension(@TempDir Path root) throws IOException {
        Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("IMG_1234.jpg"), "existing");
        Files.writeString(destDir.resolve("IMG_1234 (2).jpg"), "existing2");
        Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "incoming");

        Path dest = store.move(source, destDir);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234 (3).jpg"));
        assertThat(Files.readString(dest)).isEqualTo("incoming");
        assertThat(Files.readString(destDir.resolve("IMG_1234.jpg"))).isEqualTo("existing");
        assertThat(Files.readString(destDir.resolve("IMG_1234 (2).jpg"))).isEqualTo("existing2");
    }

    @Test
    void moveHandlesFilenameWithNoExtension(@TempDir Path root) throws IOException {
        Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("README"), "existing");
        Path source = root.resolve("README");
        Files.writeString(source, "incoming");

        Path dest = store.move(source, destDir);

        assertThat(dest).isEqualTo(destDir.resolve("README (2)"));
    }

    @Test
    void copyLeavesSourceInPlace(@TempDir Path root) throws IOException {
        Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "photo bytes");
        Path destDir = root.resolve("dest");

        Path dest = store.copy(source, destDir);

        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.readString(dest)).isEqualTo("photo bytes");
    }

    @Test
    void copyResolvesCollisionSameAsMove(@TempDir Path root) throws IOException {
        Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve("IMG_1234.jpg"), "existing");
        Path source = root.resolve("IMG_1234.jpg");
        Files.writeString(source, "incoming");

        Path dest = store.copy(source, destDir);

        assertThat(dest).isEqualTo(destDir.resolve("IMG_1234 (2).jpg"));
        assertThat(Files.readString(destDir.resolve("IMG_1234.jpg"))).isEqualTo("existing");
    }

    @Test
    void collisionOnADotfileAppendsSuffixToTheWholeName(@TempDir Path root) throws IOException {
        Path destDir = Files.createDirectories(root.resolve("dest"));
        Files.writeString(destDir.resolve(".gitignore"), "existing");
        Path source = root.resolve(".gitignore");
        Files.writeString(source, "incoming");

        Path dest = store.move(source, destDir);

        assertThat(dest).isEqualTo(destDir.resolve(".gitignore (2)"));
    }

    @Test
    void moveMissingSourceWrapsIoExceptionUnchecked(@TempDir Path root) {
        Path missing = root.resolve("does-not-exist.jpg");
        Path destDir = root.resolve("dest");

        assertThatThrownBy(() -> store.move(missing, destDir)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void copyMissingSourceWrapsIoExceptionUnchecked(@TempDir Path root) {
        Path missing = root.resolve("does-not-exist.jpg");
        Path destDir = root.resolve("dest");

        assertThatThrownBy(() -> store.copy(missing, destDir)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void deleteRemovesFile(@TempDir Path root) throws IOException {
        Path file = root.resolve("junk.jpg");
        Files.writeString(file, "junk");

        store.delete(file);

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void deleteMissingFileWrapsIoExceptionUnchecked(@TempDir Path root) {
        Path missing = root.resolve("does-not-exist.jpg");

        assertThatThrownBy(() -> store.delete(missing)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void ensureDirectoryCreatesNestedPathAndIsIdempotent(@TempDir Path root) {
        Path dir = root.resolve("a").resolve("b").resolve("c");

        store.ensureDirectory(dir);
        store.ensureDirectory(dir);

        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void existsIsTrueForARealFileAndFalseOtherwise(@TempDir Path root) throws IOException {
        Path file = root.resolve("present.jpg");
        Files.writeString(file, "bytes");
        Path missing = root.resolve("absent.jpg");

        assertThat(store.exists(file)).isTrue();
        assertThat(store.exists(missing)).isFalse();
    }

    @Test
    void sizeReturnsByteCount(@TempDir Path root) throws IOException {
        Path file = root.resolve("file.jpg");
        Files.writeString(file, "12345", StandardCharsets.UTF_8);

        assertThat(store.size(file)).isEqualTo(5);
    }

    @Test
    void appendLineCreatesFileOnFirstCallThenAppendsOnSubsequentCalls(@TempDir Path root) {
        Path file = root.resolve("_reasons.txt");

        store.appendLine(file, "IMG_0001.jpg - low-res");
        store.appendLine(file, "IMG_0002.jpg - unsorted-implausible-date");

        assertThat(Files.exists(file)).isTrue();
        List<String> lines = readLines(file);
        assertThat(lines).containsExactly(
                "IMG_0001.jpg - low-res", "IMG_0002.jpg - unsorted-implausible-date");
    }

    @Test
    void removeEmptyDirectoriesCollapsesNestedEmptyChainBottomUp(@TempDir Path root) throws IOException {
        Path nested = Files.createDirectories(root.resolve("Takeout").resolve("Google Photos").resolve("2019-06"));

        store.removeEmptyDirectories(root);

        assertThat(Files.exists(nested)).isFalse();
        assertThat(Files.exists(root.resolve("Takeout").resolve("Google Photos"))).isFalse();
        assertThat(Files.exists(root.resolve("Takeout"))).isFalse();
        assertThat(Files.exists(root)).isTrue();
    }

    @Test
    void removeEmptyDirectoriesLeavesADirectoryWithAFileInPlace(@TempDir Path root) throws IOException {
        Path keptDir = Files.createDirectories(root.resolve("keep"));
        Files.writeString(keptDir.resolve("leftover.txt"), "not media");
        Path emptyDir = Files.createDirectories(root.resolve("empty"));

        store.removeEmptyDirectories(root);

        assertThat(Files.exists(keptDir)).isTrue();
        assertThat(Files.exists(emptyDir)).isFalse();
    }

    @Test
    void removeEmptyDirectoriesLeavesAnAncestorInPlaceWhenADeeperSiblingStillHasAFile(@TempDir Path root) throws IOException {
        Path emptyBranch = Files.createDirectories(root.resolve("album").resolve("empty-sub"));
        Path fileBranch = Files.createDirectories(root.resolve("album").resolve("has-file"));
        Files.writeString(fileBranch.resolve("leftover.txt"), "not media");

        store.removeEmptyDirectories(root);

        assertThat(Files.exists(emptyBranch)).isFalse();
        assertThat(Files.exists(fileBranch)).isTrue();
        assertThat(Files.exists(root.resolve("album"))).isTrue();
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
