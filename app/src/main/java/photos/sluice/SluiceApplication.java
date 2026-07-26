package photos.sluice;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import photos.sluice.config.ConfigDirLocator;

import java.nio.file.Path;

@SpringBootApplication
public class SluiceApplication {

    /**
     * Starts the Spring Boot application, importing the user's config file if present.
     *
     * @param args {@link String}[] command-line arguments
     */
    static void main(String[] args) {
        Path configFile = ConfigDirLocator.locate(System.getProperty("os.name"), System.getenv())
                .resolve("config.yml");
        new SpringApplicationBuilder(SluiceApplication.class)
                .properties("spring.config.import=optional:file:" + configFile)
                .run(args);
    }
}
