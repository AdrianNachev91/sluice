package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Everything one command produced, in both shapes, before anything has been written.
 *
 * @param status {@link CommandStatus} how the command ended
 * @param payload {@link Object} what the document says, or null when the command has nothing to say
 * @param resultLines a {@link List} of {@link String} the answer, for a person, on the output stream
 * @param noteLines a {@link List} of {@link String} everything else, on the error stream
 */
public record CommandOutcome(CommandStatus status, @Nullable Object payload,
                             List<String> resultLines, List<String> noteLines) {

    /**
     * Copies both line lists, so nothing can change an outcome after it was built.
     *
     * @param status {@link CommandStatus} how the command ended
     * @param payload {@link Object} what the document says, or null
     * @param resultLines a {@link List} of {@link String} the answer, for a person
     * @param noteLines a {@link List} of {@link String} everything else
     */
    public CommandOutcome {
        resultLines = List.copyOf(resultLines);
        noteLines = List.copyOf(noteLines);
    }

    /**
     * A command that did what it was asked.
     *
     * @param payload {@link Object} what the document says
     * @param resultLines a {@link List} of {@link String} the same answer, for a person
     * @return {@link CommandOutcome} the outcome
     */
    public static CommandOutcome done(final @Nullable Object payload, final List<String> resultLines) {
        return done(payload, resultLines, List.of());
    }

    /**
     * A command that did what it was asked, with something else worth saying beside the answer.
     *
     * @param payload {@link Object} what the document says
     * @param resultLines a {@link List} of {@link String} the same answer, for a person
     * @param noteLines a {@link List} of {@link String} what is worth reading but is not the answer
     * @return {@link CommandOutcome} the outcome
     */
    public static CommandOutcome done(final @Nullable Object payload, final List<String> resultLines,
                                      final List<String> noteLines) {
        return new CommandOutcome(CommandStatus.DONE, payload, resultLines, noteLines);
    }

    /**
     * The same answer, reported as a run the caller stopped.
     *
     * <p>A stopped run still says what it did before it stopped, so nothing is dropped here. Only
     * the status changes, which is how a script tells a run that finished from one that was cut
     * short.
     *
     * @return {@link CommandOutcome} the outcome
     */
    public CommandOutcome cancelled() {
        return new CommandOutcome(CommandStatus.CANCELLED, this.payload, this.resultLines, this.noteLines);
    }

    /**
     * A command the app refused.
     *
     * @param refusal {@link Refusal} which refusal, and what to tell the person
     * @return {@link CommandOutcome} the outcome
     */
    public static CommandOutcome refused(final Refusal refusal) {
        return new CommandOutcome(CommandStatus.REFUSED, refusal.payload(), List.of(),
                List.of(refusal.sentence()));
    }

    /**
     * A failure this app has no reading for.
     *
     * <p>The trace goes out with it, on both shapes. This is the one outcome where nobody can say
     * what went wrong from the fields, so the evidence is the whole of what can be offered.
     *
     * @param failure {@link Throwable} what was raised
     * @return {@link CommandOutcome} the outcome
     */
    public static CommandOutcome failed(final Throwable failure) {
        final String trace = trace(failure);
        return new CommandOutcome(CommandStatus.FAILED,
                Fields.of("type", failure.getClass().getName(),
                        "message", String.valueOf(failure.getMessage()),
                        "trace", trace),
                List.of(), List.of(trace));
    }

    /**
     * A failure's stack trace, rendered whole.
     *
     * @param failure {@link Throwable} the failure to render
     * @return {@link String} the rendered trace
     */
    private static String trace(final Throwable failure) {
        final var text = new StringWriter();
        try (final var writer = new PrintWriter(text)) {
            failure.printStackTrace(writer);
        }
        return text.toString().stripTrailing();
    }
}
