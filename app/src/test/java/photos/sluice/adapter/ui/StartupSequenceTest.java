package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StartupSequenceTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    // The lock is verified inside the same ordered chain as the two calls, rather than beside it.
    // Checked separately, an implementation that swept first and claimed afterwards would pass.
    @Test
    void runClaimsTheWorkingRootBeforeAnythingReachesAFile(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline, usableRoots());

        sequence.run();

        final var order = inOrder(lock, this.pipeline);
        order.verify(lock).acquire(root);
        order.verify(this.pipeline).armWatchesForResumableRuns();
        order.verify(this.pipeline).sweepExpiredDisasterDrawers();
    }

    @Test
    void unusableRootsStopTheSequenceBeforeTheClaim(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline,
                violating(new NotConfigured(PathRole.WORKING_ROOT)));

        sequence.run();

        verifyNoInteractions(lock);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void anotherRootLeftUnsetStopsTheHousekeepingButNotTheClaim(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline,
                violating(new NotConfigured(PathRole.LIBRARY_ROOT)));

        sequence.run();

        verify(lock).acquire(root);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void overlappingRootsStopTheHousekeepingButNotTheClaim(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline,
                violating(new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX)));

        sequence.run();

        verify(lock).acquire(root);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aRefusedClaimStopsBeforeTheHousekeeping(@TempDir final Path root) {
        final var sequence = new StartupSequence(new RefusingLock(), paths(root), this.pipeline, usableRoots());

        assertThatThrownBy(sequence::run).isInstanceOf(WorkingRootBusyException.class);

        verifyNoInteractions(this.pipeline);
    }

    // Checked separately, an implementation that handed the root back first would pass.
    @Test
    void shutdownRetiresWatchersAndDrainsBeforeGivingTheWorkingRootBack(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        when(this.pipeline.stopAcceptingJobs(any())).thenReturn(true);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline, usableRoots());

        sequence.shutdown();

        final var order = inOrder(this.pipeline, lock);
        order.verify(this.pipeline).stopAllWatching();
        order.verify(this.pipeline).stopAcceptingJobs(any());
        order.verify(lock).releaseAll();
    }

    @Test
    void shutdownKeepsTheWorkingRootWhenAJobOutlastsTheDrain(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        when(this.pipeline.stopAcceptingJobs(any())).thenReturn(false);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline, usableRoots());

        sequence.shutdown();

        verify(this.pipeline).stopAllWatching();
        verify(lock, never()).releaseAll();
    }

    @Test
    void anAttendedWindDownWaitsFarLongerThanAnUnattendedOne(@TempDir final Path root) {
        when(this.pipeline.stopAcceptingJobs(any())).thenReturn(true);

        new StartupSequence(mock(WorkingRootLock.class), paths(root), this.pipeline, usableRoots())
                .windDownWithin(StartupSequence.ATTENDED_DRAIN_WAIT);

        verify(this.pipeline).stopAcceptingJobs(StartupSequence.ATTENDED_DRAIN_WAIT);
        assertThat(StartupSequence.ATTENDED_DRAIN_WAIT).isGreaterThan(Duration.ofSeconds(60));
    }

    @Test
    void aWindDownGivenNoTimeAtAllKeepsTheWorkingRoot(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        when(this.pipeline.stopAcceptingJobs(Duration.ZERO)).thenReturn(false);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline, usableRoots());

        assertThat(sequence.windDownWithin(Duration.ZERO)).isFalse();

        verify(this.pipeline).stopAllWatching();
        verify(lock, never()).releaseAll();
    }

    @Test
    void theToolkitsOwnShutdownAfterAQuitFlowDrainsNothingASecondTime(@TempDir final Path root) {
        when(this.pipeline.stopAcceptingJobs(any())).thenReturn(false);
        final var sequence = new StartupSequence(mock(WorkingRootLock.class), paths(root),
                this.pipeline, usableRoots());
        sequence.windDownWithin(Duration.ZERO);

        sequence.shutdown();

        verify(this.pipeline).stopAllWatching();
        verify(this.pipeline).stopAcceptingJobs(any());
    }

    private static PathsPort paths(final Path root) {
        return new FixedPaths(root);
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

    // Only workingRoot() is ever asked for here.
    private record FixedPaths(Path root) implements PathsPort {
        @Override
        public Path workingRoot() {
            return this.root;
        }

        @Override
        public Path inbox() {
            return this.root.resolve("Inbox");
        }

        @Override
        public Path sorted() {
            return this.root.resolve("Sorted");
        }

        @Override
        public Path review() {
            return this.root.resolve("Review");
        }

        @Override
        public Path duplicates() {
            return this.root.resolve("Duplicates");
        }

        @Override
        public Path unreviewable() {
            return this.root.resolve("Unreviewable");
        }

        @Override
        public Path library() {
            return this.root.resolve("Library");
        }

        @Override
        public Path logs() {
            return this.root.resolve("logs");
        }

        @Override
        public Path cullPrep() {
            return this.logs().resolve("sift-prep");
        }

        @Override
        public Path graveyard() {
            return this.logs().resolve("archives");
        }
    }
}
