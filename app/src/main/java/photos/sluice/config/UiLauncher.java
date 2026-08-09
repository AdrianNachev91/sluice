package photos.sluice.config;

import javafx.application.Application;
import photos.sluice.adapter.ui.view.SluiceFxApplication;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Starts the desktop window. This lives in the wiring layer because it names a concrete window
 * class. Naming a concrete effect implementation is the one thing only the wiring layer may do.
 *
 * <p>The config file's location arrives here as a path and leaves as an ordinary Spring
 * command-line argument. So the window is handed a plain argument list and never works out where
 * anything lives on disk itself. It could not: the code that answers that question lives in this
 * package, and an adapter may not reach into it.
 */
public final class UiLauncher {

    private static final String CONFIG_IMPORT_ARG = "--spring.config.import=optional:file:";

    /**
     * Prevents instantiation of this static utility class.
     */
    private UiLauncher() {
    }

    /**
     * Opens the desktop window and blocks until it closes.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     * @param args {@link String}[] the command-line arguments to pass on
     */
    public static void launch(final Path configFile, final String[] args) {
        Application.launch(SluiceFxApplication.class, springArgs(configFile, args));
    }

    /**
     * Builds the argument list the window forwards to Spring: the config-file import first, then
     * whatever the user passed. The user's arguments come last so an explicitly passed property
     * still wins.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     * @param args {@link String}[] the command-line arguments to pass on
     * @return {@link String}[] the arguments to start Spring with
     */
    static String[] springArgs(final Path configFile, final String[] args) {
        return Stream.concat(Stream.of(CONFIG_IMPORT_ARG + configFile), Stream.of(args))
                .toArray(String[]::new);
    }
}
