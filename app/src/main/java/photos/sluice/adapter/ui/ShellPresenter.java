package photos.sluice.adapter.ui;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

/**
 * Decides which resting state the Dashboard pane opens on. A view handed only this boolean has
 * nothing left to branch on.
 */
@Component
@Profile("!cli")
public class ShellPresenter {

    private final PathValidationUseCase pathValidation;

    /**
     * Creates the presenter over the use case that checks the roots in force.
     *
     * @param pathValidation {@link PathValidationUseCase} answers what is wrong with the roots
     *     the app is running on, if anything
     */
    public ShellPresenter(final PathValidationUseCase pathValidation) {
        this.pathValidation = pathValidation;
    }

    /**
     * Whether every folder root is unset.
     *
     * @return boolean true when nothing at all is configured
     */
    public boolean unconfigured() {
        final var violations = this.pathValidation.violationsInForce();
        return violations.size() == PathRole.values().length
                && violations.stream().allMatch(NotConfigured.class::isInstance);
    }
}
