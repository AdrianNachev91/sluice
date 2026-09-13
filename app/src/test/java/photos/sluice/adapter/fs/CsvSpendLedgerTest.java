package photos.sluice.adapter.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.RunEnding;
import photos.sluice.application.port.out.SpendLedgerEntry;
import photos.sluice.config.PathsConfig;
import photos.sluice.config.SettingsFixture;
import photos.sluice.config.SettingsHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvSpendLedgerTest {

    private static final String HEADER = "\"ended_at\",\"scope\",\"provider\",\"model\",\"tile_size\","
            + "\"tiles_per_row\",\"montages_sifted\",\"montages_skipped\",\"api_calls\",\"input_tokens\","
            + "\"output_tokens\",\"ending\"";

    @Test
    void readAnswersNothingWhenNoRunHasBeenRecorded(@TempDir final Path workingRoot) {
        assertThat(ledgerAt(workingRoot).read()).isEmpty();
    }

    @Test
    void appendCreatesTheFileWithItsHeader(@TempDir final Path workingRoot) throws IOException {
        ledgerAt(workingRoot).append(entry("2019-06", RunEnding.APPLIED));

        assertThat(Files.readAllLines(ledgerFile(workingRoot), StandardCharsets.UTF_8)).containsExactly(
                HEADER,
                "\"2026-08-22T10:00:00Z\",\"2019-06\",\"anthropic\",\"claude-sonnet-5\",\"224\",\"5\","
                        + "\"28\",\"0\",\"33\",\"162000\",\"120000\",\"APPLIED\"");
    }

    @Test
    void anAppendedRunRoundTripsThroughARead(@TempDir final Path workingRoot) {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);
        final SpendLedgerEntry run = entry("2019-06", RunEnding.APPLIED);

        ledger.append(run);

        assertThat(ledger.read()).containsExactly(run);
    }

    @Test
    void runsComeBackInTheOrderTheyWereAppended(@TempDir final Path workingRoot) {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);

        ledger.append(entry("2019-06", RunEnding.APPLIED));
        ledger.append(entry("2020-07", RunEnding.CEILING_REACHED));

        assertThat(ledger.read()).extracting(SpendLedgerEntry::scope).containsExactly("2019-06", "2020-07");
    }

    @Test
    void appendAddsARowWithoutRepeatingTheHeader(@TempDir final Path workingRoot) throws IOException {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);

        ledger.append(entry("2019-06", RunEnding.APPLIED));
        ledger.append(entry("2020-07", RunEnding.CANCELLED));

        assertThat(Files.readAllLines(ledgerFile(workingRoot), StandardCharsets.UTF_8))
                .filteredOn(HEADER::equals).hasSize(1);
    }

    @Test
    void appendInsertsTheMissingNewlineWhenTheLastLineWasNotTerminated(@TempDir final Path workingRoot)
            throws IOException {
        final Path csv = ledgerFile(workingRoot);
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, HEADER + System.lineSeparator() + line("2019-06", "APPLIED"),
                StandardCharsets.UTF_8);

        ledgerAt(workingRoot).append(entry("2020-07", RunEnding.CANCELLED));

        assertThat(ledgerAt(workingRoot).read()).extracting(SpendLedgerEntry::scope)
                .containsExactly("2019-06", "2020-07");
    }

    @Test
    void readTakesAFileCarryingABomAndCarriageReturns(@TempDir final Path workingRoot) throws IOException {
        final Path csv = ledgerFile(workingRoot);
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "﻿" + HEADER + "\r\n" + line("2019-06", "APPLIED") + "\r\n",
                StandardCharsets.UTF_8);

        assertThat(ledgerAt(workingRoot).read()).containsExactly(entry("2019-06", RunEnding.APPLIED));
    }

    @Test
    void aRunWithNoModelComesBackWithNoModel(@TempDir final Path workingRoot) {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);
        final SpendLedgerEntry run = new SpendLedgerEntry(Instant.parse("2026-08-22T10:00:00Z"), "2019-06",
                "external-agent", null, 224, 5, 28, 0, 0, 0, 0, RunEnding.APPLIED);

        ledger.append(run);

        assertThat(ledger.read()).containsExactly(run);
    }

    @Test
    void aFieldHoldingACommaOrAQuoteSurvivesTheRoundTrip(@TempDir final Path workingRoot) {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);
        final SpendLedgerEntry run = new SpendLedgerEntry(Instant.parse("2026-08-22T10:00:00Z"),
                "oldest-30", "a,provider", "a \"quoted\" model", 224, 5, 1, 0, 1, 10, 5, RunEnding.APPLIED);

        ledger.append(run);

        assertThat(ledger.read()).containsExactly(run);
    }

    @Test
    void aLineWithTooFewFieldsIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, "\"2026-08-22T10:00:00Z\",\"2019-06\",\"anthropic\"");

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    @Test
    void aLineWhoseCountsAreNotNumbersIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, line("2019-06", "APPLIED").replace("\"28\"", "\"many\""));

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    @Test
    void aLineWhoseEndingIsNotOneWeWriteIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, line("2019-06", "SOMETHING_ELSE"));

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    @Test
    void aLineWhoseTimeIsNotATimeIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, line("2019-06", "APPLIED").replace("2026-08-22T10:00:00Z", "last tuesday"));

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    // A negative count would otherwise reach the ceiling arithmetic and make it impossible to build,
    // and that refusal would surface as a sift that will not start.
    @Test
    void aLineWhoseCountsAreNegativeIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, line("2019-06", "APPLIED").replace("\"120000\"", "\"-1\""));

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    @Test
    void aLineWhoseMontageGridIsNotPositiveIsRefused(@TempDir final Path workingRoot) throws IOException {
        writeLedger(workingRoot, line("2019-06", "APPLIED").replace("\"224\"", "\"0\""));

        assertThatThrownBy(() -> ledgerAt(workingRoot).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed spend ledger line");
    }

    @Test
    void setAsideMovesTheLedgerAndLeavesTheNextReadEmpty(@TempDir final Path workingRoot,
                                                         @TempDir final Path graveyard) {
        final CsvSpendLedger ledger = ledgerAt(workingRoot);
        ledger.append(entry("2019-06", RunEnding.APPLIED));
        final Path filedAt = graveyard.resolve("kept").resolve("spend-ledger-2026-08-22.csv");

        assertThat(ledger.setAside(filedAt)).isTrue();

        assertThat(filedAt).isRegularFile().content().contains("2019-06");
        assertThat(ledger.read()).isEmpty();
    }

    @Test
    void setAsideAnswersThatThereWasNoLedgerToMove(@TempDir final Path workingRoot, @TempDir final Path graveyard) {
        final Path filedAt = graveyard.resolve("spend-ledger-2026-08-22.csv");

        assertThat(ledgerAt(workingRoot).setAside(filedAt)).isFalse();

        assertThat(filedAt).doesNotExist();
    }

    @Test
    void anAppendGoesToTheWorkingRootConfiguredWhenItRuns(@TempDir final Path before, @TempDir final Path after) {
        final var holder = new SettingsHolder(SettingsFixture.settings(
                new PathSettings(before.toString(), before.toString(), before.resolve("Inbox").toString())));
        final var ledger = new CsvSpendLedger(new PathsConfig(holder));
        ledger.append(entry("2019-06", RunEnding.APPLIED));

        holder.apply(SettingsFixture.settings(
                new PathSettings(after.toString(), after.toString(), after.resolve("Inbox").toString())));
        ledger.append(entry("2020-07", RunEnding.APPLIED));

        assertThat(ledger.read()).extracting(SpendLedgerEntry::scope).containsExactly("2020-07");
        assertThat(ledgerFile(before)).isRegularFile();
    }

    private static void writeLedger(final Path workingRoot, final String row) throws IOException {
        final Path csv = ledgerFile(workingRoot);
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, HEADER + System.lineSeparator() + row + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String line(final String scope, final String ending) {
        return "\"2026-08-22T10:00:00Z\",\"" + scope + "\",\"anthropic\",\"claude-sonnet-5\",\"224\",\"5\","
                + "\"28\",\"0\",\"33\",\"162000\",\"120000\",\"" + ending + "\"";
    }

    private static SpendLedgerEntry entry(final String scope, final RunEnding ending) {
        return new SpendLedgerEntry(Instant.parse("2026-08-22T10:00:00Z"), scope, "anthropic",
                "claude-sonnet-5", 224, 5, 28, 0, 33, 162_000, 120_000, ending);
    }

    private static Path ledgerFile(final Path workingRoot) {
        return workingRoot.resolve("logs").resolve("spend-ledger.csv");
    }

    private static CsvSpendLedger ledgerAt(final Path workingRoot) {
        return new CsvSpendLedger(SettingsFixture.workingRoot(workingRoot));
    }
}
