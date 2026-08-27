package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RunsCommandTest {

    private static final Path PREP_ROOT = Path.of("D:", "Repos", "Sluice", "logs", "sift-prep");
    private static final Instant WRITTEN = Instant.parse("2026-08-20T10:15:30.069830400Z");

    private final Pipeline pipeline = mock(Pipeline.class);

    @Test
    void anInstallWithNothingSiftedSaysSoRatherThanPrintingAnEmptyTable() {
        when(this.pipeline.cullRuns()).thenReturn(listed());

        final CliHarness.Result result = this.run("runs");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("No sift runs.");
    }

    @Test
    void aRootNobodyCouldReadIsRefusedRatherThanReportedAsAnInstallWithNothingSifted() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(PREP_ROOT));

        final CliHarness.Result result = this.run("runs");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains(PREP_ROOT.toString());
    }

    @Test
    void aCallerAskingForADocumentIsToldWhichRootCouldNotBeRead() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(PREP_ROOT));

        assertThat(this.run("runs", "--json").out())
                .contains("\"kind\":\"RUNS_UNREADABLE\"")
                .contains("sift-prep");
    }

    @Test
    void eachRunIsListedWithHowFarThroughItsSheetsItIs() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.WAITING, List.of(), new ShardTally(12, 11, 25))));

        final CliHarness.Result result = this.run("runs");

        assertThat(result.out()).contains("2019-06").contains("WAITING").contains("11/25");
    }

    @Test
    void theScopeAPersonReadsIsTheOneOtherCommandsTake() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25))));

        assertThat(this.run("runs", "--json").out()).contains("\"scope\":\"2019-06\"");
    }

    @Test
    void theColumnsLineUpWhateverLengthTheScopesAre() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019", PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25)),
                summary("2019-06-07-08", PrepDirHealth.State.WAITING, List.of(), new ShardTally(1, 1, 9))));

        final List<String> lines = this.run("runs").out().lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(1).indexOf("READY")).isEqualTo(lines.get(2).indexOf("WAITING"));
        assertThat(lines.get(1).indexOf("25/25")).isEqualTo(lines.get(2).indexOf("1/9"));
    }

    @Test
    void whenARunWasLastWrittenToIsShownToTheSecond() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25))));

        assertThat(this.run("runs").out()).contains("2026-08-20T10:15:30Z").doesNotContain("069830400");
        assertThat(this.run("runs", "--json").out()).contains("2026-08-20T10:15:30.069830400Z");
    }

    @Test
    void noLineEndsInBlanks() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019", PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25)),
                summary("2019-06-07-08", PrepDirHealth.State.WAITING, List.of(), new ShardTally(1, 1, 9))));

        assertThat(this.run("runs").out().lines())
                .allSatisfy(line -> assertThat(line).isEqualTo(line.stripTrailing()));
    }

    @Test
    void aRunNobodyCouldCountTheSheetsOfShowsADashRatherThanAZero() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.DAMAGED,
                        List.of(new Finding.UnreadablePrepDir(PREP_ROOT)), null)));

        assertThat(this.run("runs").out().lines().skip(1))
                .allSatisfy(line -> assertThat(line.split(" +")).contains("DAMAGED", "-"));
    }

    @Test
    void aPersonSeesHowManyProblemsARunHasAndAMachineSeesWhatTheyAre() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.BLOCKED,
                        List.of(new Finding.CorruptSidecar("montage-002")), new ShardTally(25, 24, 25))));

        assertThat(this.run("runs").out()).doesNotContain("CorruptSidecar");
        assertThat(this.run("runs", "--json").out()).contains("\"type\":\"CorruptSidecar\"");
    }

    @Test
    void askedForADocumentTheOutputStreamCarriesOneAndTheTableIsNotPrinted() {
        when(this.pipeline.cullRuns()).thenReturn(listed(
                summary("2019-06", PrepDirHealth.State.READY, List.of(), new ShardTally(25, 25, 25))));

        final CliHarness.Result result = this.run("runs", "--json");

        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out()).doesNotContain("SCOPE").contains("\"command\":\"runs\"");
    }

    @Test
    void theFlagReadsTheSameBeforeTheVerbAsAfterIt() {
        when(this.pipeline.cullRuns()).thenReturn(listed());

        assertThat(this.run("--json", "runs").out()).contains("\"command\":\"runs\"");
        assertThat(this.run("runs", "--json").out()).contains("\"command\":\"runs\"");
    }

    @Test
    void listingRunsAsksTheFacadeForNothingButTheRuns() {
        when(this.pipeline.cullRuns()).thenReturn(listed());

        this.run("runs");

        verify(this.pipeline).cullRuns();
        verifyNoMoreInteractions(this.pipeline);
    }

    @Test
    void anInstallWithNoFoldersChosenYetIsRefusedRatherThanCrashing() {
        when(this.pipeline.cullRuns()).thenThrow(new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.WORKING_ROOT))));

        final CliHarness.Result result = this.run("runs");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("sluice.paths.repo-root");
    }

    @Test
    void aRefusedListingStillWritesItsDocumentForTheCallerThatAskedForOne() {
        when(this.pipeline.cullRuns()).thenThrow(new PathsMisconfiguredException(
                List.of(new NotConfigured(PathRole.WORKING_ROOT))));

        final CliHarness.Result result = this.run("runs", "--json");

        assertThat(result.out()).contains("\"status\":\"REFUSED\"").contains("\"kind\":\"FOLDERS_UNUSABLE\"");
        assertThat(result.err()).contains("sluice.paths.repo-root");
    }

    @Test
    void aFailureNothingClassifiedTakesItsOwnCodeAndHandsOverTheTrace() {
        when(this.pipeline.cullRuns()).thenThrow(new IllegalArgumentException("the disk went away"));

        final CliHarness.Result result = this.run("runs");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.FAILED.exitCode());
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("IllegalArgumentException").contains("the disk went away");
    }

    // A trace runs to dozens of lines. Written into the document unescaped it would end the line
    // the document is supposed to be, and a caller reading one run per line would see many.
    @Test
    void aFailuresTraceDoesNotBreakTheDocumentOntoASecondLine() {
        when(this.pipeline.cullRuns()).thenThrow(new IllegalArgumentException("the disk went away"));

        final CliHarness.Result result = this.run("runs", "--json");

        assertThat(result.out().lines()).hasSize(1);
        assertThat(result.out()).contains("\"status\":\"FAILED\"").contains("the disk went away");
    }

    @Test
    void argumentsTheParserRefusedProduceNoDocumentEvenWhenOneWasAskedFor() {
        final CliHarness.Result result = this.run("runs", "--json", "--nonsense");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
    }

    // The double is what makes this reachable at all. No real credential store fails on demand,
    // and the failure being simulated is one that happens while reading another failure.
    @Test
    void aFailureThatCannotEvenBeReadStillLeavesTheCallerADocument() {
        when(this.pipeline.cullRuns()).thenThrow(new IllegalStateException("the original failure"));
        final var command = new RunsCommand(this.pipeline, new CommandReports(new RefusalClassifier(new NoSecrets()) {
            @Override
            public Refusal refusalFor(final Throwable failure) {
                throw new IllegalStateException("the credential store stopped answering");
            }
        }));

        final CliHarness.Result result = CliHarness.run(
                SluiceCli.parser(new SluiceCli(), CliHarness.supplying(command)), "runs", "--json");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.FAILED.exitCode());
        assertThat(result.out()).contains("the original failure").contains("\"status\":\"FAILED\"");
    }

    private CliHarness.Result run(final String... args) {
        final var command = new RunsCommand(this.pipeline, new CommandReports(new RefusalClassifier(new NoSecrets())));
        return CliHarness.run(SluiceCli.parser(new SluiceCli(), CliHarness.supplying(command)), args);
    }

    private static CullRuns listed(final CullRunSummary... runs) {
        return new CullRuns.Listed(List.of(runs));
    }

    private static CullRunSummary summary(final String scope, final PrepDirHealth.State state,
                                          final List<Finding> findings, final @Nullable ShardTally shards) {
        return new CullRunSummary(scope, PREP_ROOT.resolve(scope), new PrepDirHealth(state, findings), shards,
                WRITTEN);
    }
}
