package photos.sluice.adapter.cli;

import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.job.WaitingCullJob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns what a sift or a resume produced into the outcome a person and a machine both read.
 *
 * <p>The exit status comes entirely from which of the four shapes came back, never from whether
 * the caller typed a cancel.
 */
final class CullOutcomeReport {

    private CullOutcomeReport() {
    }

    /**
     * Reads how a sift ended into the outcome this surface reports.
     *
     * @param outcome {@link CullJobOutcome} how the run ended
     * @return {@link CommandOutcome} the outcome
     */
    static CommandOutcome of(final CullJobOutcome outcome) {
        return switch (outcome) {
            case final CullJobOutcome.Applied applied -> appliedOutcome(applied);
            case final CullJobOutcome.Waiting waiting -> waitingOutcome(waiting);
            case final CullJobOutcome.Blocked blocked -> blockedOutcome(blocked);
            case final CullJobOutcome.Cancelled cancelled -> cancelledOutcome(cancelled);
        };
    }

    /**
     * A completed run: what apply moved.
     *
     * @param applied {@link CullJobOutcome.Applied} the completed run
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome appliedOutcome(final CullJobOutcome.Applied applied) {
        final List<String> lines = new ArrayList<>();
        lines.add(ResultLines.count("Photos looked at", applied.applyReport().reviewed()));
        lines.add(ResultLines.count("Sheets judged", applied.cullReport().montagesCulled()));
        lines.add(ResultLines.count("Calls to your provider", applied.cullReport().apiCalls()));
        applied.applyReport().byCategory()
                .forEach((category, moved) -> ResultLines.addWhenAny(lines, category, moved));
        ResultLines.addWhenAny(lines, "Near-duplicate groups", applied.applyReport().nearDupGroups());
        ResultLines.addWhenAny(lines, "Copies set aside", applied.applyReport().nearDupRejects());
        ResultLines.addWhenAny(lines, "Could not be judged", applied.applyReport().unreviewable());
        return CommandOutcome.done(CullPayloads.outcome(applied), lines, notes(applied));
    }

    /**
     * A run that paused with work left, worded by why.
     *
     * @param waiting {@link CullJobOutcome.Waiting} the paused run
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome waitingOutcome(final CullJobOutcome.Waiting waiting) {
        final WaitingCullJob job = waiting.job();
        final String line = switch (waiting.reason()) {
            case CANCELLED -> "Stopped. You can continue at any time - run 'resume " + job.scope() + "'.";
            case SHARDS_OUTSTANDING -> "The sheets are ready, waiting for your agent's decisions on them. "
                    + "Nothing moves until they arrive.";
            case CEILING_REACHED -> "Sluice stopped this sift because it went far past what it was "
                    + "expected to cost. Nothing more has been spent from your provider account balance. "
                    + "Run 'resume " + job.scope() + "' to continue under a fresh limit.";
        };
        return CommandOutcome.waiting(CullPayloads.outcome(waiting), List.of(line), notes(waiting));
    }

    /**
     * A run whose shards are all in, but whose apply refused anyway.
     *
     * @param blocked {@link CullJobOutcome.Blocked} the blocked run
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome blockedOutcome(final CullJobOutcome.Blocked blocked) {
        final String line = "Sifting stopped and needs a look. Run 'troubleshoot " + blocked.job().scope()
                + "' to see what went wrong.";
        return CommandOutcome.blocked(CullPayloads.outcome(blocked), List.of(line), notes(blocked));
    }

    /**
     * A run stopped before montage rendering finished, leaving nothing on disk.
     *
     * @param cancelled {@link CullJobOutcome.Cancelled} the cancelled run
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome cancelledOutcome(final CullJobOutcome.Cancelled cancelled) {
        return CommandOutcome.done(CullPayloads.outcome(cancelled),
                List.of("Stopped. No sheets were built yet, and nothing was sent to your provider."),
                notes(cancelled)).cancelled();
    }

    /**
     * What is worth reading beside the answer, on any of the four shapes.
     *
     * @param outcome {@link CullJobOutcome} how the run ended
     * @return a {@link List} of {@link String} the notes, empty where there are none
     */
    private static List<String> notes(final CullJobOutcome outcome) {
        final List<String> notes = new ArrayList<>();
        spendNote(outcome.cullReport()).ifPresent(notes::add);
        if (outcome.archivedPriorRun() != null) {
            notes.add("A previous sift of this scope was moved to " + outcome.archivedPriorRun() + ".");
        }
        return notes;
    }

    /**
     * What this run actually consumed, where it consumed anything.
     *
     * @param report {@link CullReport} what the run judged and consumed
     * @return an {@link Optional} of {@link String} the note, or empty where nothing was spent
     */
    private static Optional<String> spendNote(final CullReport report) {
        final TokenSpend spend = report.spend();
        final long total = spend.inputTokens() + spend.outputTokens();
        if (total == 0) {
            return Optional.empty();
        }
        return Optional.of("Spent: " + ResultLines.grouped(total) + " tokens across " + report.apiCalls()
                + (report.apiCalls() == 1 ? " call." : " calls."));
    }
}
