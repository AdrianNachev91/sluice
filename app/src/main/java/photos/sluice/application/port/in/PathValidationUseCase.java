package photos.sluice.application.port.in;

import photos.sluice.application.port.out.PathSettings;
import photos.sluice.domain.paths.PathViolation;

import java.util.List;

/**
 * Checks folder roots before anything works in them. One implementation serves three callers. A
 * picker, asking whether the folder a user just chose is usable. The facade, refusing work it
 * cannot safely do. And the startup sequence, deciding whether there is a folder to claim at all.
 *
 * <p>Living behind a use case is what lets a screen and a command line ask the same question. A
 * check written into either one would be walked past by the other.
 */
public interface PathValidationUseCase {

    /**
     * Checks roots that are not in force, as a picker does before offering to save them.
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
