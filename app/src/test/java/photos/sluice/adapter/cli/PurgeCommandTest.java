package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PurgeCommandTest {

    private static final Path PREP_DIR = Path.of("D:", "Sift", "2019");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();

    @Test
    void withAFinishedRunAndNoYesItRefusesAndNamesTheScope() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(complete("2019"), blocked("2020"))));

        final CliHarness.Result result = this.run("purge");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("2019").doesNotContain("2020").contains("--yes");
    }

    @Test
    void withNothingFinishedItRunsStraightThroughWithNoYes() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(blocked("2020"))));
        when(this.pipeline.purgeCompleted())
                .thenAnswer(_ -> this.runner.submit(_ -> new PurgeReport(List.of(), Map.of(), Map.of(), null)));

        final CliHarness.Result result = this.run("purge");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("No finished runs to clear.");
    }

    @Test
    void withYesItPurgesAndReportsWhatWasCleared() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(complete("2019"))));
        when(this.pipeline.purgeCompleted()).thenAnswer(_ -> this.runner.submit(_ ->
                new PurgeReport(List.of("2019"), Map.of("2020", PrepDirHealth.State.BLOCKED), Map.of(), null)));

        final CliHarness.Result result = this.run("purge", "--yes");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Cleared 1 finished run.",
                "1 run has not finished, so nothing from it was touched.");
    }

    // Matches every other read of cullRuns() in this adapter (RunAddress, AnswerCommand). A root
    // that cannot be read is a refusal, not a count of zero, and purgeCompleted() must never even
    // be asked, since what it would delete is unknown.
    @Test
    void anUnreadableRootIsRefusedRatherThanReportingAnEmptyCount() {
        final var root = Path.of("D:", "Sift", "logs", "sift-prep");
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(root));

        final CliHarness.Result result = this.run("purge");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains(root.toString());
        verify(this.pipeline, never()).purgeCompleted();
    }

    // The rarer race: readable when confirmed() checked, unreadable by the time the job's own
    // sweep runs. PurgeReport models this as a value rather than a throw inside the job. The
    // outcome here stays DONE with an honest line, not a second refusal.
    @Test
    void aRootThatBecomesUnreadableDuringTheJobReportsItRatherThanFailing() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));
        final var root = Path.of("D:", "Sift", "logs", "sift-prep");
        when(this.pipeline.purgeCompleted())
                .thenAnswer(_ -> this.runner.submit(_ -> new PurgeReport(List.of(), Map.of(), Map.of(), root)));

        final CliHarness.Result result = this.run("purge");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Nothing was cleared, because " + root + " cannot be "
                + "read. Most likely the folder is held by another process or not there anymore.");
    }

    private static CullRunSummary complete(final String scope) {
        return new CullRunSummary(scope, PREP_DIR.resolveSibling(scope),
                new PrepDirHealth(PrepDirHealth.State.COMPLETE, List.of()), null, Instant.EPOCH);
    }

    private static CullRunSummary blocked(final String scope) {
        return new CullRunSummary(scope, PREP_DIR.resolveSibling(scope),
                new PrepDirHealth(PrepDirHealth.State.BLOCKED, List.of()), null, Instant.EPOCH);
    }

    private CliHarness.Result run(final String... args) {
        final Path root = Path.of("D:", "Sift");
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var lock = mock(WorkingRootLock.class);
        final var start = new MutatingCommandStart(lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final InputStream typed = new ByteArrayInputStream(new byte[0]);
        final var progress = new ConsoleProgressPort(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), false);
        final var jobs = new JobReports(reports, start, new TypedCancel(typed, progress), progress);
        return CliHarness.run(CliHarness.parser(new PurgeCommand(this.pipeline, jobs)), args);
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
