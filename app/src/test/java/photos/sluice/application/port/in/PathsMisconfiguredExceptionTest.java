package photos.sluice.application.port.in;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathsMisconfiguredExceptionTest {

    @Test
    void anUnsetRootIsNamedByItsProperty() {
        assertThat(new PathsMisconfiguredException(List.of(new NotConfigured(PathRole.WORKING_ROOT)))
                .getMessage()).contains("sluice.paths.repo-root", "is not set");
    }

    @Test
    void unusableTextIsQuotedBackAsItWasSet() {
        assertThat(new PathsMisconfiguredException(List.of(new NotAPath(PathRole.INBOX, "C:|photos")))
                .getMessage()).contains("sluice.paths.inbox", "C:|photos", "not a usable folder path");
    }

    @Test
    void aMissingFolderIsNamedByItsResolvedPath() {
        final Path missing = Path.of("gone").toAbsolutePath();

        assertThat(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, missing)))
                .getMessage()).contains("sluice.paths.library-root", missing.toString(), "not a folder");
    }

    // The two sentences are compared rather than substring-matched. A negative quoting
    // NotADirectory's own wording would stop proving anything the moment that sentence is reworded,
    // and would go on passing.
    @Test
    void anUnreadableFolderIsNamedAsThereRatherThanMissing() {
        final Path unreadable = Path.of("share").toAbsolutePath();

        final String message = message(new Unreadable(PathRole.INBOX, unreadable));

        assertThat(message).contains("sluice.paths.inbox", unreadable.toString(), "could not be read");
        assertThat(message).isNotEqualTo(message(new NotADirectory(PathRole.INBOX, unreadable)));
    }

    @Test
    void anOverlapNamesBothRoots() {
        assertThat(new PathsMisconfiguredException(
                List.of(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX))).getMessage())
                .contains("sluice.paths.library-root", "sluice.paths.inbox", "must not contain each other");
    }

    @Test
    void everyViolationIsWordedRatherThanOnlyTheFirst() {
        final var exception = new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.WORKING_ROOT), new NotConfigured(PathRole.INBOX)));

        assertThat(exception.getMessage()).contains("sluice.paths.repo-root", "sluice.paths.inbox");
    }

    @Test
    void anUnusableRootsRefusalIsAnIllegalStateException() {
        assertThat(new PathsMisconfiguredException(List.of(new NotConfigured(PathRole.INBOX))))
                .isInstanceOf(IllegalStateException.class);
    }

    // The typed list is the contract a screen reads. The message is only what a log gets.
    @Test
    void theViolationsAreCarriedThroughAsValues() {
        final var violation = new NotConfigured(PathRole.INBOX);

        assertThat(new PathsMisconfiguredException(List.of(violation)).violations()).containsExactly(violation);
    }

    private static String message(final PathViolation violation) {
        return new PathsMisconfiguredException(List.of(violation)).getMessage();
    }
}
