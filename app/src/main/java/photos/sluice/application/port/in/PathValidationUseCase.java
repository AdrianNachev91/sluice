package photos.sluice.application.port.in;

import photos.sluice.application.port.out.PathSettings;
import photos.sluice.domain.paths.PathViolation;

import java.util.List;

/**
 * Checks folder roots before anything works in them.
 *
 * <p>Living behind a use case is what lets a screen and a command line ask the same question. A
 * check written into either one would be walked past by the other.
 */
public interface PathValidationUseCase {

    /**
     * Checks roots that are not in force.
     *
     * @param paths {@link PathSettings} the candidate folder roots
     * @return a {@link List} of {@link PathViolation}, empty when the roots are usable
     */
    List<PathViolation> violations(PathSettings paths);

    /**
     * Checks the roots the app is running on right now.
     *
     * @return a {@link List} of {@link PathViolation}, empty when the roots are usable
     */
    List<PathViolation> violationsInForce();
}
