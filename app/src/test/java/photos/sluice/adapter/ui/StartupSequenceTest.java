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
import java.util.List;

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

    // The claim has to come first, not merely happen. Both housekeeping steps reach files: the
    // sweep deletes outright, and an armed watcher can auto-resume a waiting run into an apply. So
    // the lock is verified inside the same ordered chain as the two calls, rather than beside it.
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

    // An install with no folders chosen has nothing to claim, and the claim is not this class's to
    // take on its behalf. The save that first names a working root takes it.
    @Test
    void unusableRootsStopTheSequenceBeforeTheClaim(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline,
                violating(new NotConfigured(PathRole.REPO_ROOT)));

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
                violating(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX)));

        sequence.run();

        verify(lock).acquire(root);
        verifyNoInteractions(this.pipeline);
    }

    // The sweep deletes and an armed watcher can auto-resume into an apply. A run that could not
    // claim the root must therefore stop before either, not merely report the refusal afterwards.
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

    // Only repoRoot() is ever asked for here. The rest of the port exists for engines this sequence
    // never reaches.
    private record FixedPaths(Path root) implements PathsPort {
        @Override
        public Path repoRoot() {
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
            return this.logs().resolve("cull-prep");
        }

        @Override
        public Path graveyard() {
            return this.logs().resolve("disasters");
        }
    }
}
