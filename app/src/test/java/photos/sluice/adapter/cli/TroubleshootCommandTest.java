package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;
import photos.sluice.domain.paths.PathViolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TroubleshootCommandTest {

    private final Pipeline pipeline = mock(Pipeline.class);
    private final RunAddress address = mock(RunAddress.class);
    private final JobRunner runner = new JobRunner();

    @Test
    void everythingRepairedReportsDoneAndNamesWhatWasFixed() {
        this.answering("2019", new TroubleshootReport(healthOf(State.BLOCKED, List.of(new Finding.CorruptIndex(
                        Path.of("index.json")))), true, null, List.of("decisions-999.json"),
                healthOf(State.READY, List.of()), "text"));

        final CliHarness.Result result = this.run("troubleshoot", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("This sift's records were unreadable and have been rebuilt.",
                "Stray sheets filed into place: 1", "Every sheet was judged. Run 'resume' to move the photos.");
    }

    // Pins the literal line, not Finding.describe(). That method's own Javadoc says it renders
    // the validator's aggregated-exception prose, never what this stream shows a person.
    @Test
    void whatIsStillOpenNamesTheKeyAndOptionsRatherThanTheValidatorsOwnProse() {
        final Finding corrupt = new Finding.CorruptIndex(Path.of("index.json"));
        this.answering("2019", new TroubleshootReport(healthOf(State.BLOCKED, List.of(corrupt)), false, null,
                List.of(), healthOf(State.BLOCKED, List.of(corrupt)), "text"));

        final CliHarness.Result result = this.run("troubleshoot", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.BLOCKED.exitCode());
        assertThat(result.out().lines()).containsExactly("Needs your call: 1",
                "Needs an answer: index.json. Run 'answer <run> index.json <option>', option one of DISCARD.");
        assertThat(result.out()).doesNotContain("montage").doesNotContain(corrupt.describe());
    }

    @Test
    void stillWaitingOnSheetsWithNothingElseOpenReportsWaiting() {
        this.answering("2019", new TroubleshootReport(healthOf(State.WAITING, List.of()), false, null, List.of(),
                healthOf(State.WAITING, List.of()), "text"));

        final CliHarness.Result result = this.run("troubleshoot", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.WAITING.exitCode());
        assertThat(result.out().lines()).containsExactly("Waiting for the rest of the sheets to come back.");
    }

    @Test
    void anAlreadyFinishedRunReportsSoRatherThanAnEmptyList() {
        this.answering("2019", new TroubleshootReport(healthOf(State.COMPLETE, List.of()), false, null, List.of(),
                healthOf(State.COMPLETE, List.of()), "text"));

        final CliHarness.Result result = this.run("troubleshoot", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("This sift already finished.");
    }

    @Test
    void aCallerAskingForADocumentReadsEachFindingsKeyAndOptions() {
        final Finding corrupt = new Finding.CorruptIndex(Path.of("index.json"));
        this.answering("2019", new TroubleshootReport(healthOf(State.BLOCKED, List.of(corrupt)), false, null,
                List.of(), healthOf(State.BLOCKED, List.of(corrupt)), "text"));

        final String out = this.run("troubleshoot", "2019", "--json").out();

        assertThat(out).contains("\"key\":\"index.json\"").contains("\"options\":[\"DISCARD\"]");
    }

    private void answering(final String tag, final TroubleshootReport report) {
        final Path prepDir = Path.of("D:", "Sift", tag);
        when(this.address.folderFor(eq(tag))).thenReturn(prepDir);
        when(this.pipeline.troubleshoot(eq(prepDir))).thenAnswer(_ -> this.runner.submit(_ -> report));
    }

    private static PrepDirHealth healthOf(final State state, final List<Finding> findings) {
        return new PrepDirHealth(state, findings);
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
        return CliHarness.run(CliHarness.parser(new TroubleshootCommand(this.pipeline, jobs, this.address)), args);
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
