package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.job.WaitingSiftJob;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiftPayloadsTest {

    private static final Path PREP_DIR = Path.of("D:", "Repos", "Sluice", "logs", "sift-prep", "2019-06");
    private static final Instant WRITTEN = Instant.parse("2026-08-20T10:15:30Z");

    @Test
    void aRunCarriesTheScopeEveryOtherCommandAddressesItBy() {
        final SiftPayloads.RunPayload payload = SiftPayloads.run(run(PrepDirHealth.State.WAITING,
                List.of(), new ShardTally(12, 11, 25)));

        assertThat(payload.scope()).isEqualTo("2019-06");
        assertThat(payload.prepDir()).isEqualTo(PREP_DIR.toString());
        assertThat(payload.state()).isEqualTo(PrepDirHealth.State.WAITING);
        assertThat(payload.shards()).isEqualTo(new SiftPayloads.ShardsPayload(12, 11, 25));
    }

    @Test
    void aRunNobodyCouldCountTheSheetsOfSaysSoRatherThanReportingZero() {
        final SiftPayloads.RunPayload payload = SiftPayloads.run(run(PrepDirHealth.State.DAMAGED,
                List.of(new Finding.UnreadablePrepDir(PREP_DIR)), null));

        assertThat(payload.shards()).isNull();
    }

    @Test
    void aRunCarriesEveryProblemStillOpenOnIt() {
        final SiftPayloads.RunPayload payload = SiftPayloads.run(run(PrepDirHealth.State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002"),
                        new Finding.MissingShard("montage-003", "decisions-003.json")), new ShardTally(25, 24, 25)));

        assertThat(payload.findings()).extracting(FindingPayload::type)
                .containsExactly("CorruptSidecar", "MissingShard");
    }

    @Test
    void whenARunWasLastWrittenToIsWrittenTheWayEverythingElseReadsIt() {
        assertThat(SiftPayloads.run(run(PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25))).since())
                .isEqualTo("2026-08-20T10:15:30Z");
    }

    @Test
    void aFinishedSiftReportsWhatItMovedAndWhatItCost() {
        final SiftPayloads.OutcomePayload payload = SiftPayloads.outcome(new SiftJobOutcome.Applied(
                report(), new ApplyReport(120, Map.of("junk", 14), 3, 2, 5, List.of()), null, null));

        assertThat(payload.outcome()).isEqualTo("Applied");
        assertThat(payload.applied()).isNotNull();
        assertThat(payload.applied().reviewed()).isEqualTo(120);
        assertThat(payload.report().spend().inputTokens()).isEqualTo(9_000);
    }

    @Test
    void aPausedSiftSaysWhyItPausedAndWhichRunToComeBackTo() {
        final SiftPayloads.OutcomePayload payload = SiftPayloads.outcome(new SiftJobOutcome.Waiting(
                new WaitingSiftJob("2019-06", PREP_DIR, new ShardTally(12, 12, 25), WRITTEN),
                WaitingReason.CEILING_REACHED, report(), null));

        assertThat(payload.outcome()).isEqualTo("Waiting");
        assertThat(payload.reason()).isEqualTo(WaitingReason.CEILING_REACHED);
        assertThat(payload.scope()).isEqualTo("2019-06");
        assertThat(payload.prepDir()).isEqualTo(PREP_DIR.toString());
    }

    @Test
    void aSiftWhoseApplyRefusedCarriesWhatRefusedIt() {
        final SiftPayloads.OutcomePayload payload = SiftPayloads.outcome(new SiftJobOutcome.Blocked(
                new WaitingSiftJob("2019-06", PREP_DIR, new ShardTally(25, 24, 25), WRITTEN),
                List.of(new Finding.CorruptSidecar("montage-002")), report(), null));

        assertThat(payload.outcome()).isEqualTo("Blocked");
        assertThat(payload.findings()).extracting(FindingPayload::type).containsExactly("CorruptSidecar");
    }

    @Test
    void everyWayASiftCanEndReportsWhatItConsumed() {
        assertThat(List.of(
                SiftPayloads.outcome(new SiftJobOutcome.Cancelled(report(), null)),
                SiftPayloads.outcome(new SiftJobOutcome.Applied(report(),
                        new ApplyReport(0, Map.of(), 0, 0, 0, List.of()), null, null)),
                SiftPayloads.outcome(new SiftJobOutcome.Waiting(
                        new WaitingSiftJob("2019-06", PREP_DIR, new ShardTally(1, 1, 2), WRITTEN),
                        WaitingReason.CANCELLED, report(), null)),
                SiftPayloads.outcome(new SiftJobOutcome.Blocked(
                        new WaitingSiftJob("2019-06", PREP_DIR, new ShardTally(2, 2, 2), WRITTEN),
                        List.of(), report(), null))))
                .allSatisfy(payload -> assertThat(payload.report().apiCalls()).isEqualTo(27));
    }

    @Test
    void everyWayASiftCanEndSaysWhereAPreviousRunWasSetAside() {
        final Path graveyard = PREP_DIR.resolveSibling("graveyard").resolve("2019-06");

        assertThat(List.of(
                SiftPayloads.outcome(new SiftJobOutcome.Cancelled(report(), graveyard)),
                SiftPayloads.outcome(new SiftJobOutcome.Applied(report(),
                        new ApplyReport(0, Map.of(), 0, 0, 0, List.of()), graveyard, null))))
                .allSatisfy(payload -> assertThat(payload.archivedPriorRun()).isEqualTo(graveyard.toString()));
    }

    @Test
    void aSiftThatSetNothingAsideLeavesTheFieldOutRatherThanNamingNowhere() {
        assertThat(SiftPayloads.outcome(new SiftJobOutcome.Cancelled(report(), null)).archivedPriorRun()).isNull();
    }

    @Test
    void aSpendNamesTheProviderAndTheModelThatConsumedIt() {
        final SiftPayloads.SpendPayload payload = SiftPayloads.spend(
                new TokenSpend(9_000, 400, "anthropic", "claude-sonnet-5"));

        assertThat(payload.providerId()).isEqualTo("anthropic");
        assertThat(payload.modelId()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void aProviderThatCallsNoModelOfItsOwnLeavesTheModelOut() {
        assertThat(SiftPayloads.spend(TokenSpend.none("external-agent")).modelId()).isNull();
    }

    @Test
    void anExpectationSaysHowMuchToTrustItself() {
        final SiftPayloads.EstimatePayload payload = SiftPayloads.estimate(
                new SpendEstimate(9_000, 400, false, false, false));

        assertThat(payload.exactInput()).isFalse();
        assertThat(payload.historicOutput()).isFalse();
    }

    @Test
    void aCeilingStopIsReportedOnTheRunsOwnReport() {
        final SiftReport stopped = new SiftReport(12, 13, 27,
                new TokenSpend(9_000, 400, "anthropic", "claude-sonnet-5"), true);

        assertThat(SiftPayloads.report(stopped).stoppedAtCeiling()).isTrue();
    }

    private static SiftRunSummary run(final PrepDirHealth.State state, final List<Finding> findings,
                                      final @Nullable ShardTally shards) {
        return new SiftRunSummary("2019-06", PREP_DIR, new PrepDirHealth(state, findings), shards, WRITTEN);
    }

    private static SiftReport report() {
        return new SiftReport(12, 13, 27, new TokenSpend(9_000, 400, "anthropic", "claude-sonnet-5"), false);
    }
}
