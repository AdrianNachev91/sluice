package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.domain.paths.PathViolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CullCommandTest {

    private static final Instant SINCE = Instant.parse("2026-08-20T10:15:30Z");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private final ByteArrayOutputStream reported = new ByteArrayOutputStream();
    private final ConsoleProgressPort progress =
            new ConsoleProgressPort(new PrintStream(this.reported, true, StandardCharsets.UTF_8), false);

    private final InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void aYearWithNoMonthsNarrowsToThatYear(@TempDir final Path root) {
        this.answering(applied());

        this.run(root, "sift", "2019");

        verify(this.pipeline).cull(new CullScope.Year(2019, null));
    }

    @Test
    void aGappedMonthListIsAcceptedAsIs(@TempDir final Path root) {
        this.answering(applied());

        this.run(root, "sift", "2019", "--months", "6,8,11");

        verify(this.pipeline).cull(new CullScope.Year(2019, List.of(6, 8, 11)));
    }

    @Test
    void aCountTakesTheOldestPhotosInsteadOfAYear(@TempDir final Path root) {
        this.answering(applied());

        this.run(root, "sift", "--oldest", "30");

        verify(this.pipeline).cull(new CullScope.OldestN(30));
    }

    @Test
    void aBareSiftWithNoYearOrOldestIsRefused(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "sift");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aCompletedSiftReportsWhatApplyMoved(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Applied(zeroReport(), new ApplyReport(10, Map.of("Junk", 3), 1, 0, 0,
                List.of()), null));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Photos looked at: 10", "Junk: 3",
                "Could not be judged: 1");
    }

    @Test
    void aRunWaitingOnAnAgentNamesNoOfferSinceNothingMovesUntilTheyAnswer(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Waiting(job("2019"), WaitingReason.SHARDS_OUTSTANDING, zeroReport(), null));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.WAITING.exitCode());
        assertThat(result.out()).contains("waiting for your agent's decisions");
    }

    @Test
    void aCancelledSiftThatBecameAWaitingRunExitsWaitingRatherThanCancelled(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Waiting(job("2019"), WaitingReason.CANCELLED, zeroReport(), null));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.WAITING.exitCode());
        assertThat(result.out()).contains("resume 2019");
    }

    @Test
    void aCeilingStopReassuresNothingMoreWasSpent(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Waiting(job("2019"), WaitingReason.CEILING_REACHED, zeroReport(), null));

        assertThat(this.run(root, "sift", "2019").out())
                .contains("went far past what it was expected to cost")
                .contains("Nothing more has been spent from your provider account balance")
                .contains("resume 2019");
    }

    @Test
    void aBlockedRunPointsAtTroubleshootRatherThanListingItsFindings(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Blocked(job("2019"),
                List.of(new Finding.CorruptSidecar("montage-002")), zeroReport(), null));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.BLOCKED.exitCode());
        assertThat(result.out()).contains("troubleshoot 2019").doesNotContain("CorruptSidecar");
        assertThat(this.run(root, "sift", "2019", "--json").out()).contains("\"type\":\"CorruptSidecar\"");
    }

    @Test
    void aSiftTheProviderCouldNotFinishIsBlockedRatherThanRefusedOrFailed(@TempDir final Path root) {
        when(this.pipeline.cull(any())).thenAnswer(_ -> this.runner.submit(_ -> {
            throw new CompletionException(new CullException("montage-003 came back with no decisions"));
        }));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.BLOCKED.exitCode());
        assertThat(result.out()).contains("montage-003 came back with no decisions");
        assertThat(result.err()).doesNotContain("CullException");
    }

    @Test
    void aRunCancelledBeforeAnyMontageRenderedExitsCancelled(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Cancelled(zeroReport(), null));

        final CliHarness.Result result = this.run(root, "sift", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out()).contains("nothing was sent to your provider.");
    }

    @Test
    void whatWasActuallySpentIsNotedWhenAnythingWas(@TempDir final Path root) {
        this.answering(new CullJobOutcome.Applied(
                new CullReport(2, 0, 3, new TokenSpend(1000, 500, "anthropic", "claude-sonnet-5"), false),
                new ApplyReport(2, Map.of(), 0, 0, 0, List.of()), null));

        assertThat(this.run(root, "sift", "2019").err()).contains("Spent: 1,500 tokens across 3 calls.");
    }

    @Test
    void nothingSpentIsSilentRatherThanSayingZero(@TempDir final Path root) {
        this.answering(applied());

        assertThat(this.run(root, "sift", "2019").err()).doesNotContain("Spent");
    }

    @Test
    void anArchivedPriorRunIsNamedInItsNewLocation(@TempDir final Path root) {
        final Path graveyard = root.resolve("graveyard").resolve("2019-06-01");
        this.answering(new CullJobOutcome.Applied(zeroReport(), new ApplyReport(1, Map.of(), 0, 0, 0, List.of()),
                graveyard));

        assertThat(this.run(root, "sift", "2019").err()).contains(graveyard.toString());
    }

    @Test
    void anEstimateIsPrintedBeforeTheJobIsAskedAboutItsOutcome(@TempDir final Path root) {
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(
                List.of(new SortedTally.YearRow(2019, 187, 0, List.of()))));
        when(this.pipeline.estimateFor(187)).thenReturn(new SpendEstimate(50_000, 4_321, false, false, false));
        this.answering(applied());

        this.run(root, "sift", "2019");

        assertThat(this.reported.toString(StandardCharsets.UTF_8))
                .contains("Sifting 187 photos is expected to spend about 54,000 tokens")
                .contains("an estimate, based on default estimates");
    }

    @Test
    void theEstimateIsSilentWhenTheConfiguredProviderSpendsNothing(@TempDir final Path root) {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);
        this.answering(applied());

        this.run(root, "sift", "2019");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).doesNotContain("Sifting");
        verify(this.pipeline, never()).sortedTally();
    }

    @Test
    void theEstimateIsSilentWhenQuietWasAsked(@TempDir final Path root) {
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
        when(this.pipeline.estimateFor(any(Integer.class))).thenReturn(new SpendEstimate(0, 0, false, false, false));
        this.answering(applied());

        this.run(root, "sift", "2019", "--quiet");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void aRefusalRaisedStartingTheJobPrintsNoEstimate(@TempDir final Path root) {
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        when(this.pipeline.cull(any())).thenThrow(new IllegalArgumentException("no key"));

        this.run(root, "sift", "2019");

        assertThat(this.reported.toString(StandardCharsets.UTF_8)).isEmpty();
        verify(this.pipeline, never()).sortedTally();
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheSiftStarts(@TempDir final Path root) {
        this.answering(applied());

        this.run(root, "sift", "2019");

        verify(this.lock).acquire(root);
    }

    private void answering(final CullJobOutcome outcome) {
        when(this.pipeline.cull(any())).thenAnswer(_ -> this.runner.submit(_ -> outcome));
    }

    private static CullJobOutcome.Applied applied() {
        return new CullJobOutcome.Applied(zeroReport(), new ApplyReport(0, Map.of(), 0, 0, 0, List.of()), null);
    }

    private static CullReport zeroReport() {
        return CullReport.nothingSpent("external-agent", 0);
    }

    private static WaitingCullJob job(final String scope) {
        return new WaitingCullJob(scope, Path.of("logs", "sift-prep", scope), new ShardTally(1, 0, 2), SINCE);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, this.progress), this.progress);
        return CliHarness.run(CliHarness.parser(
                new CullCommand(this.pipeline, jobs, this.progress, mock(PathsPort.class))), args);
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
