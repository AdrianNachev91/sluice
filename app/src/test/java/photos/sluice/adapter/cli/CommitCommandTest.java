package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.paths.PathViolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CommitCommandTest {

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void aBareCommitIsRefusedRatherThanMovingEverything(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "commit");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void allSpelledOutMovesEveryYear(@TempDir final Path root) {
        this.answering(nothingCommitted());

        this.run(root, "commit", "all");

        verify(this.pipeline).commit(new CommitScope.All());
    }

    @Test
    void aYearAndASpanOfMonthsNarrowToThoseMonths(@TempDir final Path root) {
        this.answering(nothingCommitted());

        this.run(root, "commit", "2019", "--months", "6-8");

        verify(this.pipeline).commit(new CommitScope.Year(2019, new MonthRange(6, 8)));
    }

    @Test
    void aGappedMonthListIsRefusedRatherThanWidened(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "commit", "2019", "--months", "6,8,11");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aMoveReportsCountsByBucket(@TempDir final Path root) {
        this.answering(new CommitSummary(10, 0, Map.of(LibraryBucket.PHOTOS, 7, LibraryBucket.VIDEOS, 3), false));

        final CliHarness.Result result = this.run(root, "commit", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Moved to your library: 10", "Photos: 7", "Videos: 3");
    }

    @Test
    void nothingToMoveIsReportedRatherThanAZeroCount(@TempDir final Path root) {
        this.answering(nothingCommitted());

        assertThat(this.run(root, "commit", "2019").out().lines()).containsExactly("Nothing to move for this scope.");
    }

    @Test
    void aStoppedMoveNamesWhatIsStillInSorted(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.commit(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new CommitSummary(2, 4, Map.of(LibraryBucket.PHOTOS, 2), true);
        }));

        final CliHarness.Result result = this.run(root, "commit", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out().lines()).containsExactly(
                "Stopped. 4 photos and videos are still in Sorted.", "Moved to your library: 2", "Photos: 2");
    }

    @Test
    void aCallerAskingForADocumentGetsTheBucketsAsFields(@TempDir final Path root) {
        this.answering(new CommitSummary(5, 0, Map.of(LibraryBucket.FUNNY, 5), false));

        assertThat(this.run(root, "commit", "2019", "--json").out())
                .contains("\"command\":\"commit\"")
                .contains("\"committed\":5")
                .contains("\"FUNNY\":5");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheMoveStarts(@TempDir final Path root) {
        this.answering(nothingCommitted());

        this.run(root, "commit", "all");

        verify(this.lock).acquire(root);
    }

    private void answering(final CommitSummary summary) {
        when(this.pipeline.commit(any())).thenAnswer(_ -> this.runner.submit(_ -> summary));
    }

    private static CommitSummary nothingCommitted() {
        return new CommitSummary(0, 0, Map.of(), false);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final var progress = new ConsoleProgressPort(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), false);
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, progress), progress);
        return CliHarness.run(CliHarness.parser(new CommitCommand(this.pipeline, jobs)), args);
    }

    private record UsableRoots() implements PathValidationUseCase {
        @Override
        public List<PathViolation> violations(final PathSettings paths) {
            return List.of();
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return List.of();
        }
    }
}
