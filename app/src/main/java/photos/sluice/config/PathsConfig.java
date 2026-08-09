package photos.sluice.config;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves Sluice's working paths from the bound {@link PathsProperties}. At startup it validates
 * that the repo root, library root, and inbox all point to directories that actually exist. It
 * also checks that the three roots don't overlap. An overlap could let a file be deleted as a
 * redundant duplicate while being the only copy that exists.
 *
 * <p>The logs, Sorted, Review, Duplicates, and Unreviewable directories are all derived from the
 * repo root rather than configured independently.
 */
@Component
public class PathsConfig implements PathsPort {

    private final PathsProperties properties;

    /**
     * Creates a paths config backed by the given bound properties.
     *
     * @param properties {@link PathsProperties} bound path properties
     */
    public PathsConfig(final PathsProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates that the configured path properties point to existing directories, and that the
     * three roots don't overlap.
     */
    @PostConstruct
    void validate() {
        requireExistingDirectory("sluice.paths.repo-root", this.properties.repoRoot());
        requireExistingDirectory("sluice.paths.library-root", this.properties.libraryRoot());
        requireExistingDirectory("sluice.paths.inbox", this.properties.inbox());

        final List<String> violations = rootOverlapViolations(this.repoRoot(), this.library(), this.inbox());
        if (!violations.isEmpty()) {
            throw new IllegalStateException(String.join(" ", violations));
        }
    }

    /**
     * Checks three candidate roots for the overlaps that would let a file be deleted as a
     * redundant duplicate while being the only copy that exists. Every committed file proves its
     * own liveness by its presence in the hash index. If the library sits inside the inbox, or the
     * other way round, a fresh sort run sees the library copy. It then marks the inbox original
     * redundant and deletes the only copy.
     *
     * <p>The rules are asymmetric because the documented layout puts the inbox inside the repo
     * root, and the Sorted, Review, Duplicates, and Unreviewable directories are themselves derived
     * from the repo root. A blanket "all three roots must be disjoint" check would reject that
     * canonical setup:
     *
     * <ul>
     *   <li>The library and the inbox must not contain each other, in either direction.
     *   <li>The inbox must not be the repo root, or an ancestor of it. The repo root may still be
     *       an ancestor of the inbox, since that's the documented layout.
     * </ul>
     *
     * <p>Roots are compared via {@link Path#toRealPath}, so two configured paths naming the same
     * real directory under different names, such as a symlink or a Windows junction, are caught
     * too. Every argument must already resolve to an existing directory.
     *
     * <p>The check is callable on its own, separate from {@link #validate()}. That lets more than
     * one caller reuse it against that same precondition. Future callers include this class's own
     * startup fail-fast, an interactive directory picker, and a non-interactive entry point that
     * takes paths directly.
     *
     * @param repoRoot {@link Path} the candidate repo root
     * @param libraryRoot {@link Path} the candidate library root
     * @param inbox {@link Path} the candidate inbox root
     * @return {@link List} of violation messages, empty when the layout is legal
     */
    public static List<String> rootOverlapViolations(final Path repoRoot, final Path libraryRoot,
            final Path inbox) {
        final Path realRepoRoot = toRealPath(repoRoot);
        final Path realLibrary = toRealPath(libraryRoot);
        final Path realInbox = toRealPath(inbox);

        final List<String> violations = new ArrayList<>();
        if (contains(realLibrary, realInbox) || contains(realInbox, realLibrary)) {
            violations.add("sluice.paths.library-root (" + libraryRoot + ") and sluice.paths.inbox ("
                    + inbox + ") must not contain each other.");
        }
        if (contains(realInbox, realRepoRoot)) {
            violations.add("sluice.paths.inbox (" + inbox
                    + ") must not be, or contain, sluice.paths.repo-root (" + repoRoot + ").");
        }
        return violations;
    }

    /**
     * Whether the descendant path starts with the ancestor path, once both are real paths. True
     * when the two paths are equal.
     *
     * @param ancestor {@link Path} the candidate ancestor, already resolved via {@code toRealPath}
     * @param descendant {@link Path} the candidate descendant, already resolved via
     *     {@code toRealPath}
     * @return {@code boolean} true if descendant is ancestor, or lies under it
     */
    private static boolean contains(final Path ancestor, final Path descendant) {
        return descendant.startsWith(ancestor);
    }

    /**
     * Resolves a path's real, symlink- and junction-free form.
     *
     * @param path {@link Path} the path to resolve; must already exist
     * @return {@link Path} the resolved real path
     */
    private static Path toRealPath(final Path path) {
        try {
            return path.toRealPath();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to resolve the real path of " + path, e);
        }
    }

    /**
     * Resolves the configured repo root path.
     *
     * @return {@link Path} the absolute repo root path
     */
    @Override
    public Path repoRoot() {
        return resolve(Objects.requireNonNull(this.properties.repoRoot()));
    }

    /**
     * Resolves the configured library root path.
     *
     * @return {@link Path} the absolute library root path
     */
    @Override
    public Path library() {
        return resolve(Objects.requireNonNull(this.properties.libraryRoot()));
    }

    /**
     * Resolves the configured inbox path.
     *
     * @return {@link Path} the absolute inbox path
     */
    @Override
    public Path inbox() {
        return resolve(Objects.requireNonNull(this.properties.inbox()));
    }

    /**
     * Resolves the logs directory under the repo root.
     *
     * @return {@link Path} the logs directory path
     */
    @Override
    public Path logs() {
        return this.repoRoot().resolve("logs");
    }

    /**
     * Resolves the Sorted staging directory under the repo root.
     *
     * @return {@link Path} the Sorted directory path
     */
    @Override
    public Path sorted() {
        return this.repoRoot().resolve("Sorted");
    }

    /**
     * Resolves the Review directory under the repo root.
     *
     * @return {@link Path} the Review directory path
     */
    @Override
    public Path review() {
        return this.repoRoot().resolve("Review");
    }

    /**
     * Resolves the Duplicates directory under the repo root.
     *
     * @return {@link Path} the Duplicates directory path
     */
    @Override
    public Path duplicates() {
        return this.repoRoot().resolve("Duplicates");
    }

    /**
     * Resolves the Unreviewable directory under the repo root.
     *
     * @return {@link Path} the Unreviewable directory path
     */
    @Override
    public Path unreviewable() {
        return this.repoRoot().resolve("Unreviewable");
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

    /**
     * Fails fast if the given property value is blank or doesn't resolve to an existing directory.
     *
     * @param property {@link String} the config property name, for the error message
     * @param raw {@link String} the raw configured path value
     */
    private static void requireExistingDirectory(final String property, final @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    property + " is not configured. Set it in Settings or via the " + property + " property.");
        }
        final Path path = resolve(raw);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(
                    property + " (" + path + ") does not exist. Set it in Settings or via the "
                            + property + " property.");
        }
    }
}
