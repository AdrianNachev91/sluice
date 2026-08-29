package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MutatingCommandStartTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    // Checked separately, an implementation that swept first and claimed afterwards would pass.
    @Test
    void theWorkingRootIsClaimedBeforeTheSweepDeletesAnythingInIt(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);

        this.start(root, lock, usableRoots()).claimAndSweep();

        final var order = inOrder(lock, this.pipeline);
        order.verify(lock).acquire(root);
        order.verify(this.pipeline).sweepExpiredDisasterDrawers();
    }

    @Test
    void anUnsetLibraryRootStopsBothTheClaimAndTheSweep(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);

        this.start(root, lock, violating(new NotConfigured(PathRole.LIBRARY_ROOT))).claimAndSweep();

        verifyNoInteractions(lock);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void rootsInsideEachOtherStopBothTheClaimAndTheSweep(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);

        this.start(root, lock, violating(new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX))).claimAndSweep();

        verifyNoInteractions(lock);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aRefusedClaimStopsBeforeTheSweep(@TempDir final Path root) {
        final var start = this.start(root, new RefusingLock(), usableRoots());

        assertThatThrownBy(start::claimAndSweep).isInstanceOf(WorkingRootBusyException.class);

        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aCommandRunThroughTheParserClaimsTheRoot(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var command = new StandInCommand(this.start(root, lock, usableRoots()));

        final CliHarness.Result result = CliHarness.run(new CommandLine(command));

        assertThat(result.exitCode()).isEqualTo(CommandLine.ExitCode.OK);
        verify(lock).acquire(root);
    }

    // The stand-in does not report through CommandReports, as a real verb does, so what reaches the
    // error stream here is the parser's own unhandled-exception report. Asserting that text would
    // pin a shape nobody chose.
    @Test
    void aBusyWorkingRootStopsTheCommandThoughNothingHasWordedTheRefusal(@TempDir final Path root) {
        final var command = new StandInCommand(this.start(root, new RefusingLock(), usableRoots()));

        final CliHarness.Result result = CliHarness.run(new CommandLine(command));

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.out()).isEmpty();
    }

    private MutatingCommandStart start(final Path root, final WorkingRootLock lock,
                                       final PathValidationUseCase validation) {
        return new MutatingCommandStart(lock, paths(root), this.pipeline, validation);
    }

    private static PathsPort paths(final Path root) {
        return SettingsFixture.workingRoot(root);
    }

    private static PathValidationUseCase usableRoots() {
        return new FixedViolations(List.of());
    }

    private static PathValidationUseCase violating(final PathViolation violation) {
        return new FixedViolations(List.of(violation));
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

    private static final class RefusingLock implements WorkingRootLock {
        @Override
        public void acquire(final Path workingRoot) {
            throw new WorkingRootBusyException(workingRoot);
        }

        @Override
        public void release(final Path workingRoot) {
        }

        @Override
        public void releaseAll() {
        }
    }

    // Calls the sequence and nothing else, so what these drive is the claim rather than any verb's
    // own work.
    @Command(name = "stand-in")
    private record StandInCommand(MutatingCommandStart start) implements Callable<Integer> {
        @Override
        public Integer call() {
            this.start.claimAndSweep();
            return CommandLine.ExitCode.OK;
        }
    }
}
