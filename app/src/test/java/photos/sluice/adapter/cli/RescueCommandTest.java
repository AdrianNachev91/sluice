package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.RescueRoot;
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
        this.answering(RescueRoot.REVIEW, "Food", nothingRescued());

        this.run(root, "rescue", "Food");

        verify(this.pipeline).rescue(RescueRoot.REVIEW, "Food");
    }

    @Test
    void withNoRootNamedTheFolderIsLookedForUnderReview(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "2019-06", nothingRescued());

        this.run(root, "rescue", "2019-06");

        verify(this.pipeline).rescue(RescueRoot.REVIEW, "2019-06");
    }

    @Test
    void namingTheUnreviewableRootSendsTheRescueThere(@TempDir final Path root) {
        this.answering(RescueRoot.UNREVIEWABLE, "2019/06", nothingRescued());

        this.run(root, "rescue", "2019/06", "--from", "unreviewable");

        verify(this.pipeline).rescue(RescueRoot.UNREVIEWABLE, "2019/06");
    }

    @Test
    void namingTheDuplicatesRootSendsTheRescueThere(@TempDir final Path root) {
        this.answering(RescueRoot.DUPLICATES, "2019-06_beach", nothingRescued());

        this.run(root, "rescue", "2019-06_beach", "--from", "duplicates");

        verify(this.pipeline).rescue(RescueRoot.DUPLICATES, "2019-06_beach");
    }

    @Test
    void aRootNameNothingHoldsIsRefusedAndNamesTheThreeThatExist(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "rescue", "Food", "--from", "sorted");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("Not a root: sorted").contains("review")
                .contains("unreviewable").contains("duplicates");
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void aMissingFolderIsAUsageErrorRatherThanARefusal(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "rescue");

        assertThat(result.exitCode()).isEqualTo(2);
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void theRootItselfIsNotAFolderInsideIt(@TempDir final Path root) {
        for (final String naming : new String[] {"", ".", "./", "2019-06/.."}) {
            final CliHarness.Result result = this.run(root, "rescue", naming);

            assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
            assertThat(result.err()).contains("Not a folder to rescue");
        }
        verifyNoInteractions(this.pipeline);
    }

    @Test
    void nothingReadyIsReportedRatherThanAZeroCount(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "Food", nothingRescued());

        assertThat(this.run(root, "rescue", "Food").out().lines())
                .containsExactly("Nothing in this folder was ready to move to Sorted.");
    }

    @Test
    void aRescueNamesBothOfTheDestinationsItMovedTo(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "2019-06", new RescueSummary(4, 1, 0, 0, false, false));

        final CliHarness.Result result = this.run(root, "rescue", "2019-06");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Moved to Sorted: 4", "Moved to Unsorted: 1",
                "No year names those, so write commit unsorted to move them to your Library.",
                "The folder is still there.");
    }

    @Test
    void aRescueThatOnlyDeletedCopiesSortedHeldSaysSoRatherThanThatNothingWasReady(@TempDir final Path root) {
        this.answering(RescueRoot.DUPLICATES, "2019-06_beach", new RescueSummary(0, 0, 3, 0, true, false));

        final CliHarness.Result result = this.run(root, "rescue", "2019-06_beach", "--from", "duplicates");

        assertThat(result.out().lines()).containsExactly("Deleted: already in Sorted: 3",
                "The folder was removed.");
    }

    @Test
    void aRescueThatDatedEverythingPrintsOneCountAndTheFoldersFate(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "2019-06", new RescueSummary(4, 0, 0, 0, true, false));

        assertThat(this.run(root, "rescue", "2019-06").out().lines()).containsExactly(
                "Moved to Sorted: 4", "The folder was removed.");
    }

    @Test
    void anEmptiedFolderSaysItWasRemoved(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "2019-06", new RescueSummary(4, 0, 0, 0, true, false));

        assertThat(this.run(root, "rescue", "2019-06").out()).contains("The folder was removed.");
    }

    @Test
    void aFolderThatWasNeverThereIsRefusedRatherThanCrashing(@TempDir final Path root) {
        final Path missing = root.resolve("Review").resolve("DoesNotExist");
        when(this.pipeline.rescue(eq(RescueRoot.REVIEW), eq("DoesNotExist"))).thenAnswer(_ -> this.runner.submit(_ -> {
            throw new UncheckedIOException(new NoSuchFileException(missing.toString()));
        }));

        final CliHarness.Result result = this.run(root, "rescue", "DoesNotExist");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains(missing.toString());
    }

    @Test
    void aStoppedRescueCountsWhatIsStillInTheFolderAboveItsCounts(@TempDir final Path root) {
        this.stopping(new RescueSummary(1, 0, 0, 1200, false, true));

        final CliHarness.Result result = this.run(root, "rescue", "2019-06");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.CANCELLED.exitCode());
        assertThat(result.out().lines()).containsExactly(
                "Stopped. 1,200 photos and videos are still in the folder you started from.",
                "Moved to Sorted: 1", "The folder is still there.");
    }

    @Test
    void aStoppedRescueThatLeftOnePhotoSaysSoInTheSingular(@TempDir final Path root) {
        this.stopping(new RescueSummary(1, 0, 0, 1, false, true));

        assertThat(this.run(root, "rescue", "2019-06").out())
                .contains("Stopped. One photo or video is still in the folder you started from.");
    }

    // Reachable: a stop taken after the last photo moved, with only notes left in the tail.
    @Test
    void aStoppedRescueThatLeftNoMediaSaysThatRatherThanCountingZero(@TempDir final Path root) {
        this.stopping(new RescueSummary(1, 0, 0, 0, false, true));

        assertThat(this.run(root, "rescue", "2019-06").out())
                .contains("Stopped. No photos or videos are left in the folder you started from.");
    }

    @Test
    void aCallerAskingForADocumentGetsEveryCountAsAField(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "Food", new RescueSummary(2, 1, 3, 0, false, false));

        assertThat(this.run(root, "rescue", "Food", "--json").out())
                .contains("\"command\":\"rescue\"")
                .contains("\"rescued\":2")
                .contains("\"undated\":1")
                .contains("\"alreadyInSorted\":3")
                .contains("\"leftBehind\":0");
    }

    @Test
    void theWorkingRootIsClaimedBeforeTheRescueStarts(@TempDir final Path root) {
        this.answering(RescueRoot.REVIEW, "Food", nothingRescued());

        this.run(root, "rescue", "Food");

        verify(this.lock).acquire(root);
    }

    @Test
    void aFolderNameThatClimbsOutOfItsRootIsRefusedRatherThanReachingTheEngine(@TempDir final Path root) {
        final CliHarness.Result result = this.run(root, "rescue", "../..");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).doesNotContain("IllegalArgumentException");
        verifyNoInteractions(this.pipeline);
    }

    private void answering(final RescueRoot root, final String folder, final RescueSummary summary) {
        when(this.pipeline.rescue(eq(root), eq(folder))).thenAnswer(_ -> this.runner.submit(_ -> summary));
    }

    private void stopping(final RescueSummary summary) {
        this.typed = new ByteArrayInputStream("c\n".getBytes(StandardCharsets.UTF_8));
        when(this.pipeline.rescue(eq(RescueRoot.REVIEW), eq("2019-06"))).thenAnswer(_ ->
                this.runner.submit(handle -> {
                    while (!handle.isCancellationRequested()) {
                        //noinspection BusyWait
                        Thread.sleep(1);
                    }
                    return summary;
                }));
    }

    private static RescueSummary nothingRescued() {
        return new RescueSummary(0, 0, 0, 0, false, false);
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
