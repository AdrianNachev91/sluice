package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentWriterTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void theAnswerAPersonReadsGoesToTheOutputStream() {
        this.writer(false).write("runs", CommandOutcome.done(List.of(), List.of("2019  READY", "2020  WAITING")));

        assertThat(this.out.toString().lines()).containsExactly("2019  READY", "2020  WAITING");
        assertThat(this.err.toString()).isEmpty();
    }

    @Test
    void askedForADocumentTheOutputStreamCarriesThatInsteadOfTheAnswer() {
        this.writer(true).write("runs", CommandOutcome.done(List.of("first"), List.of("2019  READY")));

        assertThat(this.out.toString()).doesNotContain("2019  READY").contains("\"command\":\"runs\"");
    }

    @Test
    void theDocumentIsOneLine() {
        this.writer(true).write("runs", CommandOutcome.done(List.of("first", "second"), List.of()));

        assertThat(this.out.toString().lines()).hasSize(1);
    }

    @Test
    void theDocumentSaysWhichVerbRanAndHowItEnded() {
        this.writer(true).write("runs", CommandOutcome.done(List.of(), List.of()));

        assertThat(this.out.toString()).contains("\"command\":\"runs\"").contains("\"status\":\"DONE\"");
    }

    @Test
    void aDocumentWithNoVerbBehindItLeavesTheFieldOutRatherThanNamingOne() {
        this.writer(true).write(null, CommandOutcome.done(null, List.of()));

        assertThat(this.out.toString()).doesNotContain("command").doesNotContain("result");
    }

    @Test
    void aRefusalReachesThePersonWhetherOrNotADocumentWasAskedFor() {
        this.writer(true).write("runs", CommandOutcome.refused(
                Refusal.of(RefusalKind.JOB_IN_PROGRESS, "Something else is running.")));

        assertThat(this.err.toString().lines()).containsExactly("Something else is running.");
        assertThat(this.out.toString()).contains("\"status\":\"REFUSED\"");
    }

    @Test
    void aRefusedCommandWritesNothingOnTheOutputStreamWhenNoDocumentWasAskedFor() {
        this.writer(false).write("runs", CommandOutcome.refused(
                Refusal.of(RefusalKind.JOB_IN_PROGRESS, "Something else is running.")));

        assertThat(this.out.toString()).isEmpty();
        assertThat(this.err.toString().lines()).containsExactly("Something else is running.");
    }

    @Test
    void theExitCodeIsWhicheverOneTheStatusCarries() {
        assertThat(this.writer(false).write("runs", CommandOutcome.done(List.of(), List.of())))
                .isEqualTo(CommandStatus.DONE.exitCode());
        assertThat(this.writer(false).write("runs", CommandOutcome.refused(
                Refusal.of(RefusalKind.JOB_IN_PROGRESS, "Something else is running."))))
                .isEqualTo(CommandStatus.REFUSED.exitCode());
    }

    @Test
    void aRefusalsOwnFieldsAreOnTheWireAndItsSentenceIsNot() {
        this.writer(true).write("runs", CommandOutcome.refused(new Refusal(RefusalKind.WORKING_ROOT_BUSY,
                "Another Sluice process is already running.", Fields.of("workingRoot", "D:\\Photos"))));

        assertThat(this.out.toString())
                .contains("\"kind\":\"WORKING_ROOT_BUSY\"")
                .contains("\"workingRoot\":\"D:\\\\Photos\"")
                .doesNotContain("already running");
    }

    private DocumentWriter writer(final boolean asDocument) {
        return new DocumentWriter(new PrintWriter(this.out), new PrintWriter(this.err), asDocument,
                JsonMapper.builder().build());
    }
}
