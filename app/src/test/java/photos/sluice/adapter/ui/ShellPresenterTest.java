package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellPresenterTest {

    @Test
    void everyRootUnsetIsUnconfigured() {
        final var presenter = new ShellPresenter(violating(
                new NotConfigured(PathRole.REPO_ROOT),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.unconfigured()).isTrue();
    }

    @Test
    void usableRootsAreNotUnconfigured() {
        final var presenter = new ShellPresenter(violating());

        assertThat(presenter.unconfigured()).isFalse();
    }

    @Test
    void oneRootLeftUnsetAmongOthersConfiguredIsNotTheWelcomeCardTrigger() {
        final var presenter = new ShellPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.unconfigured()).isFalse();
    }

    @Test
    void aBadValueRatherThanAnUnsetRootIsNotUnconfigured() {
        final var presenter = new ShellPresenter(violating(
                new NotAPath(PathRole.REPO_ROOT, "not a path"),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.unconfigured()).isFalse();
    }

    @Test
    void anOverlapAloneIsNotUnconfigured() {
        final var presenter = new ShellPresenter(violating(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX)));

        assertThat(presenter.unconfigured()).isFalse();
    }

    private static PathValidationUseCase violating(final PathViolation... violations) {
        return new FixedViolations(List.of(violations));
    }

    private record FixedViolations(List<PathViolation> violations) implements PathValidationUseCase {
        @Override
        public List<PathViolation> violations(final PathSettings paths) {
            return this.violations;
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return this.violations;
        }
    }
}
