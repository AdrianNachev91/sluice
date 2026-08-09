package photos.sluice;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import photos.sluice.config.ConfigDirLocator;
import photos.sluice.config.UiLauncher;

import java.nio.file.Path;

/**
 * The application's entry point. It locates the user's optional config file before anything starts.
 * That file's properties then load as an ordinary Spring config-import source, alongside the
 * bundled defaults and any OS environment variables.
 *
 * <p>It hands the launch to {@link UiLauncher} rather than naming a window class itself. Only the
 * wiring layer is allowed to name a concrete effect implementation, and this class sits outside it.
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
        UiLauncher.launch(configFile, args);
    }
}
