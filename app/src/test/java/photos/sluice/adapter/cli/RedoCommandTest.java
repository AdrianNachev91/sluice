package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import photos.sluice.application.service.Pipeline;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RedoCommandTest {

    private static final Path PREP_DIR = Path.of("D:", "Sift", "2019");

    private final Pipeline pipeline = mock(Pipeline.class);
    private final RunAddress address = mock(RunAddress.class);
    private final MutatingCommandStart start = mock(MutatingCommandStart.class);

    @Test
    void theInstructionsGoOnTheOutputStreamAndTheCostNoteOnTheError() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.redoRejectedAnswers(eq(PREP_DIR))).thenReturn("Judge sheet montage-001 again.");

        final CliHarness.Result result = this.run("redo", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(result.out()).isEqualTo("Judge sheet montage-001 again." + System.lineSeparator());
        assertThat(result.err()).contains("spends from your provider account balance");
        verify(this.start).claimAndSweep();
    }

    @Test
    void aCallerAskingForADocumentGetsThePromptAsAField() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.redoRejectedAnswers(eq(PREP_DIR))).thenReturn("Judge sheet montage-001 again.");

        final CliHarness.Result result = this.run("redo", "2019", "--json");

        assertThat(result.out()).contains("\"prompt\":\"Judge sheet montage-001 again.\"");
    }

    @Test
    void nothingToRedoIsRefusedRatherThanFailing() {
        when(this.address.folderFor(eq("2019"))).thenReturn(PREP_DIR);
        when(this.pipeline.redoRejectedAnswers(eq(PREP_DIR)))
                .thenThrow(new Pipeline.NothingToRedoException(PREP_DIR));

        final CliHarness.Result result = this.run("redo", "2019");

        assertThat(result.exitCode()).isEqualTo(CommandStatus.REFUSED.exitCode());
        assertThat(result.err()).contains(PREP_DIR.toString());
    }

    private CliHarness.Result run(final String... args) {
        final var reports = new CommandReports(new RefusalClassifier(new NoSecrets()));
        return CliHarness.run(
                CliHarness.parser(new RedoCommand(this.pipeline, reports, this.address, this.start)), args);
    }
}
