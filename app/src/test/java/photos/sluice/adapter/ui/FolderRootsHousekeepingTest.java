package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FolderRootsHousekeepingTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    private final FolderRootsHousekeeping housekeeping = new FolderRootsHousekeeping(this.pipeline);

    // An ordered chain rather than two separate verifications: checked apart, an implementation
    // that retired last would pass.
    @Test
    void watchersAreRetiredBeforeAnythingIsArmedAgainstTheNewRoots() {
        this.housekeeping.folderRootsChanged(true);

        final var order = inOrder(this.pipeline);
        order.verify(this.pipeline).stopAllWatching();
        order.verify(this.pipeline).armWatchesForResumableRuns();
    }

    @Test
    void theRetentionSweepIsLeftToTheNextLaunch() {
        this.housekeeping.folderRootsChanged(true);

        verify(this.pipeline, never()).sweepExpiredDisasterDrawers();
    }

    @Test
    void aMoveThatLeavesTheWorkingRootAloneRetiresNothingAndStillArms() {
        this.housekeeping.folderRootsChanged(false);

        verify(this.pipeline, never()).stopAllWatching();
        verify(this.pipeline).armWatchesForResumableRuns();
    }

    @Test
    void aRefusedArmingIsSwallowedRatherThanFailingTheSaveThatCausedIt() {
        doThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("gone")))))
                .when(this.pipeline).armWatchesForResumableRuns();

        assertThatCode(() -> this.housekeeping.folderRootsChanged(true)).doesNotThrowAnyException();

        verify(this.pipeline).stopAllWatching();
    }

    @Test
    void aRefusedRetireIsSwallowedAsWell() {
        doThrow(new IllegalStateException("watchers gone")).when(this.pipeline).stopAllWatching();

        assertThatCode(() -> this.housekeeping.folderRootsChanged(true)).doesNotThrowAnyException();
    }

    @Test
    void anErrorIsSwallowedTooRatherThanFailingASaveThatAlreadyLanded() {
        doThrow(new StackOverflowError()).when(this.pipeline).armWatchesForResumableRuns();

        assertThatCode(() -> this.housekeeping.folderRootsChanged(true)).doesNotThrowAnyException();
    }
}
