package photos.sluice.config;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.PathsPort;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves Sluice's working paths from the settings currently in force. It resolves only. Whether
 * those roots exist, and whether they may sit where they do, is checked through
 * {@code PathValidationUseCase}. That is what lets a screen and a command line ask the same
 * question of the same code.
 *
 * <p>An accessor on an install with nothing configured yet fails, since there is no folder to name.
 * Callers run that check first rather than meeting it here.
 *
 * <p>The logs, Sorted, Review, Duplicates, and Unreviewable directories are all derived from the
 * working root rather than configured independently.
 *
 * <p>Every accessor reads the settings again rather than holding a resolved path. That is what
 * makes a saved folder root reach the engines with nothing restarted.
 */
@Component
public class PathsConfig implements PathsPort {

    private final LiveSettings settings;

    /**
     * Creates a paths config over the settings in force.
     *
     * @param settings {@link LiveSettings} the settings the app is running on
     */
    public PathsConfig(final LiveSettings settings) {
        this.settings = settings;
    }

    /**
     * Resolves the configured working root path.
     *
     * @return {@link Path} the absolute working root path
     */
    @Override
    public Path workingRoot() {
        return resolve(Objects.requireNonNull(this.paths().workingRoot()));
    }

    /**
     * Resolves the configured library root path.
     *
     * @return {@link Path} the absolute library root path
     */
    @Override
    public Path library() {
        return resolve(Objects.requireNonNull(this.paths().libraryRoot()));
    }

    /**
     * Resolves the configured inbox path.
     *
     * @return {@link Path} the absolute inbox path
     */
    @Override
    public Path inbox() {
        return resolve(Objects.requireNonNull(this.paths().inbox()));
    }

    /**
     * Resolves the logs directory under the working root.
     *
     * @return {@link Path} the logs directory path
     */
    @Override
    public Path logs() {
        return this.workingRoot().resolve("logs");
    }

    /**
     * Resolves the Sorted staging directory under the working root.
     *
     * @return {@link Path} the Sorted directory path
     */
    @Override
    public Path sorted() {
        return this.workingRoot().resolve("Sorted");
    }

    /**
     * Resolves the Review directory under the working root.
     *
     * @return {@link Path} the Review directory path
     */
    @Override
    public Path review() {
        return this.workingRoot().resolve("Review");
    }

    /**
     * Resolves the Duplicates directory under the working root.
     *
     * @return {@link Path} the Duplicates directory path
     */
    @Override
    public Path duplicates() {
        return this.workingRoot().resolve("Duplicates");
    }

    /**
     * Resolves the Unreviewable directory under the working root.
     *
     * @return {@link Path} the Unreviewable directory path
     */
    @Override
    public Path unreviewable() {
        return this.workingRoot().resolve("Unreviewable");
    }

    /**
     * Resolves the sift-prep directory under the logs directory.
     *
     * @return {@link Path} the sift-prep directory path
     */
    @Override
    public Path siftPrep() {
        return this.logs().resolve("sift-prep");
    }

    /**
     * Resolves the graveyard directory under the logs directory.
     *
     * @return {@link Path} the graveyard directory path
     */
    @Override
    public Path graveyard() {
        return this.logs().resolve("archives");
    }

    /**
     * The folder roots the settings currently in force name.
     *
     * @return {@link PathSettings} the configured folder roots
     */
    private PathSettings paths() {
        return this.settings.current().paths();
    }

    /**
     * Converts a raw path string to an absolute, normalized path.
     *
     * @param raw {@link String} the raw path string
     * @return {@link Path} the absolute normalized path
     */
    private static Path resolve(final String raw) {
        return Path.of(raw).toAbsolutePath().normalize();
    }

}
