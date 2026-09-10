package photos.sluice.config;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Works out which HEIF decoder to run: the one installed alongside the app, or whatever the
 * settings name.
 *
 * <p>A setting that names a path is somebody's own choice of decoder and is left alone. A bare
 * command name is looked up in the installation first. A name that is not installed falls back to
 * the setting, so it still reaches whatever the machine has.
 */
public final class HeifDecoderLocator {

    /**
     * Where the installer puts the decoder, relative to the directory holding the app's own files.
     */
    private static final String INSIDE_THE_INSTALLATION = "heif/bin";

    /**
     * Prevents instantiation of this static utility class.
     */
    private HeifDecoderLocator() {
    }

    /**
     * Where the decoder is looked for, so a test can hold the packaging to the same answer.
     *
     * @return {@link String} the directory, relative to the app's own files
     */
    static String installationSubdirectory() {
        return INSIDE_THE_INSTALLATION;
    }

    /**
     * The decoder command to run.
     *
     * @param configured {@link String} what the settings name, a bare command or a path
     * @param installation {@link String} the directory holding the app's own files. Null when
     *     nothing is installed, which is how it runs from a build.
     * @return {@link String} the installed decoder's path where there is one to use, and the
     *     configured value otherwise
     */
    public static String command(final String configured, final @Nullable String installation) {
        if (installation == null) {
            return configured;
        }
        try {
            if (namesAPath(configured)) {
                return configured;
            }
            return installed(Path.of(installation).resolve(INSIDE_THE_INSTALLATION), configured)
                    .orElse(configured);
        } catch (final InvalidPathException _) {
            // A value this filesystem refuses to read as a name cannot be one the installer wrote,
            // so there is nothing to search for. It runs as typed and fails where it always did,
            // rather than stopping the app from opening at all.
            return configured;
        }
    }

    /**
     * Whether the setting picks a particular file rather than naming a command to look up.
     *
     * @param configured {@link String} what the settings name
     * @return boolean true when it names a path
     * @throws InvalidPathException if this filesystem will not read the setting as a name
     */
    private static boolean namesAPath(final String configured) {
        final Path path = Path.of(configured);
        return path.getRoot() != null || path.getNameCount() > 1;
    }

    /**
     * The installed decoder, under either the plain name or the one Windows executables carry.
     *
     * @param directory {@link Path} where the installer puts it
     * @param command {@link String} the bare command name to look for
     * @return {@link Optional} the file's path, or empty when neither name is there to run
     */
    private static Optional<String> installed(final Path directory, final String command) {
        return Stream.of(command, command + ".exe")
                .map(directory::resolve)
                .filter(HeifDecoderLocator::runnable)
                .findFirst()
                .map(Path::toString);
    }

    /**
     * Whether a candidate is a file this machine would actually run.
     *
     * <p>Windows answers true for anything readable, so the permission half of this is POSIX only.
     *
     * @param candidate {@link Path} the file to judge
     * @return boolean true when it is a regular file and executable
     */
    private static boolean runnable(final Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
