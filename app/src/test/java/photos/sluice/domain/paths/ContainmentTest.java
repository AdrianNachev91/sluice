package photos.sluice.domain.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainmentTest {

    @Test
    void aFileUnderTheRootIsContained(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root, root.resolve("Photos/2019/06/a.jpg"))).isTrue();
    }

    @Test
    void aFileBesideTheRootIsNotContained(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root.resolve("Sorted"), root.resolve("Library/a.jpg"))).isFalse();
    }

    @Test
    void theRootIsNotContainedInItself(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root, root)).isFalse();
    }

    @Test
    void aPathClimbingOutOfTheRootWithParentReferencesIsNotContained(@TempDir final Path root) {
        final Path sorted = root.resolve("Sorted");

        assertThat(Containment.strictlyUnder(sorted, sorted.resolve("../Library/a.jpg"))).isFalse();
    }

    @Test
    void aPathUsingParentReferencesButLandingInsideIsContained(@TempDir final Path root) {
        final Path sorted = root.resolve("Sorted");

        assertThat(Containment.strictlyUnder(sorted, sorted.resolve("Photos/../Videos/a.mp4"))).isTrue();
    }

    // A relative path anchors to the process directory, which a temp dir never is.
    @Test
    void aRelativePathIsNotContainedInAnUnrelatedAbsoluteRoot(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root, Path.of("Sorted/Photos/2019/06/a.jpg"))).isFalse();
    }

    // Both sides anchor to the same process directory, so they still relate. Absolutizing only the
    // candidate answers false here, since an absolute path cannot start with a relative one.
    @Test
    void aRelativeRootStillContainsARelativePathBeneathIt() {
        assertThat(Containment.strictlyUnder(Path.of("Sorted"), Path.of("Sorted/a.jpg"))).isTrue();
    }

    // Normalizing only the candidate answers false here, since the candidate has nothing to strip
    // and the root's own parent reference survives into the comparison.
    @Test
    void aRootSpelledWithAParentReferenceStillContainsItsMembers(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root.resolve("Sorted/../Sorted"), root.resolve("Sorted/a.jpg")))
                .isTrue();
    }

    @Test
    void aSiblingRootSharingANamePrefixIsNotContained(@TempDir final Path root) {
        assertThat(Containment.strictlyUnder(root.resolve("Sorted"), root.resolve("SortedOld/a.jpg"))).isFalse();
    }
}
