package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioMediaStoreTest {

    private final NioMediaStore store = new NioMediaStore();

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
}
