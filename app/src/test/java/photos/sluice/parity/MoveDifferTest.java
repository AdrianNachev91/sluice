package photos.sluice.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MoveDifferTest {

    private final MoveDiffer differ = new MoveDiffer();

    @Test
    void identicalTreesDiffToEmpty(@TempDir final Path root) throws IOException {
        final Path treeA = write(root.resolve("a"), "Photos/2021/03/img.jpg");
        final Path treeB = write(root.resolve("b"), "Photos/2021/03/img.jpg");

        final MoveDiffer.Diff diff = this.differ.diffTrees(treeA, treeB);

        assertThat(diff.identical()).isTrue();
    }

    @Test
    void extraFileOnOneSideIsReported(@TempDir final Path root) throws IOException {
        final Path treeA = write(root.resolve("a"), "Photos/2021/03/img.jpg", "Review/Unsorted/stray.jpg");
        final Path treeB = write(root.resolve("b"), "Photos/2021/03/img.jpg");

        final MoveDiffer.Diff diff = this.differ.diffTrees(treeA, treeB);

        assertThat(diff.identical()).isFalse();
        assertThat(diff.onlyInA()).containsExactly("Review/Unsorted/stray.jpg");
        assertThat(diff.onlyInB()).isEmpty();
    }

    @Test
    void relativePathsAreForwardSlashNormalized(@TempDir final Path root) throws IOException {
        final Path tree = write(root.resolve("a"), "Videos/2019/07/clip.mp4");

        final Set<String> relative = this.differ.relativeFilePaths(tree);

        assertThat(relative).containsExactly("Videos/2019/07/clip.mp4");
    }

    @Test
    void missingRootIsTreatedAsEmptyTree(@TempDir final Path root) {
        final Set<String> relative = this.differ.relativeFilePaths(root.resolve("never-created"));

        assertThat(relative).isEmpty();
    }

    @Test
    void diffOfPlainSetsIsSymmetric() {
        final MoveDiffer.Diff diff = this.differ.diff(Set.of("only-a.jpg", "shared.jpg"), Set.of("shared.jpg", "only-b.jpg"));

        assertThat(diff.onlyInA()).containsExactly("only-a.jpg");
        assertThat(diff.onlyInB()).containsExactly("only-b.jpg");
    }

    private static Path write(final Path root, final String... relativeFiles) throws IOException {
        for (final String relative : relativeFiles) {
            final Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content");
        }
        return root;
    }
}
