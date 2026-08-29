package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.paths.PathViolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Two tests here wait on a job that runs until it is cancelled. Unbounded, a cancel that stops
// working leaves the suite hanging instead of naming what broke. On its own thread, because the
// default reports a timeout only once the test method returns, and a blocked one never does.
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SortCommandTest {

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private final ByteArrayOutputStream reported = new ByteArrayOutputStream();
    private final ConsoleProgressPort progress =
            new ConsoleProgressPort(new PrintStream(this.reported, true, StandardCharsets.UTF_8), false);

    private InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void aSortNamingNoYearTakesTheOldestOne(@TempDir final Path root) {
        this.answering(nothingSorted());

        this.run(root, "sort");

        verify(this.pipeline).sort(new SortScope.OldestYear());
    }

    @Test
    void aYearAndASpanOfMonthsNarrowToThoseMonths(@TempDir final Path root) {
        this.answering(nothingSorted());

        this.run(root, "sort", "2019", "--months", "6-8");

        verify(this.pipeline).sort(new SortScope.Year(2019, new MonthRange(6, 8)));
    }

    @Test
    void aCountTakesTheOldestPhotosInsteadOfAYear(@TempDir final Path root) {
        this.answering(nothingSorted());

        this.run(root, "sort", "--oldest", "30");

        verify(this.pipeline).sort(new SortScope.OldestN(30));
    }

    @Test
    void aSortSaysWhatItFiledAndWhereTheRestWent(@TempDir final Path root) {
        this.answering(new SortSummary(10, 2, 1, 5, 1, 1, 0, 3, List.of(), List.of(), Set.of(2019), List.of(), false, 0));

        final CliHarness.Result result = this.run(root, "sort", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Photos sorted: 5", "Videos sorted: 1",
                "Already in your library: 2", "Identical copies removed: 1", "Moved to Review: 1");
    }

    @Test
    void aSortThatFiledNothingStillNamesPhotosAndVideos(@TempDir final Path root) {
        this.answering(new SortSummary(2, 2, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Set.of(), List.of(), false, 0));

        assertThat(this.run(root, "sort", "2019").out().lines())
                .containsExactly("Photos sorted: 0", "Videos sorted: 0", "Already in your library: 2");
    }

    @Test
    void anInboxWithNothingReadyToSortSaysSoRatherThanCountingZeroes(@TempDir final Path root) {
        this.answering(nothingSorted());

        assertThat(this.run(root, "sort").out().lines())
                .containsExactly("Nothing in your Inbox was ready to sort.");
    }

    @Test
    void countsFromAStoppedSortAreLedByTheFactThatItStopped(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new SortSummary(3, 0, 0, 3, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019), List.of(), true, 1466);
        }));

        assertThat(this.run(root, "sort").out().lines())
                .containsExactly("Stopped. 1,466 photos and videos are still in your Inbox.",
                        "Photos sorted: 3", "Videos sorted: 0");
    }

    @Test
    void aSingleFileLeftBehindIsNamedInTheSingular(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new SortSummary(2, 0, 0, 0, 2, 0, 0, 0, List.of(), List.of(), Set.of(2019), List.of(), true, 1);
        }));

        assertThat(this.run(root, "sort").out().lines())
                .containsExactly("Stopped. One photo or video is still in your Inbox.",
                        "Photos sorted: 0", "Videos sorted: 2");
    }

    @Test
    void aSortStoppedWithNothingLeftBehindNamesNoRemainder(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new SortSummary(3, 0, 0, 3, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019), List.of(), true, 0);
        }));

        assertThat(this.run(root, "sort").out().lines())
                .containsExactly("Stopped. Everything this run picked up was sorted.",
                        "Photos sorted: 3", "Videos sorted: 0");
    }

    @Test
    void aCancelLandingAfterTheRunFinishedIsNotReportedAsAStop(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        this.answering(new SortSummary(3, 0, 0, 3, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019),
                List.of(), false, 0));

        final CliHarness.Result result = this.run(root, "sort");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Photos sorted: 3", "Videos sorted: 0");
    }

    @Test
    void aYearHoldingNothingIsReportedWithoutClaimingTheInboxIsEmpty(@TempDir final Path root) {
        this.answering(nothingSorted());

        assertThat(this.run(root, "sort", "2024").out().lines())
                .containsExactly("Nothing to sort for this scope.");
    }

    @Test
    void aRunNarrowedByMonthsAloneIsReportedTheSameWayAYearIs(@TempDir final Path root) {
        this.answering(nothingSorted());

        assertThat(this.run(root, "sort", "2024", "--months", "6-8").out().lines())
                .containsExactly("Nothing to sort for this scope.");
    }

    @Test
    void aRunNarrowedToTheOldestFewIsReportedWithoutClaimingTheInboxIsEmpty(@TempDir final Path root) {
        this.answering(nothingSorted());

        assertThat(this.run(root, "sort", "--oldest", "30").out().lines())
                .containsExactly("Nothing to sort for this scope.");
    }

    @Test
    void aCallerAskingForADocumentGetsTheCountsAsFields(@TempDir final Path root) {
        this.answering(new SortSummary(10, 2, 1, 5, 1, 1, 0, 3, List.of(), List.of(), Set.of(2019), List.of(), false, 0));

        final CliHarness.Result result = this.run(root, "sort", "2019", "--json");

        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out())
                .contains("\"command\":\"sort\"")
                .contains("\"status\":\"DONE\"")
                .contains("\"photosSorted\":5")
                .contains("\"alreadyInLibrary\":2")
                .contains("\"dateFilesRemoved\":3")
                .contains("\"yearsSorted\":[2019]");
    }

    @Test
    void aScopeTheVerbRefusesStartsNoJobAtAll(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "sort", "2019", "--months", "6,8,11");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.out()).isEmpty();
        verifyNoInteractions(this.pipeline);
        verifyNoInteractions(this.lock);
    }

    @Test
    void aRefusalRaisedInsideTheJobIsAnsweredLikeAnyOther(@TempDir final Path root) {
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(_ -> {
            throw new JobInProgressException("Sluice is already working on something.");
        }));

        final CliHarness.Result result = this.run(root, "sort", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Sluice is already working on something.");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheSortStarts(@TempDir final Path root) {
        this.answering(nothingSorted());

        this.run(root, "sort");

        verify(this.lock).acquire(root);
    }

    @Test
    void aSortTheCallerStoppedStillSaysWhatItDid(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new SortSummary(1, 0, 0, 1, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019), List.of(), true, 4);
        }));

        final CliHarness.Result result = this.run(root, "sort", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out()).contains("Photos sorted: 1");
    }

    @Test
    void aSortStoppedBeforeItFiledAnythingDoesNotCallTheInboxEmpty(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return stoppedWithNothingSorted();
        }));

        final CliHarness.Result result = this.run(root, "sort");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out()).isEqualTo("Stopped before anything was sorted. Your Inbox is unchanged."
                + System.lineSeparator());
    }

    @Test
    void datesFallingBackToTheFileClockAreSaidBesideTheAnswerRatherThanInIt(@TempDir final Path root) {
        this.answering(new SortSummary(1, 0, 0, 1, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019),
                List.of("sidecars are present but almost none paired"), false, 0));

        final CliHarness.Result result = this.run(root, "sort", "2019");

        assertThat(result.out()).doesNotContain("dates on these photos");
        assertThat(result.err()).contains("The dates on these photos may be wrong.");
    }

    @Test
    void thatSameFallbackReachesADocumentAsAFieldRatherThanAsASentence(@TempDir final Path root) {
        this.answering(new SortSummary(1, 0, 0, 1, 0, 0, 0, 0, List.of(), List.of(), Set.of(2019),
                List.of("sidecars are present but almost none paired"), false, 0));

        final String document = this.run(root, "sort", "2019", "--json").out();

        assertThat(document).contains("\"datesMayBeWrong\":true").doesNotContain("sidecars");
    }

    @Test
    void aQuietSortReportsNoProgress(@TempDir final Path root) {
        this.reporting(nothingSorted());

        final CliHarness.Result result = this.run(root, "sort", "--quiet");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(result.out()).contains("Nothing in your Inbox was ready to sort.");
        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
    }

    @Test
    void theQuietFlagReadsTheSameBeforeTheVerbAsAfterIt(@TempDir final Path root) {
        this.reporting(nothingSorted());

        this.run(root, "--quiet", "sort");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void aStoppedSortNamesItselfStoppedInTheDocumentTheAgentReads(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return stoppedWithNothingSorted();
        }));

        final CliHarness.Result result = this.run(root, "sort", "--json");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out()).contains("\"status\":\"CANCELLED\"").contains("\"command\":\"sort\"");
    }

    @Test
    void aSortNobodyAskedToBeQuietReportsItsProgress(@TempDir final Path root) {
        this.reporting(nothingSorted());

        this.run(root, "sort");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).contains("Sorting...");
    }

    // Started when the verb calls, not when the stub is written. Submitted up front, the job has
    // already run and reported by the time the command turns progress off, so a broken --quiet
    // would pass.
    private void answering(final SortSummary summary) {
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(_ -> summary));
    }

    private void reporting(final SortSummary summary) {
        when(this.pipeline.sort(any())).thenAnswer(_ -> this.runner.submit(_ -> {
            this.progress.phaseStarted("Sorting...");
            this.progress.tick("Sorting...", 1, 1);
            this.progress.phaseFinished("Sorting...");
            return summary;
        }));
    }

    private static SortSummary nothingSorted() {
        return new SortSummary(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Set.of(), List.of(), false, 0);
    }

    private static SortSummary stoppedWithNothingSorted() {
        return new SortSummary(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Set.of(), List.of(), true, 0);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root),
                this.pipeline, new UsableRoots());
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, this.progress),
                this.progress);
        return CliHarness.run(CliHarness.parser(new SortCommand(this.pipeline, jobs)), args);
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
