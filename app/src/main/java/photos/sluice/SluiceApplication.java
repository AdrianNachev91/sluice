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
 * <p>{@code sluice app} opens the window. Anything else runs as a command and exits, and nothing at
 * all prints the help, since somebody who typed the name into a terminal was asking what it does.
 * That is the whole of the choice made here. It is made before either surface is built, so a machine
 * with no desktop never loads a window toolkit.
 *
 * <p>Each surface is launched through a class in the wiring layer rather than named directly. Only
 * that layer is allowed to name a concrete effect implementation, and this class sits outside it.
 */
@SpringBootApplication
public class SluiceApplication {

    /**
     * The verb that opens the window. The command surface spells the same name for its help entry
     * and its refusal, and a test holds the two equal.
     */
    static final String APP = "app";

    /**
     * What an empty invocation is turned into.
     */
    private static final String HELP = "--help";

    /**
     * Handed to the window, which takes nothing from a command line.
     */
    private static final String[] NO_ARGUMENTS = new String[0];

    /**
     * Starts the application, importing the user's config file if present.
     *
     * @param args {@link String}[] command-line arguments
     */
    public static void main(final String[] args) {
        final Path configFile = ConfigDirLocator.configFile(System.getProperty("os.name"), System.getenv());
        if (opensTheWindow(args)) {
            UiLauncher.launch(configFile, NO_ARGUMENTS);
            return;
        }
        System.exit(CliLauncher.run(configFile, commandIn(args)));
    }

    /**
     * Whether this invocation opens the window rather than running a command.
     *
     * <p>Its own method so the choice can be tested. Neither branch returns: one blocks until a
     * window closes and the other ends the process, so nothing can drive {@code main} itself and
     * still make an assertion.
     *
     * <p>The verb alone, since the window takes no settings from a command line. The verb carrying
     * anything else goes to the parser, which holds the words that refusal is given in.
     *
     * @param args {@link String}[] command-line arguments
     * @return boolean true when the desktop window should open
     */
    static boolean opensTheWindow(final String[] args) {
        return args.length == 1 && APP.equals(args[0]);
    }

    /**
     * What the command surface is asked to run.
     *
     * <p>An empty invocation asks for help.
     *
     * @param args {@link String}[] command-line arguments
     * @return {@link String}[] the arguments to run
     */
    static String[] commandIn(final String[] args) {
        return args.length == 0 ? new String[]{HELP} : args;
    }
}
