package photos.sluice.config;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Builds the argument list a launcher starts Spring with.
 *
 * <p>The config file's location arrives as a path and leaves as an ordinary Spring command-line
 * argument. So what is started is handed a plain argument list, and never works out where anything
 * lives on disk itself.
 */
final class SpringLaunch {

    private static final String CONFIG_IMPORT_ARG = "--spring.config.import=optional:file:";

    /**
     * Prevents instantiation of this static utility class.
     */
    private SpringLaunch() {
    }

    /**
     * The config-file import first, then whatever the user passed. The user's arguments come last
     * so an explicitly passed property still wins. The import is optional because a fresh install
     * has no config file and the app has to start anyway.
     *
     * <p>Everything given is passed on, a command parser's own flags included. A flag Spring does
     * not recognise becomes a property nothing reads, which costs nothing.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     * @param args {@link String}[] the command-line arguments to pass on
     * @return {@link String}[] the arguments to start Spring with
     */
    static String[] argsWithConfigImport(final Path configFile, final String[] args) {
        return Stream.concat(Stream.of(CONFIG_IMPORT_ARG + configFile), Stream.of(args))
                .toArray(String[]::new);
    }
}
