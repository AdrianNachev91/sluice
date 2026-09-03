package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Unreadable;
import photos.sluice.domain.paths.RootLayout;

import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the three folder roots, by resolving each one and then handing the results to the domain
 * rule that compares them.
 *
 * <p>The two halves are split because only one of them touches a disk. Whether a folder is there,
 * and what it really is behind any symlink, is a question for the filesystem. Whether three real
 * folders may sit where they do is arithmetic, and lives in the domain.
 *
 * <p>The overlap rule only runs once all three roots resolved. Two roots cannot be compared while
 * one of them is a folder nobody has chosen yet. A user told to fix the missing one first has
 * enough to act on.
 */
@Component
public class PathValidationService implements PathValidationUseCase {

    private static final Logger log = LoggerFactory.getLogger(PathValidationService.class);

    private final MediaReader media;
    private final LiveSettings settings;

    /**
     * Creates the service.
     *
     * @param media {@link MediaReader} resolves a configured root to the real directory behind it
     * @param settings {@link LiveSettings} holds the settings in force
     */
    public PathValidationService(final MediaReader media, final LiveSettings settings) {
        this.media = media;
        this.settings = settings;
    }

    /**
     * Checks candidate roots.
     *
     * @param paths {@link PathSettings} the candidate folder roots
     * @return a {@link List} of {@link PathViolation}, empty when the roots are usable
     */
    @Override
    public List<PathViolation> violations(final PathSettings paths) {
        final List<PathViolation> violations = new ArrayList<>();
        final Path repoRoot = this.resolve(PathRole.WORKING_ROOT, paths.repoRoot(), violations);
        final Path libraryRoot = this.resolve(PathRole.LIBRARY_ROOT, paths.libraryRoot(), violations);
        final Path inbox = this.resolve(PathRole.INBOX, paths.inbox(), violations);
        if (repoRoot != null && libraryRoot != null && inbox != null) {
            violations.addAll(RootLayout.violations(repoRoot, libraryRoot, inbox));
        }
        return List.copyOf(violations);
    }

    /**
     * Checks the roots in force.
     *
     * @return a {@link List} of {@link PathViolation}, empty when the roots are usable
     */
    @Override
    public List<PathViolation> violationsInForce() {
        return this.violations(this.settings.current().paths());
    }

    /**
     * Resolves one configured value to the real directory behind it, recording why it could not be
     * where it could not.
     *
     * <p>A read failure is recorded as a violation rather than thrown. Everything that calls here
     * wants a verdict on three roots, and a root nobody can read is one this app must not work in
     * either way. Letting it out untyped would hand a screen an exception naming no root, in place
     * of a list that marks the field. The failure itself is logged, since nothing renders it.
     *
     * @param role {@link PathRole} which root the value belongs to
     * @param raw {@link String} the configured value, or null when nothing is set
     * @param violations a {@link List} of {@link PathViolation} collected so far, added to here
     * @return {@link Path} the real directory, or null if there is none
     */
    private @Nullable Path resolve(final PathRole role, final @Nullable String raw,
                                   final List<PathViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new NotConfigured(role));
            return null;
        }
        final Path path;
        try {
            path = Path.of(raw).toAbsolutePath().normalize();
        } catch (final InvalidPathException e) {
            violations.add(new NotAPath(role, raw));
            return null;
        }
        final Path real;
        try {
            // Asked before resolving, because resolving cannot separate a refusal from an absence
            // and this has to mark the field with the right one of the two.
            if (!this.media.directoryIsThere(path)) {
                violations.add(new NotADirectory(role, path));
                return null;
            }
            real = this.media.realDirectory(path).orElse(null);
        } catch (final UncheckedIOException e) {
            log.warn("Could not resolve the folder root {}", path, e);
            violations.add(new Unreadable(role, path));
            return null;
        }
        if (real == null) {
            violations.add(new NotADirectory(role, path));
        }
        return real;
    }
}
