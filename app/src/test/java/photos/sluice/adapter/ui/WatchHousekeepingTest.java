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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WatchHousekeepingTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    private final WatchHousekeeping housekeeping = new WatchHousekeeping(this.pipeline);

    @Test
    void turningWatchingOffRetiresEveryWatcherAndArmsNothing() {
        this.housekeeping.watchingChanged(false);

        verify(this.pipeline).stopAllWatching();
        verify(this.pipeline, never()).armWatchesForResumableRuns();
    }

    @Test
    void turningWatchingOnArmsTheResumableRunsAndRetiresNothing() {
        this.housekeeping.watchingChanged(true);

        verify(this.pipeline).armWatchesForResumableRuns();
        verify(this.pipeline, never()).stopAllWatching();
    }

    @Test
    void aRefusedRetireIsSwallowedRatherThanFailingTheSaveThatCausedIt() {
        doThrow(new PathsMisconfiguredException(
                List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("gone")))))
                .when(this.pipeline).stopAllWatching();

        assertThatCode(() -> this.housekeeping.watchingChanged(false)).doesNotThrowAnyException();
    }

    @Test
    void aRefusedArmIsSwallowedTheSameWay() {
        doThrow(new PathsMisconfiguredException(
                List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("gone")))))
                .when(this.pipeline).armWatchesForResumableRuns();

        assertThatCode(() -> this.housekeeping.watchingChanged(true)).doesNotThrowAnyException();
    }

    @Test
    void anErrorIsSwallowedTooRatherThanFailingASaveThatAlreadyLanded() {
        doThrow(new StackOverflowError()).when(this.pipeline).stopAllWatching();

        assertThatCode(() -> this.housekeeping.watchingChanged(false)).doesNotThrowAnyException();
    }
}
