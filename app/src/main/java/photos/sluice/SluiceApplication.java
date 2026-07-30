package photos.sluice;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import photos.sluice.config.ConfigDirLocator;

import java.nio.file.Path;

/**
 * The application's entry point. It locates the user's optional config file before Spring starts.
 * That makes its properties available as an ordinary Spring config-import source, alongside the
 * bundled defaults and any OS environment variables.
 */
@SpringBootApplication
public class SluiceApplication {

    /**
     * Starts the Spring Boot application, importing the user's config file if present.
     *
     * @param args {@link String}[] command-line arguments
     */
    static void main(final String[] args) {
        final Path configFile = ConfigDirLocator.locate(System.getProperty("os.name"), System.getenv())
                .resolve("config.yml");
        new SpringApplicationBuilder(SluiceApplication.class)
                .properties("spring.config.import=optional:file:" + configFile)
                .run(args);
    }
}
