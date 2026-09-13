package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import photos.sluice.application.service.JobRunner;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.sift.AnswerSource;
import photos.sluice.domain.sift.ChoiceAnswer;
import photos.sluice.domain.sift.CorruptSidecarResolution;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.DiscardReport;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.PrepDirHealth;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AnswerCommandTest {

    private static final Path PREP_DIR = Path.of("D:", "Sift", "2019");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final RunAddress address = mock(RunAddress.class);
    private final MutatingCommandStart start = mock(MutatingCommandStart.class);
    private final ConsoleProgressPort progress = mock(ConsoleProgressPort.class);
    private final JobRunner runner = new JobRunner();

    @Test
    void aMatchingKeyAndOptionRecordsTheAnswerAndClaimsTheWorkingRootFirst() {
        final Finding.StrayShard stray = new Finding.StrayShard("decisions-999.json");
        this.answering(stray);

        final CliHarness.Result result = this.run("answer", "2019", "decisions-999.json", "SET_ASIDE");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Answered. Run 'troubleshoot 2019' to see what is still "
                + "open.");
        verify(this.start).claimAndSweep();
        verify(this.pipeline).answer(PREP_DIR, new ChoiceAnswer.SetAsideStrayShard(stray), AnswerSource.CLI);
    }

    @Test
    void aKeyAndOptionAnsweringNothingOpenIsRefused() {
        this.answering(new Finding.StrayShard("decisions-999.json"));

        final CliHarness.Result result = this.run("answer", "2019", "decisions-999.json", "APPLY_ANYWAY");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("decisions-999.json", "APPLY_ANYWAY");
    }

    @Test
    void discardWithoutYesIsRefusedAndNamesWhatIsLost() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                new SiftRunSummary("2019", PREP_DIR, health(new Finding.CorruptIndex(Path.of("index.json"))), null,
                        Instant.EPOCH))));

        final CliHarness.Result result = this.run("answer", "2019", "index.json", "DISCARD");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains("--yes");
    }

    @Test
    void discardWithYesDiscardsTheRunThroughTheSameFacadeCall() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                new SiftRunSummary("2019", PREP_DIR, health(new Finding.CorruptIndex(Path.of("index.json"))), null,
                        Instant.EPOCH))));
        final var graveyard = Path.of("D:", "Sift", "archives", "2019-graveyard");
        when(this.pipeline.discard(eq(PREP_DIR))).thenAnswer(_ -> this.runner.submit(_ -> new DiscardReport(
                graveyard, 0)));

        final CliHarness.Result result = this.run("answer", "2019", "index.json", "DISCARD", "--yes");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("Discarded. Its records are archived in " + graveyard
                + " for 30 days.");
    }

    @Test
    void discardWithYesAndQuietSuppressesProgress() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                new SiftRunSummary("2019", PREP_DIR, health(new Finding.CorruptIndex(Path.of("index.json"))), null,
                        Instant.EPOCH))));
        when(this.pipeline.discard(eq(PREP_DIR))).thenAnswer(_ -> this.runner.submit(_ -> new DiscardReport(
                Path.of("D:", "Sift", "archives", "2019-graveyard"), 0)));

        this.run("answer", "2019", "index.json", "DISCARD", "--yes", "--quiet");

        verify(this.progress).quiet(true);
    }

    // A blocked run ordinarily carries several open findings; resolve() has to find the right one
    // among them rather than only ever seeing a list of one.
    @Test
    void aKeyAndOptionMatchOneFindingAmongSeveralOpen() {
        final Finding.CorruptSidecar sidecar = new Finding.CorruptSidecar("montage-002");
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019", PREP_DIR,
                new PrepDirHealth(PrepDirHealth.State.BLOCKED,
                        List.of(new Finding.StrayShard("decisions-999.json"), sidecar)),
                null, Instant.EPOCH))));

        final CliHarness.Result result = this.run("answer", "2019", "montage-002", "APPLY_ANYWAY");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        verify(this.pipeline).answer(PREP_DIR,
                new ChoiceAnswer.ResolveCorruptSidecar("montage-002", CorruptSidecarResolution.APPLY_ANYWAY),
                AnswerSource.CLI);
    }

    // A restored photo closes its own finding, so nothing on the run answers to the key any more.
    // Resolving against open findings alone therefore reports the good news as a failure.
    @Test
    void lookingAgainAtAPhotoStillMissingSaysSo() {
        final Path gone = Path.of("D:", "Sorted", "2019", "gone.jpg");
        this.answering(new Finding.MissingSource(gone, Path.of("move-record.log")));

        final CliHarness.Result result = this.run("answer", "2019", gone.toString(), "RECHECK");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("It is still missing.");
        verify(this.pipeline, never()).answer(any(), any(), any());
    }

    @Test
    void lookingAgainAtARestoredPhotoSaysTheProblemIsGone(@TempDir final Path sorted) throws IOException {
        final Path restored = sorted.resolve("back.jpg");
        Files.writeString(restored, "the photo, put back");
        this.answering(new Finding.MissingSource(sorted.resolve("someone-else.jpg"),
                Path.of("move-record.log")));

        final CliHarness.Result result = this.run("answer", "2019", restored.toString(), "RECHECK");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out().lines()).containsExactly("That is no longer a problem.");
        verify(this.pipeline, never()).answer(any(), any(), any());
    }

    private void answering(final Finding finding) {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                new SiftRunSummary("2019", PREP_DIR, health(finding), null, Instant.EPOCH))));
    }

    private static PrepDirHealth health(final Finding finding) {
        return new PrepDirHealth(PrepDirHealth.State.BLOCKED, List.of(finding));
    }

    private CliHarness.Result run(final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        final var confirmation = new DiscardConfirmation(this.pipeline);
        return CliHarness.run(CliHarness.parser(
                new AnswerCommand(this.pipeline, reports, this.address, this.start, confirmation, this.progress)),
                args);
    }
}
