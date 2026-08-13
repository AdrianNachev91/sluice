package photos.sluice.application.service;

import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.domain.paths.PathViolation;

import java.util.List;

/**
 * Refuses work whose folder roots are not usable: unset, missing, or sitting inside each other.
 *
 * <p>A class of its own rather than a method on {@link Pipeline}. Two callers need this refusal and
 * only one of them is a facade entry point. {@link Pipeline} runs it in front of every call that
 * resolves a path. {@link CullEngine#resume} runs it too, since a watcher's auto-resume reaches
 * that method without passing the facade at all.
 *
 * <p>Not a Spring bean. {@link Pipeline} builds the one instance it needs and hands it on, the same
 * way it builds {@link CullEngine} itself.
 */
final class RootsGuard {

    private final PathValidationUseCase pathValidation;

    /**
     * Creates the guard.
     *
     * @param pathValidation {@link PathValidationUseCase} checks the roots the app is running on
     */
    RootsGuard(final PathValidationUseCase pathValidation) {
        this.pathValidation = pathValidation;
    }

    /**
     * Checks the roots in force and refuses if any is unusable.
     *
     * @throws PathsMisconfiguredException if any of the three roots is unset, missing, or overlapping
     */
    void requireUsable() {
        final List<PathViolation> violations = this.pathValidation.violationsInForce();
        if (!violations.isEmpty()) {
            throw new PathsMisconfiguredException(violations);
        }
    }
}
