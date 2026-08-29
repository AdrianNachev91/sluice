package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.config.SettingsFixture;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ImportCommandTest {

    private final Pipeline pipeline = mock(Pipeline.class);
    private final JobRunner runner = new JobRunner();
    private final WorkingRootLock lock = mock(WorkingRootLock.class);

    private InputStream typed = new ByteArrayInputStream(new byte[0]);

    @Test
    void copyIsTheDefaultKind(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.answering(List.of(source), ImportKind.COPY, nothingFound());

        this.run(root, "import", source.toString());

        verify(this.pipeline).importFrom(List.of(source), ImportKind.COPY);
    }

    @Test
    void moveOptsIntoMoving(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.answering(List.of(source), ImportKind.MOVE, nothingFound());

        this.run(root, "import", source.toString(), "--move");

        verify(this.pipeline).importFrom(List.of(source), ImportKind.MOVE);
    }

    @Test
    void severalSourcesAreAllPassedThrough(@TempDir final Path root) {
        final Path first = root.resolve("a");
        final Path second = root.resolve("b");
        this.answering(List.of(first, second), ImportKind.COPY, nothingFound());

        this.run(root, "import", first.toString(), second.toString());

        verify(this.pipeline).importFrom(List.of(first, second), ImportKind.COPY);
    }

    @Test
    void aMissingSourceIsAUsageErrorRatherThanARefusal(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "import");

        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void nothingFoundIsReportedRatherThanAZeroCount(@TempDir final Path root) {
        final Path source = root.resolve("Empty");
        this.answering(List.of(source), ImportKind.COPY, nothingFound());

        assertThat(this.run(root, "import", source.toString()).out().lines())
                .containsExactly("Nothing to import there.");
    }

    @Test
    void anImportNamesWhatItBroughtInAndWhatItCouldNot(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.answering(List.of(source), ImportKind.COPY, new ImportSummary(5, 3, 1, 0, 1, 0, false));

        final CliHarness.Result result = this.run(root, "import", source.toString());

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Found: 5", "Imported: 3",
                "Skipped: already in your Inbox: 1", "Could not be read: 1");
    }

    @Test
    void unreadableFoldersAreNamedEvenWhenNothingWasFound(@TempDir final Path root) {
        final Path source = root.resolve("Locked");
        this.answering(List.of(source), ImportKind.COPY, new ImportSummary(0, 0, 0, 0, 0, 1, false));

        assertThat(this.run(root, "import", source.toString()).out())
                .contains("Folders could not be opened: 1");
    }

    @Test
    void aRefusedSourceIsReportedRatherThanAStackTrace(@TempDir final Path root) {
        final Path source = root.resolve("GoneNow");
        when(this.pipeline.importFrom(eq(List.of(source)), eq(ImportKind.COPY)))
                .thenThrow(new ImportSourceException("Sluice can't import from " + source + ": it no longer exists."));

        final CliHarness.Result result = this.run(root, "import", source.toString());

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("it no longer exists");
    }

    @Test
    void aStoppedImportSaysSoAboveItsCounts(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.importFrom(eq(List.of(source)), eq(ImportKind.COPY)))
                .thenAnswer(_ -> this.runner.submit(handle -> {
                    while (!handle.isCancellationRequested()) {
                        //noinspection BusyWait
                        Thread.sleep(1);
                    }
                    return new ImportSummary(5, 2, 0, 0, 0, 0, true);
                }));

        final CliHarness.Result result = this.run(root, "import", source.toString());

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out().lines()).containsExactly("Stopped. Run the import again to pick up the rest.",
                "Found: 5", "Imported: 2");
    }

    @Test
    void aCallerAskingForADocumentGetsTheCountsAsFields(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.answering(List.of(source), ImportKind.COPY, new ImportSummary(3, 3, 0, 0, 0, 0, false));

        assertThat(this.run(root, "import", source.toString(), "--json").out())
                .contains("\"command\":\"import\"")
                .contains("\"broughtIn\":3");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheImportStarts(@TempDir final Path root) {
        final Path source = root.resolve("SDCard");
        this.answering(List.of(source), ImportKind.COPY, nothingFound());

        this.run(root, "import", source.toString());

        verify(this.lock).acquire(root);
    }

    private void answering(final List<Path> sources, final ImportKind kind, final ImportSummary summary) {
        when(this.pipeline.importFrom(eq(sources), eq(kind))).thenAnswer(_ -> this.runner.submit(_ -> summary));
    }

    private static ImportSummary nothingFound() {
        return new ImportSummary(0, 0, 0, 0, 0, 0, false);
    }

    private CliHarness.Result run(final Path root, final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var start = new MutatingCommandStart(this.lock, SettingsFixture.workingRoot(root), this.pipeline,
                new UsableRoots());
        final var progress = new ConsoleProgressPort(new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), false);
        final var jobs = new JobReports(reports, start, new TypedCancel(this.typed, progress), progress);
        return CliHarness.run(CliHarness.parser(new ImportCommand(this.pipeline, jobs)), args);
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
