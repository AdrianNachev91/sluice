package photos.sluice;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import photos.sluice.config.CliLauncher;
import photos.sluice.config.ConfigDirLocator;
import photos.sluice.config.UiLauncher;

import java.nio.file.Path;

/**
 * The application's entry point. It locates the user's optional config file before anything starts.
 * That file's properties then load as an ordinary Spring config-import source, alongside the
 * bundled defaults and any OS environment variables.
 *
 * <p>Sluice started with nothing to do opens its window. Started with arguments, it does what they
 * say and exits. That is the whole of the choice made here. It is made before either surface is
 * built, so a machine with no desktop never loads a window toolkit.
 *
 * <p>Each surface is launched through a class in the wiring layer rather than named directly. Only
 * that layer is allowed to name a concrete effect implementation, and this class sits outside it.
 */
@SpringBootApplication
public class SluiceApplication {

    /**
     * Starts the application, importing the user's config file if present.
     *
     * @param args {@link String}[] command-line arguments
     */
    static void main(final String[] args) {
        final Path configFile = ConfigDirLocator.configFile(System.getProperty("os.name"), System.getenv());
        if (opensTheWindow(args)) {
            UiLauncher.launch(configFile, args);
            return;
        }
        System.exit(CliLauncher.run(configFile, args));
    }

    /**
     * Whether this invocation opens the window rather than running a command.
     *
     * <p>Its own method so the choice can be tested. Neither branch returns: one blocks until a
     * window closes and the other ends the process, so nothing can drive {@code main} itself and
     * still make an assertion.
     *
     * @param args {@link String}[] command-line arguments
     * @return boolean true when the desktop window should open
     */
    static boolean opensTheWindow(final String[] args) {
        return args.length == 0;
    }
}
