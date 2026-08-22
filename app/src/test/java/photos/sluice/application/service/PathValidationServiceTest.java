package photos.sluice.application.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.config.SettingsFixture;
import photos.sluice.config.SettingsHolder;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PathValidationServiceTest {

    @Test
    void threeExistingSeparateFoldersAreUsable(@TempDir final Path root) throws IOException {
        assertThat(validate(directories(root, "work", "library", "inbox"))).isEmpty();
    }

    // The state a fresh install starts in, before anyone has chosen a folder.
    @Test
    void nothingConfiguredNamesAllThreeRoots() {
        assertThat(validate(new PathSettings(null, null, null)))
                .containsExactly(new NotConfigured(PathRole.WORKING_ROOT), new NotConfigured(PathRole.LIBRARY_ROOT),
                        new NotConfigured(PathRole.INBOX));
    }

    @Test
    void aBlankValueCountsAsNotConfigured(@TempDir final Path root) throws IOException {
        final PathSettings paths = directories(root, "work", "library", "inbox");

        assertThat(validate(new PathSettings("   ", paths.libraryRoot(), paths.inbox())))
                .containsExactly(new NotConfigured(PathRole.WORKING_ROOT));
    }

    // A user typing a folder by hand can produce text no filesystem could ever hold. It has to come
    // back as a refusal rather than as the exception Path.of throws, since this runs at startup.
    @Test
    void textThatIsNotAPathAtAllIsRefusedRatherThanThrown(@TempDir final Path root) throws IOException {
        final PathSettings paths = directories(root, "work", "library", "inbox");
        final String unusable = "photos" + (char) 0;

        assertThat(validate(new PathSettings(unusable, paths.libraryRoot(), paths.inbox())))
                .containsExactly(new NotAPath(PathRole.WORKING_ROOT, unusable));
    }

    @Test
    void aFolderThatIsNotThereNamesItsResolvedPath(@TempDir final Path root) throws IOException {
        final PathSettings paths = directories(root, "work", "library", "inbox");
        final Path missing = root.resolve("gone");

        assertThat(validate(new PathSettings(missing.toString(), paths.libraryRoot(), paths.inbox())))
                .containsExactly(new NotADirectory(PathRole.WORKING_ROOT, missing));
    }

    @Test
    void aFileWhereAFolderShouldBeIsNotADirectory(@TempDir final Path root) throws IOException {
        final PathSettings paths = directories(root, "work", "library", "inbox");
        final Path file = Files.createFile(root.resolve("not-a-folder"));

        assertThat(validate(new PathSettings(paths.repoRoot(), paths.libraryRoot(), file.toString())))
                .containsExactly(new NotADirectory(PathRole.INBOX, file));
    }

    // A directory that is there and refuses to resolve. Injected at the port rather than staged on
    // disk. What produces it is a share that goes away between the two calls, or a permission
    // denial partway down. Neither is reproducible, nor even the same failure on every platform.
    // The same fixture with nothing refusing is the one threeExistingSeparateFoldersAreUsable uses.
    @Test
    void aFolderThatIsThereAndCannotBeResolvedIsRecordedRatherThanThrown(@TempDir final Path root)
            throws IOException {
        final Path work = Files.createDirectories(root.resolve("work"));
        final Path library = Files.createDirectories(root.resolve("library"));
        final Path inbox = Files.createDirectories(root.resolve("inbox"));
        final var paths = new PathSettings(work.toString(), library.toString(), inbox.toString());

        assertThat(new PathValidationService(refusingToResolve(library), unconfiguredHolder()).violations(paths))
                .containsExactly(new Unreadable(PathRole.LIBRARY_ROOT, library));
    }

    // The fixture does overlap, proved on the first line, and no Overlap is reported once a root
    // stops resolving.
    @Test
    void noOverlapIsClaimedWhileARootCouldNotBeResolved(@TempDir final Path root) throws IOException {
        final Path inbox = Files.createDirectories(root.resolve("inbox"));
        final Path library = Files.createDirectories(inbox.resolve("library"));
        final Path workingRoot = Files.createDirectories(root.resolve("work"));
        final var paths = new PathSettings(workingRoot.toString(), library.toString(), inbox.toString());
        assertThat(validate(paths)).containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));

        assertThat(new PathValidationService(refusingToResolve(workingRoot), unconfiguredHolder()).violations(paths))
                .containsExactly(new Unreadable(PathRole.WORKING_ROOT, workingRoot));
    }

    @Test
    void aRelativeValueIsResolvedBeforeBeingReported() {
        assertThat(validate(new PathSettings("no-such-folder", null, null)))
                .contains(new NotADirectory(PathRole.WORKING_ROOT, Path.of("no-such-folder").toAbsolutePath()));
    }

    @Test
    void overlappingFoldersAreRefused(@TempDir final Path root) throws IOException {
        final Path inbox = Files.createDirectories(root.resolve("inbox"));
        final Path library = Files.createDirectories(inbox.resolve("library"));
        final Path workingRoot = Files.createDirectories(root.resolve("work"));

        assertThat(validate(new PathSettings(workingRoot.toString(), library.toString(), inbox.toString())))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
    }

    // Two names reaching one folder is exactly the case a string comparison would miss, and it is
    // what makes resolving through the filesystem load-bearing rather than tidy.
    @Test
    void twoNamesForOneFolderStillOverlap(@TempDir final Path root) throws IOException {
        final Path library = Files.createDirectories(root.resolve("library"));
        final Path alias = root.resolve("library-alias");
        try {
            Files.createSymbolicLink(alias, library);
        } catch (final IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not supported in this environment: " + e.getMessage());
        }
        final Path workingRoot = Files.createDirectories(root.resolve("work"));

        assertThat(validate(new PathSettings(workingRoot.toString(), library.toString(), alias.toString())))
                .containsExactly(new Overlap(PathRole.LIBRARY_ROOT, PathRole.INBOX));
    }

    // Two folders cannot be compared while one of them is a folder nobody has chosen. Reporting an
    // overlap here would mean comparing against a path that resolved to nothing.
    @Test
    void noOverlapIsClaimedWhileARootIsStillMissing(@TempDir final Path root) throws IOException {
        final Path shared = Files.createDirectories(root.resolve("shared"));

        assertThat(validate(new PathSettings(null, shared.toString(), shared.toString())))
                .containsExactly(new NotConfigured(PathRole.WORKING_ROOT));
    }

    @Test
    void theRootsInForceAreTheOnesRead(@TempDir final Path root) throws IOException {
        final PathSettings paths = directories(root, "work", "library", "inbox");
        final var holder = SettingsFixture.holder(paths.repoRoot(), paths.libraryRoot(), paths.inbox());
        final var service = new PathValidationService(new NioMediaStore(), holder);
        assertThat(service.violationsInForce()).isEmpty();

        holder.apply(SettingsFixture.settings(new PathSettings(null, paths.libraryRoot(), paths.inbox())));

        assertThat(service.violationsInForce()).containsExactly(new NotConfigured(PathRole.WORKING_ROOT));
    }

    private static PathSettings directories(final Path root, final String repoRoot, final String libraryRoot,
                                            final String inbox) throws IOException {
        return new PathSettings(Files.createDirectories(root.resolve(repoRoot)).toString(),
                Files.createDirectories(root.resolve(libraryRoot)).toString(),
                Files.createDirectories(root.resolve(inbox)).toString());
    }

    // Checking candidates never reads the settings in force, so the holder here names nothing. The
    // one test that does read them builds its own.
    private static List<PathViolation> validate(final PathSettings paths) {
        return new PathValidationService(new NioMediaStore(), unconfiguredHolder()).violations(paths);
    }

    private static SettingsHolder unconfiguredHolder() {
        return new SettingsHolder(SettingsFixture.settings(new PathSettings(null, null, null)));
    }

    // The real store everywhere except the one call the failure is about. Every other root in the
    // fixture still gets the verdict a real filesystem gives it.
    private static MediaReader refusingToResolve(final Path refused) {
        return new NioMediaStore() {
            @Override
            public Optional<Path> realDirectory(final Path path) {
                if (path.equals(refused)) {
                    throw new UncheckedIOException(new IOException("the share went away"));
                }
                return super.realDirectory(path);
            }
        };
    }
}
