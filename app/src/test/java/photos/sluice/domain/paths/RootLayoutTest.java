package photos.sluice.domain.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RootLayoutTest {

    @Test
    void threeSeparateFoldersAreLegal(@TempDir final Path root) {
        assertThat(RootLayout.violations(root.resolve("work"), root.resolve("library"), root.resolve("inbox")))
                .isEmpty();
    }

    // The documented layout. Inbox, Sorted, Review and Duplicates all sit under the working root, so
    // a rule banning every overlap would reject the arrangement the app ships with.
    @Test
    void theInboxMayLiveInsideTheWorkingRoot(@TempDir final Path root) {
        final Path workingRoot = root.resolve("work");

        assertThat(RootLayout.violations(workingRoot, root.resolve("library"), workingRoot.resolve("Inbox")))
                .isEmpty();
    }

    @Test
    void theLibraryMayLiveInsideTheWorkingRoot(@TempDir final Path root) {
        final Path workingRoot = root.resolve("work");

        assertThat(RootLayout.violations(workingRoot, workingRoot.resolve("Library"), root.resolve("inbox")))
                .isEmpty();
    }

    @Test
    void aLibraryInsideTheInboxIsAnOverlap(@TempDir final Path root) {
        final Path inbox = root.resolve("inbox");

        assertThat(RootLayout.violations(root.resolve("work"), inbox.resolve("library"), inbox))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
    }

    @Test
    void anInboxInsideTheLibraryIsAnOverlap(@TempDir final Path root) {
        final Path library = root.resolve("library");

        assertThat(RootLayout.violations(root.resolve("work"), library, library.resolve("inbox")))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
    }

    // Equal paths contain each other in both directions, so the pair still has to come back once,
    // and in the same order as the nested cases above.
    @Test
    void oneFolderNamedAsBothLibraryAndInboxIsAnOverlap(@TempDir final Path root) {
        final Path shared = root.resolve("shared");

        assertThat(RootLayout.violations(root.resolve("work"), shared, shared))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
    }

    @Test
    void oneFolderNamedAsBothTheWorkingRootAndTheInboxIsAnOverlap(@TempDir final Path root) {
        final Path shared = root.resolve("shared");

        assertThat(RootLayout.violations(shared, root.resolve("library"), shared))
                .containsExactly(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX));
    }

    @Test
    void aWorkingRootInsideTheInboxIsAnOverlap(@TempDir final Path root) {
        final Path inbox = root.resolve("inbox");

        assertThat(RootLayout.violations(inbox.resolve("work"), root.resolve("library"), inbox))
                .containsExactly(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX));
    }

    // A layout can break both rules at once, and a surface marking fields needs to hear about both.
    @Test
    void aLayoutBreakingBothRulesReportsBoth(@TempDir final Path root) {
        final Path inbox = root.resolve("inbox");

        assertThat(RootLayout.violations(inbox.resolve("work"), inbox.resolve("library"), inbox))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX),
                        new Overlap(PathRole.REPO_ROOT, PathRole.INBOX));
    }
}
