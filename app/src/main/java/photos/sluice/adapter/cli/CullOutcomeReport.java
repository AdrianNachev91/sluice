package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullException;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.application.port.out.TokenSpend;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
     * @param duplicates {@link Path} the Duplicates root, named in the row counting the copies
     *        moved there
     * @param instructions a {@link Function} of {@link Path} to {@link String} writes the text to
     *        hand an agent. Asked only on a run still waiting on its sheets
     * @return {@link CommandOutcome} the outcome
     */
    static CommandOutcome of(final CullJobOutcome outcome, final Path duplicates,
                             final Function<Path, String> instructions) {
        return switch (outcome) {
            case final CullJobOutcome.Applied applied -> appliedOutcome(applied, duplicates);
            case final CullJobOutcome.Waiting waiting -> waitingOutcome(waiting, duplicates, instructions);
            case final CullJobOutcome.Blocked blocked -> blockedOutcome(blocked);
            case final CullJobOutcome.Cancelled cancelled -> cancelledOutcome(cancelled);
        };
    }

    /**
     * A run the provider could not finish, which it raised rather than returned.
     *
     * <p>Blocked rather than refused. The run spent from the caller's provider account before it
     * gave up, so "nothing happened" would be false. What it leaves behind is a run needing a look,
     * which is the state blocked names.
     *
     * @param incomplete {@link CullException} what the provider could not finish, and why
     * @return {@link CommandOutcome} the outcome
     */
    static CommandOutcome incompleteOutcome(final CullException incomplete) {
        final CullReport report = incomplete.report();
        final List<String> lines = List.of("This sift could not be finished.",
                String.valueOf(incomplete.getMessage()),
                "Run 'troubleshoot' on it to see what can be repaired.");
        final List<String> notes = report == null ? List.of()
                : spendNote(report).map(List::of).orElseGet(List::of);
        return CommandOutcome.blocked(CullPayloads.incomplete(incomplete.getMessage(), report), lines, notes);
    }

    /**
     * A completed run: what apply moved.
     *
     * @param applied {@link CullJobOutcome.Applied} the completed run
     * @param duplicates {@link Path} the Duplicates root the near-duplicate copies went to
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome appliedOutcome(final CullJobOutcome.Applied applied, final Path duplicates) {
        final List<String> lines = new ArrayList<>();
        lines.add(ResultLines.count("Photos looked at", applied.applyReport().reviewed()));
        ResultLines.addWhenAny(lines, "Sheets judged", applied.cullReport().montagesCulled());
        ResultLines.addWhenAny(lines, "Calls to your provider", applied.cullReport().apiCalls());
        applied.applyReport().byCategory().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(category -> ResultLines.addWhenAny(lines, category.getKey(), category.getValue()));
        ResultLines.addWhenAny(lines, "Near-duplicate groups", applied.applyReport().nearDupGroups());
        ResultLines.addWhenAny(lines, "Copies moved to " + duplicates, applied.applyReport().nearDupRejects());
        ResultLines.addWhenAny(lines, "Could not be judged", applied.applyReport().unreviewable());
        return CommandOutcome.done(CullPayloads.outcome(applied), lines, notes(applied));
    }

    /**
     * A run that paused with work left, worded by why.
     *
     * @param waiting {@link CullJobOutcome.Waiting} the paused run
     * @param duplicates {@link Path} the Duplicates root, named in the row counting the copies
     *        moved there
     * @param instructions a {@link Function} of {@link Path} to {@link String} writes the text to
     *        hand an agent
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome waitingOutcome(final CullJobOutcome.Waiting waiting,
                                                 final Path duplicates,
                                                 final Function<Path, String> instructions) {
        final WaitingCullJob job = waiting.job();
        final String line = switch (waiting.reason()) {
            case CANCELLED -> "Stopped. You can continue at any time - run 'resume " + job.scope() + "'.";
            case SHARDS_OUTSTANDING -> "The sheets are ready, waiting for your agent's decisions on them. "
                    + "Nothing moves until those decisions arrive.";
            case CEILING_REACHED -> "Sift stopped because it went far past what it was expected to "
                    + "cost. Nothing more has been spent from your provider account balance. "
                    + "Run 'resume " + job.scope() + "' to continue under a fresh limit.";
        };
        if (waiting.reason() != WaitingReason.SHARDS_OUTSTANDING) {
            return CommandOutcome.waiting(CullPayloads.outcome(waiting),
                    stoppedLines(line, waiting.movedBeforeItPaused(), duplicates), notes(waiting));
        }
        final String handover = written(instructions, job.prepDir());
        final List<String> lines = List.of(line, "", handover == null
                ? "The instructions for judging them could not be written, because this sift's own "
                        + "records could not be read. Run 'resume " + job.scope() + "' again once "
                        + "whatever is holding them clears."
                : handover);
        return CommandOutcome.waiting(CullPayloads.outcome(waiting, handover), lines, notes(waiting));
    }

    /**
     * What a paused run prints, plus the files it had already moved where it had begun moving any.
     *
     * <p>A stop landing mid-apply leaves photos in their categories.
     *
     * <p>The near-duplicate group count is left off, since these lines are about what left Sorted
     * and a group's keeper does not leave it.
     *
     * @param line {@link String} what the pause itself says
     * @param moved {@link ApplyReport} what apply moved before it stopped, or null where it never
     *        started
     * @param duplicates {@link Path} the Duplicates root, named in the row counting the copies
     *        moved there
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> stoppedLines(final String line, final @Nullable ApplyReport moved,
                                             final Path duplicates) {
        if (moved == null) {
            return List.of(line);
        }
        final List<String> lines = new ArrayList<>();
        lines.add(line);
        moved.byCategory().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(category -> ResultLines.addWhenAny(lines, category.getKey(), category.getValue()));
        ResultLines.addWhenAny(lines, "Copies moved to " + duplicates, moved.nearDupRejects());
        ResultLines.addWhenAny(lines, "Could not be judged", moved.unreviewable());
        if (lines.size() > 1) {
            lines.add(1, "It had started moving photos. These left Sorted before it stopped.");
            lines.add(2, ResultLines.count("Photos that left Sorted", leftSorted(moved)));
        }
        return List.copyOf(lines);
    }

    /**
     * Every photo an apply moved out of Sorted, which is what the lines under the total add up to.
     *
     * <p>Near-duplicate groups are not in it. A group is resolved by copying its keeper, and the
     * keeper stays in Sorted, so it is the one figure an apply can raise with nothing leaving.
     *
     * @param moved {@link ApplyReport} what the apply moved
     * @return int how many photos left Sorted
     */
    private static int leftSorted(final ApplyReport moved) {
        return moved.unreviewable() + moved.nearDupRejects()
                + moved.byCategory().values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * The instructions for judging a run's sheets, or null where the run's records could not be
     * read.
     *
     * <p>The read happens after the job has finished its own work. Letting it throw would lose that
     * run, whose sheets are built and whose paying provider has already been billed for them. The
     * failure classifies as a refusal, and a refusal reports as nothing having happened.
     *
     * @param instructions a {@link Function} of {@link Path} to {@link String} writes the text
     * @param prepDir {@link Path} the run to write them for
     * @return {@link String} the text, or null where the records could not be read
     */
    private static @Nullable String written(final Function<Path, String> instructions, final Path prepDir) {
        try {
            return instructions.apply(prepDir);
        } catch (final RuntimeException couldNotBeWritten) {
            return null;
        }
    }

    /**
     * A run whose shards are all in, but whose apply refused anyway.
     *
     * @param blocked {@link CullJobOutcome.Blocked} the blocked run
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome blockedOutcome(final CullJobOutcome.Blocked blocked) {
        final String line = "Every sheet came back, but there are problems with some. Nothing was moved. "
                + "Run 'troubleshoot " + blocked.job().scope() + "' to see what went wrong.";
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
