package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StartupSequenceTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    // The claim has to come first, not merely happen. Both housekeeping steps reach files: the
    // sweep deletes outright, and an armed watcher can auto-resume a waiting run into an apply. So
    // the lock is verified inside the same ordered chain as the two calls, rather than beside it.
    // Checked separately, an implementation that swept first and claimed afterwards would pass.
    @Test
    void runClaimsTheWorkingRootBeforeAnythingReachesAFile(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline);

        sequence.run();

        final var order = inOrder(lock, this.pipeline);
        order.verify(lock).acquire(root);
        order.verify(this.pipeline).armWatchesForResumableRuns();
        order.verify(this.pipeline).sweepExpiredDisasterDrawers();
    }

    // The sweep deletes and an armed watcher can auto-resume into an apply. A run that could not
    // claim the root must therefore stop before either, not merely report the refusal afterwards.
    @Test
    void aRefusedClaimStopsBeforeTheHousekeeping(@TempDir final Path root) {
        final var sequence = new StartupSequence(new RefusingLock(), paths(root), this.pipeline);

        assertThatThrownBy(sequence::run).isInstanceOf(WorkingRootBusyException.class);

        verifyNoInteractions(this.pipeline);
    }

    @Test
    void shutdownGivesTheWorkingRootBack(@TempDir final Path root) {
        final var lock = mock(WorkingRootLock.class);
        final var sequence = new StartupSequence(lock, paths(root), this.pipeline);

        sequence.shutdown();

        verify(lock).release();
    }

    private static PathsPort paths(final Path root) {
        return new FixedPaths(root);
    }

    private static final class RefusingLock implements WorkingRootLock {
        @Override
        public void acquire(final Path workingRoot) {
            throw new WorkingRootBusyException(workingRoot);
        }

        @Override
        public void release() {
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
    }
}
