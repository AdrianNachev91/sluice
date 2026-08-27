package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;

/**
 * Puts a finished outcome on the two streams, and answers with the code the process leaves with.
 *
 * <p>The split is the contract, and it is the same one every command inherits. The output stream
 * carries the answer and nothing else, so a run piped into another program arrives as that answer.
 * The error stream carries everything that is not the answer, whichever shape was asked for, so
 * somebody watching a piped run still reads why it stopped.
 *
 * <p>Asked for a document, the output stream carries exactly one and it is written on a single
 * line. Line-oriented tools then read a run as one record without being told where it ends.
 *
 * <p>Both streams are flushed here rather than left to the caller. A writer left unflushed reads as
 * a command that printed nothing.
 */
public final class DocumentWriter {

    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean asDocument;
    private final JsonMapper mapper;

    /**
     * Creates the writer over one invocation's streams.
     *
     * @param out {@link PrintWriter} the output stream, which carries the answer
     * @param err {@link PrintWriter} the error stream, which carries everything else
     * @param asDocument boolean whether a machine-readable document was asked for
     * @param mapper {@link JsonMapper} writes that document
     */
    public DocumentWriter(final PrintWriter out, final PrintWriter err, final boolean asDocument,
                          final JsonMapper mapper) {
        this.out = out;
        this.err = err;
        this.asDocument = asDocument;
        this.mapper = mapper;
    }

    /**
     * Writes one command's outcome and answers with its exit code.
     *
     * @param command {@link String} the verb that ran, or null when none was reached
     * @param outcome {@link CommandOutcome} everything it produced
     * @return int the code the process leaves with
     */
    public int write(final @Nullable String command, final CommandOutcome outcome) {
        if (this.asDocument) {
            this.out.println(this.mapper.writeValueAsString(
                    new ResultDocument(command, outcome.status(), outcome.payload())));
        } else {
            outcome.resultLines().forEach(this.out::println);
        }
        outcome.noteLines().forEach(this.err::println);
        this.out.flush();
        this.err.flush();
        return outcome.status().exitCode();
    }
}
