package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.rescue.RescueSummary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RescueCommandTest {

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void theFolderTypedIsThePipelinesOwn(@TempDir final Path root) {
        this.answering("Food", nothingRescued());

        this.run(root, "rescue", "Food");

        verify(this.pipeline).rescue("Food");
    }

    @Test
    void aMissingFolderIsAUsageErrorRatherThanARefusal(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "rescue");

        assertThat(result.exitCode()).isEqualTo(2);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void nothingReadyIsReportedRatherThanAZeroCount(@TempDir final Path root) {
        this.answering("Food", nothingRescued());

        assertThat(this.run(root, "rescue", "Food").out().lines())
                .containsExactly("Nothing in this folder was ready to rescue.");
    }

    @Test
    void aRescueNamesWhatWasRescuedAndWhatWasLeft(@TempDir final Path root) {
        this.answering("2019-06", new RescueSummary(4, List.of("bad.jpg: no plausible date"), false, false));

        final CliHarness.Result result = this.run(root, "rescue", "2019-06");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Moved to your library: 4", "Left behind: 1",
                "The folder is still there.");
    }

    @Test
    void anEmptiedFolderSaysItWasRemoved(@TempDir final Path root) {
        this.answering("2019-06", new RescueSummary(4, List.of(), true, false));

        assertThat(this.run(root, "rescue", "2019-06").out()).contains("The folder was removed.");
    }

    @Test
    void aFolderThatWasNeverThereIsRefusedRatherThanCrashing(@TempDir final Path root) {
        final Path missing = root.resolve("Review").resolve("DoesNotExist");
        when(this.pipeline.rescue(eq("DoesNotExist"))).thenAnswer(_ -> this.runner.submit(_ -> {
            throw new UncheckedIOException(new NoSuchFileException(missing.toString()));
        }));

        final CliHarness.Result result = this.run(root, "rescue", "DoesNotExist");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains(missing.toString());
    }

    @Test
    void aStoppedRescueSaysSoAboveItsCounts(@TempDir final Path root) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.rescue(eq("2019-06"))).thenAnswer(_ -> this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return new RescueSummary(1, List.of(), false, true);
        }));

        final CliHarness.Result result = this.run(root, "rescue", "2019-06");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out().lines()).containsExactly("Stopped before this folder was finished.",
                "Moved to your library: 1", "The folder is still there.");
    }

    @Test
    void aCallerAskingForADocumentGetsTheSkippedNamesAsFields(@TempDir final Path root) {
        this.answering("Food", new RescueSummary(2, List.of("bad.jpg: no plausible date"), false, false));

        assertThat(this.run(root, "rescue", "Food", "--json").out())
                .contains("\"command\":\"rescue\"")
                .contains("\"rescued\":2")
                .contains("bad.jpg: no plausible date");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheRescueStarts(@TempDir final Path root) {
        this.answering("Food", nothingRescued());

        this.run(root, "rescue", "Food");

        verify(this.lock).acquire(root);
    }

    private void answering(final String folder, final RescueSummary summary) {
        when(this.pipeline.rescue(eq(folder))).thenAnswer(_ -> this.runner.submit(_ -> summary));
    }

    private static RescueSummary nothingRescued() {
        return new RescueSummary(0, List.of(), false, false);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final var progress = new ConsoleProgressPort(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), false);
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, progress), progress);
        return CliHarness.run(CliHarness.parser(new RescueCommand(this.pipeline, jobs)), args);
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
