package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.CsvSpendLedger;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendCeiling;
import photos.sluice.application.port.out.SpendForecast;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.application.port.out.SpendLedgerPort;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.MontageConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SpendEstimatorTest {

    private static final MontageConfig SHIPPED = new MontageConfig(224, 5);
    private static final String MODEL = "claude-sonnet-5";

    @Test
    void aProviderThatConsumesNothingIsEstimatedAtNothing(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(3, new SpendForecast.NoSpend(), MODEL, SHIPPED);

        assertThat(estimate).isEqualTo(new SpendEstimate(0, 0, true, false, false));
    }

    @Test
    void theInputHalfIsWhatTheProviderCountedTimesTheCallsAMontageTakes(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(3, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.inputTokens()).isEqualTo(3_600);
        assertThat(estimate.exactInput()).isTrue();
    }

    @Test
    void theOutputHalfIsTheSeedUntilThisInstallHasRunsOfItsOwn(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(3, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.outputTokens()).isEqualTo(12_900);
        assertThat(estimate.historicOutput()).isFalse();
    }

    @Test
    void aRecordedRunOnTheSameModelAndGridReplacesTheSeed(@TempDir final Path workingRoot) {
        final SpendLedgerPort ledger = ledger(workingRoot);
        ledger.append(recordedRun(10, 20, 100_000));

        final SpendEstimate estimate = new SpendEstimator(ledger)
                .estimate(3, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.inputTokens()).isEqualTo(6_000);
        assertThat(estimate.outputTokens()).isEqualTo(30_000);
        assertThat(estimate.historicOutput()).isTrue();
    }

    @Test
    void aProviderThatCouldNotCountIsEstimatedFromTheSeedInstead(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(1, new SpendForecast.Unknown("no network"), MODEL, SHIPPED);

        assertThat(estimate.inputTokens()).isEqualTo(6_948);
        assertThat(estimate.exactInput()).isFalse();
    }

    @Test
    void aLedgerNobodyCanParseLeavesTheRunEstimatedWithoutHistory(@TempDir final Path workingRoot)
            throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("spend-ledger.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "not a ledger line at all" + System.lineSeparator(), StandardCharsets.UTF_8);

        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(3, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.outputTokens()).isEqualTo(12_900);
        assertThat(estimate.historicOutput()).isFalse();
        assertThat(estimate.historyUnreadable()).isTrue();
    }

    @Test
    void anInstallWithNoRunsYetIsNotReportedAsHavingAnUnreadableRecord(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot)
                .estimate(3, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.historicOutput()).isFalse();
        assertThat(estimate.historyUnreadable()).isFalse();
    }

    @Test
    void theCallBoundAllowsEveryMontageOneAttemptAndOneCorrection(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 28;
        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimator.ceilingFor(montages, montages, estimate))
                .isNotNull().extracting(SpendCeiling::maxCalls).isEqualTo(56);
    }

    @Test
    void theTokenBoundIsAMultipleOfWhatOneMontageIsExpectedToCost(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 3;
        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimator.ceilingFor(montages, montages, estimate))
                .isNotNull().extracting(SpendCeiling::tokenBudgetPerMontage).isEqualTo(16_500L);
    }

    @Test
    void theTokenBoundHoldsItsFireOverTheFirstFewMontages(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 28;
        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimator.ceilingFor(montages, montages, estimate))
                .isNotNull().extracting(SpendCeiling::armAfterMontages).isEqualTo(5);
    }

    @Test
    void aProviderThatConsumesNothingIsGivenNoCeilingToEnforce(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 3;
        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.NoSpend(), MODEL, SHIPPED);

        assertThat(estimator.ceilingFor(montages, montages, estimate)).isNull();
    }

    @Test
    void aRunWithNoMontagesIsGivenNoCeilingToEnforce(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 0;
        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimator.ceilingFor(montages, montages, estimate)).isNull();
    }

    // The ceiling is built one call after the ledger read. A value that parses and is still
    // impossible would reach that arithmetic, and a refused ceiling is a sift that will not start.
    @Test
    void aLedgerLineNothingCanBelieveStillLeavesTheRunWithACeiling(@TempDir final Path workingRoot)
            throws IOException {
        final Path csv = workingRoot.resolve("logs").resolve("spend-ledger.csv");
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "\"2026-08-22T10:00:00Z\",\"2018\",\"anthropic\",\"claude-sonnet-5\",\"224\",\"5\","
                + "\"1\",\"0\",\"1\",\"10\",\"-5\",\"APPLIED\"" + System.lineSeparator(),
                StandardCharsets.UTF_8);
        final SpendEstimator estimator = estimator(workingRoot);
        final int montages = 3;

        final SpendEstimate estimate = estimator.estimate(montages, new SpendForecast.Counted(1_000), MODEL, SHIPPED);

        assertThat(estimate.outputTokens()).isPositive();
        assertThat(estimator.ceilingFor(montages, montages, estimate)).isNotNull();
    }

    @Test
    void aProviderThatCallsNoModelCostsNothingBeforeAnythingIsPrepared(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot).estimateBeforePreparing(500, false, MODEL, SHIPPED);

        assertThat(estimate).isEqualTo(new SpendEstimate(0, 0, true, false, false));
    }

    @Test
    void photosAreCountedIntoSheetsWithAPartOneStillCostingAWholeCall(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);

        final SpendEstimate two = estimator.estimateBeforePreparing(50, true, MODEL, SHIPPED);
        final SpendEstimate three = estimator.estimateBeforePreparing(51, true, MODEL, SHIPPED);

        assertThat(three.totalTokens()).isGreaterThan(two.totalTokens());
        assertThat(three.totalTokens()).isEqualTo(two.totalTokens() / 2 * 3);
    }

    @Test
    void aDenserGridPutsTheSameScopeOntoFewerSheets(@TempDir final Path workingRoot) {
        final SpendEstimator estimator = estimator(workingRoot);

        final SpendEstimate atFive = estimator.estimateBeforePreparing(100, true, MODEL, SHIPPED);
        final SpendEstimate atTen = estimator.estimateBeforePreparing(100, true, MODEL, new MontageConfig(224, 10));

        assertThat(atFive.totalTokens()).isEqualTo(atTen.totalTokens() * 4);
    }

    @Test
    void anEstimateTakenBeforePreparingNeverClaimsItsInputHalfWasCounted(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot).estimateBeforePreparing(100, true, MODEL, SHIPPED);

        assertThat(estimate.exactInput()).isFalse();
        assertThat(estimate.inputTokens()).isPositive();
    }

    @Test
    void aScopeHoldingNoPhotosAtAllCostsNothing(@TempDir final Path workingRoot) {
        final SpendEstimate estimate = estimator(workingRoot).estimateBeforePreparing(0, true, MODEL, SHIPPED);

        assertThat(estimate.totalTokens()).isZero();
    }

    @Test
    void anEstimateTakenBeforePreparingProjectsItsOutputHalfFromARecordedRun(@TempDir final Path workingRoot) {
        final SpendLedgerPort ledger = ledger(workingRoot);
        ledger.append(recordedRun(10, 20, 100_000));

        final SpendEstimate estimate = new SpendEstimator(ledger)
                .estimateBeforePreparing(100, true, MODEL, SHIPPED);

        assertThat(estimate.historicOutput()).isTrue();
    }

    private static SpendLedgerEntry recordedRun(final int montagesCulled, final int apiCalls,
                                                final long outputTokens) {
        return new SpendLedgerEntry(Instant.parse("2026-08-22T10:00:00Z"), "2018", "anthropic", MODEL,
                SHIPPED.tileSize(), SHIPPED.tilesPerRow(), montagesCulled, 0, apiCalls, 50_000, outputTokens,
                RunEnding.APPLIED);
    }


    private static SpendLedgerPort ledger(final Path workingRoot) {
        return new CsvSpendLedger(SettingsFixture.workingRoot(workingRoot));
    }

    private static SpendEstimator estimator(final Path workingRoot) {
        return new SpendEstimator(ledger(workingRoot));
    }
}
