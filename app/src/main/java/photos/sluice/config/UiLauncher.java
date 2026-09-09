package photos.sluice.config;

import javafx.application.Application;
import photos.sluice.adapter.fs.YamlConfigFileRepair;
import photos.sluice.adapter.ui.UiBootstrap;
import photos.sluice.adapter.ui.view.SluiceFxApplication;

import java.nio.file.Path;

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
        install(configFile);
        Application.launch(SluiceFxApplication.class, SpringLaunch.importing(configFile, args));
    }

    /**
     * Hands the window what it needs if its own startup fails. Both pieces name the same config
     * file this launch imports, so neither can be pointed at a file the other does not know about.
     *
     * <p>Done before the launch because the toolkit builds the window class itself, and a context
     * that failed to start has nothing left to inject from. Callable on its own so a harness that
     * opens the window some other way can still prepare the process.
     *
     * @param configFile {@link Path} the user's config file, which need not exist
     */
    public static void install(final Path configFile) {
        UiBootstrap.install(new SpringStartupFailureClassifier(configFile), new YamlConfigFileRepair(configFile));
    }
}
