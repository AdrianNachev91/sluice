package photos.sluice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import photos.sluice.adapter.ui.StartupSequence;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The whole app on a machine where nobody has chosen a folder yet. Every other context test sets
// the three roots, which is exactly why an install that cannot start was invisible for so long.
// Nothing here sets them.
@SpringBootTest
class UnconfiguredStartupTest {

    @Autowired
    private Pipeline pipeline;

    @Autowired
    private StartupSequence startup;

    @Autowired
    private PathValidationUseCase pathValidation;

    @Test
    void theContextStartsWithNoFoldersConfigured() {
        assertThat(this.pathValidation.violationsInForce())
                .containsExactly(new NotConfigured(PathRole.WORKING_ROOT), new NotConfigured(PathRole.LIBRARY_ROOT),
                        new NotConfigured(PathRole.INBOX));
    }

    // There is no folder to claim and nothing to house-keep inside one, so the sequence stops before
    // asking where the working root is. Asking would be the failure this whole chunk moves off
    // startup.
    @Test
    void theStartupSequenceDoesNothing() {
        assertThatCode(this.startup::run).doesNotThrowAnyException();
    }

    @Test
    void theFacadeRefusesWorkRatherThanFailingDeeperDown() {
        assertThatThrownBy(this.pipeline::cullRuns).isInstanceOf(PathsMisconfiguredException.class);
    }
}
