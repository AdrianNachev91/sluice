package photos.sluice.config;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Component
public class PathsConfig implements PathsPort {

    private final PathsProperties properties;

    /**
     * Creates a paths config backed by the given bound properties.
     *
     * @param properties {@link PathsProperties} bound path properties
     */
    public PathsConfig(PathsProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates that the configured path properties point to existing directories.
     */
    @PostConstruct
    void validate() {
        requireExistingDirectory("sluice.paths.repo-root", properties.repoRoot());
        requireExistingDirectory("sluice.paths.library-root", properties.libraryRoot());
        requireExistingDirectory("sluice.paths.inbox", properties.inbox());
    }

    /**
     * Resolves the configured repo root path.
     *
     * @return {@link Path} the absolute repo root path
     */
    public Path repoRoot() {
        return resolve(Objects.requireNonNull(properties.repoRoot()));
    }

    /**
     * Resolves the configured library root path.
     *
     * @return {@link Path} the absolute library root path
     */
    @Override
    public Path library() {
        return resolve(Objects.requireNonNull(properties.libraryRoot()));
    }

    /**
     * Resolves the configured inbox path.
     *
     * @return {@link Path} the absolute inbox path
     */
    @Override
    public Path inbox() {
        return resolve(Objects.requireNonNull(properties.inbox()));
    }

    /**
     * Resolves the logs directory under the repo root.
     *
     * @return {@link Path} the logs directory path
     */
    @Override
    public Path logs() {
        return repoRoot().resolve("logs");
    }

    /**
     * Resolves the Sorted staging directory under the repo root.
     *
     * @return {@link Path} the Sorted directory path
     */
    @Override
    public Path sorted() {
        return repoRoot().resolve("Sorted");
    }

    /**
     * Resolves the Review directory under the repo root.
     *
     * @return {@link Path} the Review directory path
     */
    @Override
    public Path review() {
        return repoRoot().resolve("Review");
    }

    /**
     * Resolves the Duplicates directory under the repo root.
     *
     * @return {@link Path} the Duplicates directory path
     */
    @Override
    public Path duplicates() {
        return repoRoot().resolve("Duplicates");
    }

    /**
     * Resolves the Unreviewable directory under the repo root.
     *
     * @return {@link Path} the Unreviewable directory path
     */
    @Override
    public Path unreviewable() {
        return repoRoot().resolve("Unreviewable");
    }

    /**
     * Converts a raw path string to an absolute, normalized path.
     *
     * @param raw {@link String} the raw path string
     * @return {@link Path} the absolute normalized path
     */
    private static Path resolve(String raw) {
        return Path.of(raw).toAbsolutePath().normalize();
    }

    /**
     * Fails fast if the given property value is blank or doesn't resolve to an existing directory.
     *
     * @param property {@link String} the config property name, for the error message
     * @param raw {@link String} the raw configured path value
     */
    private static void requireExistingDirectory(String property, @Nullable String raw) {
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
