package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.domain.sift.MontageConfig;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpendRateTest {

    private static final MontageConfig SHIPPED = new MontageConfig(224, 5);
    private static final String MODEL = "claude-sonnet-5";

    @Test
    void anInstallWithNoRunsOfItsOwnFallsBackToTheShippedSeed() {
        assertThat(SpendRate.from(List.of(), MODEL, SHIPPED)).isEqualTo(SpendRate.seed());
    }

    @Test
    void theSeedIsNotOfferedAsHistory() {
        assertThat(SpendRate.seed().fromHistory()).isFalse();
    }

    @Test
    void aRateDerivedFromRecordedRunsSaysSo() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 10, 12, 43_000)), MODEL, SHIPPED).fromHistory())
                .isTrue();
    }

    @Test
    void theRateIsWhatTheRecordedRunsAveragedOut() {
        final SpendRate rate = SpendRate.from(List.of(run(MODEL, SHIPPED, 10, 12, 43_000)), MODEL, SHIPPED);

        assertThat(rate.callsPerMontage()).isCloseTo(1.2, within(0.001));
        assertThat(rate.outputTokensPerMontage()).isEqualTo(4_300);
    }

    @Test
    void everyMatchingRunFeedsTheSameAverage() {
        final SpendRate rate = SpendRate.from(
                List.of(run(MODEL, SHIPPED, 10, 10, 40_000), run(MODEL, SHIPPED, 10, 20, 60_000)),
                MODEL, SHIPPED);

        assertThat(rate.callsPerMontage()).isCloseTo(1.5, within(0.001));
        assertThat(rate.outputTokensPerMontage()).isEqualTo(5_000);
    }

    @Test
    void aRunOnAnotherModelIsNotWhatThisModelIsExpectedToCost() {
        assertThat(SpendRate.from(List.of(run("claude-opus-5", SHIPPED, 10, 20, 90_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunAtAnotherTileSizeIsNotWhatThisGridIsExpectedToCost() {
        assertThat(SpendRate.from(List.of(run(MODEL, new MontageConfig(448, 5), 10, 12, 43_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunAtAnotherTilesPerRowIsNotWhatThisGridIsExpectedToCost() {
        assertThat(SpendRate.from(List.of(run(MODEL, new MontageConfig(224, 7), 10, 12, 43_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aProviderThatNamesNoModelNeverMatchesAModelledRun() {
        assertThat(SpendRate.from(List.of(run(null, SHIPPED, 10, 0, 0)), null, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunThatJudgedNoMontageSaysNothingAboutWhatOneCosts() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 0, 0, 0)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunClaimingMoreOutputThanAMontageCanProduceIsLeftOutOfTheRate() {
        final List<SpendLedgerEntry> runs = List.of(
                run(MODEL, SHIPPED, 10, 12, 43_000),
                run(MODEL, SHIPPED, 1, 2, 40_000));

        assertThat(SpendRate.from(runs, MODEL, SHIPPED).outputTokensPerMontage()).isEqualTo(4_300);
    }

    @Test
    void aRunAtExactlyTheCredibleOutputCeilingStillCounts() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 1, 2, 32_768)), MODEL, SHIPPED)
                .outputTokensPerMontage()).isEqualTo(32_768);
    }

    // A judged montage cost at least one call, so fewer calls than montages is a wrong column. It is
    // dropped rather than pulled up to one, which would carry the wrong line into the rate.
    @Test
    void aRunRecordingFewerCallsThanMontagesIsLeftOutOfTheRate() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 10, 4, 40_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    // The run a runaway is stopped on is the one that must not set the next run's ceiling. On a
    // fresh install it would be the whole history.
    @Test
    void aRunTheCeilingStoppedIsNotWhatANormalRunCosts() {
        assertThat(SpendRate.from(List.of(ending(RunEnding.CEILING_REACHED, 10, 20, 90_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aCancelledRunIsAFragmentRatherThanARate() {
        assertThat(SpendRate.from(List.of(ending(RunEnding.CANCELLED, 10, 20, 90_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void anAbandonedRunIsNotWhatANormalRunCosts() {
        assertThat(SpendRate.from(List.of(ending(RunEnding.FAILED, 10, 20, 90_000)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunWhoseApplyWasRefusedStillSaysWhatItsSiftCost() {
        assertThat(SpendRate.from(List.of(ending(RunEnding.BLOCKED, 10, 12, 43_000)), MODEL, SHIPPED)
                .outputTokensPerMontage()).isEqualTo(4_300);
    }

    @Test
    void aRunRecordingMoreCallsThanAMontageIsAllowedIsLeftOutOfTheRate() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 1, 2_000_000_000, 100)), MODEL, SHIPPED))
                .isEqualTo(SpendRate.seed());
    }

    @Test
    void aRunAtExactlyTwoCallsAMontageStillCounts() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 10, 20, 40_000)), MODEL, SHIPPED)
                .callsPerMontage()).isEqualTo(2.0);
    }

    @Test
    void aRunAtExactlyOneCallAMontageStillCounts() {
        assertThat(SpendRate.from(List.of(run(MODEL, SHIPPED, 10, 10, 40_000)), MODEL, SHIPPED)
                .callsPerMontage()).isEqualTo(1.0);
    }

    private static SpendLedgerEntry ending(final RunEnding ending, final int montagesSifted, final int apiCalls,
                                           final long outputTokens) {
        final SpendLedgerEntry completed = run(MODEL, SHIPPED, montagesSifted, apiCalls, outputTokens);
        return new SpendLedgerEntry(completed.endedAt(), completed.scope(), completed.providerId(),
                completed.modelId(), completed.tileSize(), completed.tilesPerRow(), completed.montagesSifted(),
                completed.montagesSkipped(), completed.apiCalls(), completed.inputTokens(),
                completed.outputTokens(), ending);
    }

    private static SpendLedgerEntry run(final @Nullable String modelId, final MontageConfig grid,
                                        final int montagesSifted,
                                        final int apiCalls, final long outputTokens) {
        return new SpendLedgerEntry(Instant.parse("2026-08-22T10:00:00Z"), "2019-06", "anthropic", modelId,
                grid.tileSize(), grid.tilesPerRow(), montagesSifted, 0, apiCalls, 100_000, outputTokens,
                RunEnding.APPLIED);
    }
}
