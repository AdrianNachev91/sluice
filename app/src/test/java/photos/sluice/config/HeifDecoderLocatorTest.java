package photos.sluice.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class HeifDecoderLocatorTest {

    @Test
    void withNothingInstalledTheSettingIsWhatRuns() {
        assertThat(HeifDecoderLocator.command("heif-convert", null)).isEqualTo("heif-convert");
    }

    @Test
    void anInstallationWithoutADecoderLeavesTheSettingAlone(@TempDir final Path installation) {
        assertThat(HeifDecoderLocator.command("heif-convert", installation.toString()))
                .isEqualTo("heif-convert");
    }

    @Test
    void theInstalledDecoderIsPreferredOverLookingUpTheName(@TempDir final Path installation) throws IOException {
        final Path installed = decoder(installation, "heif-convert");

        assertThat(HeifDecoderLocator.command("heif-convert", installation.toString()))
                .isEqualTo(installed.toString());
    }

    @Test
    void theWindowsExecutableAnswersToThePlainName(@TempDir final Path installation) throws IOException {
        final Path installed = decoder(installation, "heif-convert.exe");

        assertThat(HeifDecoderLocator.command("heif-convert", installation.toString()))
                .isEqualTo(installed.toString());
    }

    // The relative spelling is the one an installation could swallow: resolved against the
    // installed decoder's own directory, "./heif-convert" lands on the file this fixture puts there.
    @Test
    void aRelativePathIsNotResolvedAgainstTheInstallation(@TempDir final Path installation) throws IOException {
        decoder(installation, "heif-convert");

        assertThat(HeifDecoderLocator.command("./heif-convert", installation.toString()))
                .isEqualTo("./heif-convert");
    }

    // The fixture holds a decoder under the Windows spelling alone.
    @Test
    void aPathIsRunAsWrittenRatherThanCompletedToAWindowsName(@TempDir final Path installation) throws IOException {
        final Path own = Files.createDirectories(installation.resolve("own"));
        Files.createFile(own.resolve("heif-convert.exe"));
        final String configured = own.resolve("heif-convert").toString();

        assertThat(HeifDecoderLocator.command(configured, installation.toString())).isEqualTo(configured);
    }

    // Windows refuses : * ? " < > | in a name, so Path.of throws on one before anything can be
    // looked up. Linux takes all of them, so this asserts only what both agree on.
    @Test
    void aSettingThisFilesystemWillNotReadAsANameIsStillWhatRuns(@TempDir final Path installation)
            throws IOException {
        decoder(installation, "heif-convert");

        assertThat(HeifDecoderLocator.command("heif:convert", installation.toString()))
                .isEqualTo("heif:convert");
    }

    // A drive-relative path carries no separator, and resolving one against the installation drops
    // the drive rather than refusing it. Windows only drops it when both sit on the same drive, so
    // the drive comes from the fixture rather than a literal. Linux reads the string as a bare name.
    @Test
    void aDriveRelativePathIsAPathToo(@TempDir final Path installation) throws IOException {
        decoder(installation, "heif-convert");
        final Path root = installation.getRoot();
        final String configured = (root == null ? "" : root.toString().replace("\\", "")) + "heif-convert";

        assertThat(HeifDecoderLocator.command(configured, installation.toString())).isEqualTo(configured);
    }

    // A packaging fault that loses the executable bit leaves a file the machine will not run.
    // Windows has no such bit, so this asserts nothing there.
    @Test
    @EnabledIf("posixPermissionsAreReal")
    void aDecoderTheMachineWillNotRunIsNotTakenForOne(@TempDir final Path installation) throws IOException {
        final Path directory = Files.createDirectories(installation.resolve("heif/bin"));
        final Path installed = Files.createFile(directory.resolve("heif-convert"));
        Files.setPosixFilePermissions(installed, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(HeifDecoderLocator.command("heif-convert", installation.toString()))
                .isEqualTo("heif-convert");
    }

    @SuppressWarnings("unused")
    static boolean posixPermissionsAreReal() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void aDirectoryWithTheDecodersNameIsNotTakenForIt(@TempDir final Path installation) throws IOException {
        Files.createDirectories(installation.resolve("heif/bin/heif-convert"));

        assertThat(HeifDecoderLocator.command("heif-convert", installation.toString()))
                .isEqualTo("heif-convert");
    }

    // The installer ships an executable, and a new file is 644 where permissions are real. Without
    // the bit set here the lookup finds nothing on Linux and everything on Windows, where any
    // readable file answers to isExecutable.
    private static Path decoder(final Path installation, final String name) throws IOException {
        final Path directory = installation.resolve("heif/bin");
        Files.createDirectories(directory);
        return runnable(Files.createFile(directory.resolve(name)));
    }

    static Path runnable(final Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return file;
    }
}
