package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class CullOutcomeReportTest {

    private static final Instant SINCE = Instant.parse("2026-08-20T10:15:30Z");

    private static final Path DUPLICATES = Path.of("Duplicates");

    private static final Function<Path, String> INSTRUCTIONS = prepDir -> "Judge the sheets in " + prepDir + ".";

    @Test
    void aCompletedRunReportsWhatApplyMovedAndExitsDone() {
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(
                new CullReport(4, 0, 6, TokenSpend.none("external-agent"), false),
                new ApplyReport(10, Map.of("Junk", 3), 1, 2, 5, List.of()), null, null);

        final CommandOutcome outcome = CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.DONE);
        assertThat(outcome.resultLines()).containsExactly("Photos looked at: 10", "Sheets judged: 4",
                "Calls to your provider: 6", "Junk: 3", "Near-duplicate groups: 2",
                "Copies moved to Duplicates: 5",
                "Could not be judged: 1");
    }

    @Test
    void aRunWaitingBecauseTheCallerCancelledPointsAtResumeAndExitsWaiting() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"), WaitingReason.CANCELLED,
                zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.WAITING);
        assertThat(outcome.resultLines()).containsExactly(
                "Stopped. You can continue at any time - run 'resume 2019'.");
    }

    @Test
    void aRunStoppedWhileItMovedPhotosCountsThemAndSaysTheyHaveLeftSorted() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"), WaitingReason.CANCELLED,
                zeroReport(), null, new ApplyReport(120, Map.of("Junk", 31), 2, 1, 4, List.of()));

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.WAITING);
        assertThat(outcome.resultLines()).containsExactly(
                "Stopped. You can continue at any time - run 'resume 2019'.",
                "It had started moving photos. These left Sorted before it stopped.",
                "Photos that left Sorted: 37",
                "Junk: 31", "Copies moved to Duplicates: 4", "Could not be judged: 2");
    }

    // Every count zero is the apply that was entered and gave up before its first move.
    @Test
    void aRunStoppedBeforeItMovedAnythingClaimsNothingAboutSorted() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"), WaitingReason.CANCELLED,
                zeroReport(), null, new ApplyReport(120, Map.of(), 0, 0, 0, List.of()));

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.resultLines()).containsExactly(
                "Stopped. You can continue at any time - run 'resume 2019'.");
    }

    // A near-duplicate group is resolved by copying its keeper, which stays in Sorted. It is the
    // one count an apply can raise without a photo leaving.
    @Test
    void aRunThatOnlyResolvedNearDuplicateGroupsClaimsNothingLeftSorted() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"), WaitingReason.CANCELLED,
                zeroReport(), null, new ApplyReport(120, Map.of(), 0, 1, 0, List.of()));

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.resultLines()).containsExactly(
                "Stopped. You can continue at any time - run 'resume 2019'.");
    }

    @Test
    void aRunWaitingOnAnAgentNamesNoScopeSinceTheReaderAlreadyKnowsIt() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019-06"),
                WaitingReason.SHARDS_OUTSTANDING, zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.WAITING);
        assertThat(outcome.resultLines()).containsExactly("The sheets are ready, waiting for your agent's "
                + "decisions on them. Nothing moves until those decisions arrive.",
                "", "Judge the sheets in " + Path.of("logs", "sift-prep", "2019-06") + ".");
    }

    @Test
    void aRunWaitingOnAnAgentHandsOverTheInstructionsForJudgingIt() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"),
                WaitingReason.SHARDS_OUTSTANDING, zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.payload()).isInstanceOfSatisfying(CullPayloads.OutcomePayload.class,
                payload -> assertThat(payload.instructions()).isEqualTo("Judge the sheets in "
                        + Path.of("logs", "sift-prep", "2019") + "."));
    }

    @Test
    void anEndingThatIsNotWaitingOnAnAgentAsksForNoInstructions() {
        final CullJobOutcome.Waiting cancelled = new CullJobOutcome.Waiting(job("2019"),
                WaitingReason.CANCELLED, zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(cancelled, DUPLICATES, _ -> {
            throw new AssertionError("asked for instructions on an ending with no agent waiting");
        });

        assertThat(outcome.payload()).isInstanceOfSatisfying(CullPayloads.OutcomePayload.class,
                payload -> assertThat(payload.instructions()).isNull());
    }

    @Test
    void aCeilingStopReassuresNothingMoreWasSpentAndPointsAtResume() {
        final CullJobOutcome.Waiting waiting = new CullJobOutcome.Waiting(job("2019"),
                WaitingReason.CEILING_REACHED, zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(waiting, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.WAITING);
        assertThat(outcome.resultLines()).containsExactly("Sift stopped because it went far past what it "
                + "was expected to cost. Nothing more has been spent from your provider account "
                + "balance. Run 'resume 2019' to continue under a fresh limit.");
    }

    @Test
    void aBlockedRunPointsAtTroubleshootRatherThanCountingItsFindings() {
        final CullJobOutcome.Blocked oneFinding = new CullJobOutcome.Blocked(job("2019"),
                List.of(new Finding.CorruptSidecar("montage-002")), zeroReport(), null);
        final CullJobOutcome.Blocked twoFindings = new CullJobOutcome.Blocked(job("2019"),
                List.of(new Finding.CorruptSidecar("montage-001"), new Finding.CorruptSidecar("montage-002")),
                zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(oneFinding, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.BLOCKED);
        assertThat(outcome.resultLines()).containsExactly(
                "Every sheet came back, but there are problems with some. Your photos are still in Sorted. Run "
                        + "'troubleshoot 2019' to see what went wrong.");
        assertThat(CullOutcomeReport.of(twoFindings, DUPLICATES, INSTRUCTIONS).resultLines())
                .isEqualTo(outcome.resultLines());
    }

    @Test
    void aRunCancelledBeforeAnyMontageRenderedExitsCancelled() {
        final CullJobOutcome.Cancelled cancelled = new CullJobOutcome.Cancelled(zeroReport(), null);

        final CommandOutcome outcome = CullOutcomeReport.of(cancelled, DUPLICATES, INSTRUCTIONS);

        assertThat(outcome.status()).isEqualTo(CommandStatus.CANCELLED);
        assertThat(outcome.resultLines()).containsExactly(
                "Stopped. No sheets were built yet, and nothing was sent to your provider.");
    }

    @Test
    void whatWasActuallySpentIsNotedWhenAnythingWas() {
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(
                new CullReport(2, 0, 3, new TokenSpend(1000, 500, "anthropic", "claude-sonnet-5"), false),
                new ApplyReport(2, Map.of(), 0, 0, 0, List.of()), null, null);

        assertThat(CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS).noteLines())
                .containsExactly("Spent: 1,500 tokens across 3 calls.");
    }

    @Test
    void nothingSpentIsSilentRatherThanSayingZero() {
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(zeroReport(),
                new ApplyReport(0, Map.of(), 0, 0, 0, List.of()), null, null);

        assertThat(CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS).noteLines()).isEmpty();
    }

    @Test
    void aSingleCallIsNamedInTheSingular() {
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(
                new CullReport(1, 0, 1, new TokenSpend(10, 5, "anthropic", "claude-sonnet-5"), false),
                new ApplyReport(1, Map.of(), 0, 0, 0, List.of()), null, null);

        assertThat(CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS).noteLines())
                .containsExactly("Spent: 15 tokens across 1 call.");
    }

    @Test
    void anArchivedPriorRunIsNamedInItsNewLocation() {
        final Path graveyard = Path.of("logs", "archives", "2019-2026-08-29_18-50-45");
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(zeroReport(),
                new ApplyReport(1, Map.of(), 0, 0, 0, List.of()), graveyard, null);

        assertThat(CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS).noteLines()).containsExactly(
                "A previous sift of this scope was moved to " + graveyard + ".");
    }

    @Test
    void bothNotesRideTogetherWhenBothApply() {
        final Path graveyard = Path.of("logs", "archives", "2019-2026-08-29_18-50-45");
        final CullJobOutcome.Applied applied = new CullJobOutcome.Applied(
                new CullReport(1, 0, 1, new TokenSpend(10, 5, "anthropic", "claude-sonnet-5"), false),
                new ApplyReport(1, Map.of(), 0, 0, 0, List.of()), graveyard, null);

        assertThat(CullOutcomeReport.of(applied, DUPLICATES, INSTRUCTIONS).noteLines())
                .containsExactly("Spent: 15 tokens across 1 call.",
                        "A previous sift of this scope was moved to " + graveyard + ".");
    }

    private static CullReport zeroReport() {
        return CullReport.nothingSpent("external-agent", 0);
    }

    private static WaitingCullJob job(final String scope) {
        return new WaitingCullJob(scope, Path.of("logs", "sift-prep", scope), new ShardTally(1, 0, 2), SINCE);
    }
}
