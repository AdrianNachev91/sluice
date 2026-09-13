package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.SiftReport;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.PrepDirHealth;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ResumeCommandTest {

    private static final Instant SINCE = Instant.parse("2026-08-20T10:15:30Z");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private final InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void aFullPathIsResumedWithoutAskingWhatSiftsThereAre(@TempDir final Path root) {
        final Path prepDir = root.resolve("2019");
        this.answering(prepDir, false, applied());

        this.run(root, "resume", prepDir.toString());

        verify(this.pipeline).resume(prepDir, false);
    }

    @Test
    void allowPartialMapsToTheBoolean(@TempDir final Path root) {
        final Path prepDir = root.resolve("2019");
        this.answering(prepDir, true, applied());

        this.run(root, "resume", prepDir.toString(), "--allow-partial");

        verify(this.pipeline).resume(prepDir, true);
    }

    @Test
    void aTagNamingNoSiftIsRefusedRatherThanStartingAJob(@TempDir final Path root) {
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of()));

        final CliHarness.Result result = this.run(root, "resume", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        verify(this.pipeline, never()).resume(any(), anyBoolean());
    }

    @Test
    void aScopeTagResolvesToTheRunCarryingIt(@TempDir final Path root) {
        final Path prepDir = root.resolve("2019");
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                new SiftRunSummary("2019", prepDir, new PrepDirHealth(PrepDirHealth.State.WAITING, List.of()),
                        new ShardTally(1, 0, 2), SINCE))));
        this.answering(prepDir, false, applied());

        this.run(root, "resume", "2019");

        verify(this.pipeline).resume(prepDir, false);
    }

    @Test
    void aCompletedResumeReportsWhatApplyMoved(@TempDir final Path root) {
        final Path prepDir = root.resolve("2019");
        this.answering(prepDir, false, applied());

        final CliHarness.Result result = this.run(root, "resume", prepDir.toString());

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out()).contains("Photos looked at: 1");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheResumeStarts(@TempDir final Path root) {
        final Path prepDir = root.resolve("2019");
        this.answering(prepDir, false, applied());

        this.run(root, "resume", prepDir.toString());

        verify(this.lock).acquire(root);
    }

    private void answering(final Path prepDir, final boolean allowPartial, final SiftJobOutcome outcome) {
        when(this.pipeline.resume(eq(prepDir), eq(allowPartial)))
                .thenAnswer(_ -> this.runner.submit(_ -> outcome));
    }

    private static SiftJobOutcome.Applied applied() {
        return new SiftJobOutcome.Applied(SiftReport.nothingSpent("external-agent", 0),
                new ApplyReport(1, Map.of(), 0, 0, 0, List.of()), null, null);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final var progress = new ConsoleProgressPort(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), false);
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, progress), progress);
        return CliHarness.run(CliHarness.parser(new ResumeCommand(this.pipeline, jobs,
                new RunAddress(this.pipeline), mock(PathsPort.class))), args);
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
