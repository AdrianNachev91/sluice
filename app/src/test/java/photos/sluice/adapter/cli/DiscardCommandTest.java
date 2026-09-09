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
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.paths.PathViolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DiscardCommandTest {

    private static final Path PREP_DIR = Path.of("D:", "Sift", "2019");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final RunAddress address = mock(RunAddress.class);
    private final JobRunner runner = new JobRunner();

    @Test
    void withoutYesItRefusesAndNamesWhatIsLost() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(runWith(3))));
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        when(this.pipeline.archivesFolder()).thenReturn(Path.of("D:", "Sift", "archives"));

        final CliHarness.Result result = this.run("discard", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("3 sheet decisions you have already paid for").contains("--yes");
    }

    @Test
    void withYesItDiscardsAndReportsWhatWasArchived() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        final var graveyard = Path.of("D:", "Sift", "archives", "2019-graveyard");
        when(this.pipeline.discard(eq(PREP_DIR)))
                .thenAnswer(_ -> this.runner.submit(_ -> new DiscardReport(graveyard, 3)));

        final CliHarness.Result result = this.run("discard", "2019", "--yes");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Discarded. 3 sheet decisions are set aside with it. Its "
                + "records are archived in " + graveyard + " for 30 days.");
    }

    @Test
    void theArchivedFolderIsWrittenAsAPathRatherThanAUri() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        final var graveyard = Path.of("D:", "Sift", "archives", "2019-graveyard");
        when(this.pipeline.discard(eq(PREP_DIR)))
                .thenAnswer(_ -> this.runner.submit(_ -> new DiscardReport(graveyard, 3)));

        final CliHarness.Result result = this.run("discard", "2019", "--yes", "--json");

        assertThat(result.out()).doesNotContain("file:/").contains("2019-graveyard")
                .contains("\"shardsSetAside\":3");
    }

    @Test
    void aRunWithNoShardsYetIsNotAskedToConfirmMoney() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(runWith(0))));

        final CliHarness.Result result = this.run("discard", "2019");

        assertThat(result.err()).doesNotContain("sheet decision").contains("--yes");
    }

    // The run address resolved to a folder cullRuns() does not enumerate: an absolute path that
    // does not match any listed prep dir. The cost here is genuinely unknown, not zero.
    @Test
    void aRunNotAmongThoseListedSaysTheCostCouldNotBeConfirmed() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));

        final CliHarness.Result result = this.run("discard", "2019");

        assertThat(result.err()).contains("The number of paid sheet decisions is unknown").contains("--yes");
    }

    private static CullRunSummary runWith(final int validShards) {
        return new CullRunSummary("2019", PREP_DIR, new PrepDirHealth(PrepDirHealth.State.BLOCKED, List.of()),
                new ShardTally(validShards, validShards, validShards), Instant.EPOCH);
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
        final var confirmation = new DiscardConfirmation(this.pipeline);
        return CliHarness.run(CliHarness.parser(new DiscardCommand(this.pipeline, jobs, this.address, confirmation)),
                args);
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
