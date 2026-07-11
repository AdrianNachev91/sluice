package photos.sluice.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PathsConfig {

    private final PathsProperties properties;

    public PathsConfig(PathsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        requireExistingDirectory("sluice.paths.repo-root", properties.repoRoot());
        requireExistingDirectory("sluice.paths.library-root", properties.libraryRoot());
        requireExistingDirectory("sluice.paths.inbox", properties.inbox());
    }

    public Path repoRoot() {
        return resolve(properties.repoRoot());
    }

    public Path libraryRoot() {
        return resolve(properties.libraryRoot());
    }

    public Path inbox() {
        return resolve(properties.inbox());
    }

    public Path logs() {
        return repoRoot().resolve("logs");
    }

    private static Path resolve(String raw) {
        return Path.of(raw).toAbsolutePath().normalize();
    }

    private static void requireExistingDirectory(String property, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    property + " is not configured. Set it in Settings or via the " + property + " property.");
        }
        Path path = resolve(raw);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    property + " (" + path + ") does not exist. Set it in Settings or via the "
                            + property + " property.");
        }
    }
}
