package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Files what is in the Inbox into Sorted, under the year each photo was taken.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>With nothing named it takes the oldest year present. Sorted files leave the Inbox, so the
 * oldest year remaining is always the one to do next.
 */
@Component
@Profile("cli")
@Command(name = SortCommand.VERB,
        description = "File what is in your Inbox into Sorted, under the year each photo or video was taken.")
public class SortCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "sort";

    // Indented, and opening on "of those", because it counts part of the line above it rather than
    // a place of its own. Every other line here names somewhere files went.
    private static final String GUESSED_LABEL = "  of those, low confidence date";

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "YEAR",
            description = "The year to sort. Default is the oldest year in your Inbox.")
    @SuppressWarnings("unused")
    private @Nullable String year;

    @Option(names = ScopeArguments.MONTHS, paramLabel = "MONTHS",
            description = "Months within that year, as a span: 6-8. A gapped list is refused.")
    @SuppressWarnings("unused")
    private @Nullable String months;

    @Option(names = ScopeArguments.OLDEST, paramLabel = "N",
            description = "The N oldest files by date instead of a year. Videos count too.")
    @SuppressWarnings("unused")
    private @Nullable Integer oldest;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the sort
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     */
    public SortCommand(final Pipeline pipeline, final JobReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Sorts what the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::scope, this.pipeline::sort,
                SortSummary::cancelled, this::sorted);
    }

    /**
     * Which photos this run was asked for.
     *
     * @return {@link SortScope} what to sort
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    private SortScope scope() {
        return new ScopeArguments(this.year, this.months, this.oldest).sortScope(VERB);
    }

    /**
     * What the command makes of a finished sort.
     *
     * @param finished a {@link JobReports.Finished} of {@link SortSummary} what the sort did, and
     *        whether the caller stopped it
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome sorted(final JobReports.Finished<SortSummary> finished) {
        final SortSummary sorted = finished.answer();
        return CommandOutcome.done(SortPayloads.sorted(sorted),
                lines(sorted, finished.stopped(), this.narrowed()), notes(sorted));
    }

    /**
     * Whether the caller asked for less than the whole Inbox.
     *
     * <p>Read from the arguments rather than from what came back. What a run found cannot tell an
     * empty Inbox from a full one holding nothing for the year asked for.
     *
     * @return boolean true where the arguments narrowed the run
     */
    private boolean narrowed() {
        return this.year != null || this.months != null || this.oldest != null;
    }

    /**
     * What is worth reading beside the answer.
     *
     * <p>Says what the dates mean rather than how they were worked out. A note naming which link of
     * the date chain the run fell back to can be false while the summary it was built from shows
     * otherwise. What a reader can check either way is a date on a photo.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @return a {@link List} of {@link String} the lines, empty where nothing tripped
     */
    private static List<String> notes(final SortSummary sorted) {
        if (sorted.warnings().isEmpty()) {
            return List.of();
        }
        return List.of("The dates on these photos may be wrong. Check a few before you sift them.");
    }

    /**
     * What a sort tells a person it did.
     *
     * <p>Photos and videos are named whatever they count, because a sort that filed nothing is the
     * news rather than an empty answer. The rest are named only where they happened, so a plain run
     * is two lines.
     *
     * <p>A stopped run says so above its counts rather than below them. Counts alone read as a
     * finished sort, and the reader is then corrected by a line they have already passed. What the
     * counts do not mention is everything left in the Inbox, which is why the line names it.
     *
     * <p>Only an unnarrowed run that finished is entitled to the sentence about the Inbox. Telling
     * a caller their Inbox held nothing, when they stopped the run or asked for a year it does not
     * hold, says their photos are gone.
     *
     * @param sorted {@link SortSummary} what the sort did
     * @param stopped boolean whether the caller asked it to stop
     * @param narrowed boolean whether the caller asked for less than the whole Inbox
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final SortSummary sorted, final boolean stopped,
                                      final boolean narrowed) {
        if (sorted.processed() == 0) {
            if (stopped) {
                return List.of("Stopped before anything was sorted. Your Inbox is unchanged.");
            }
            return List.of(narrowed
                    ? "Nothing to sort for this scope."
                    : "Nothing in your Inbox was ready to sort.");
        }
        final List<String> lines = new ArrayList<>();
        if (stopped) {
            lines.add(stoppedLine(sorted.leftBehind()));
        }
        final SortSummary.Guessed guessed = sorted.guessed();
        lines.add(ResultLines.count("Photos sorted", sorted.photosSorted()));
        ResultLines.addWhenAny(lines, GUESSED_LABEL, guessed.photosSorted());
        lines.add(ResultLines.count("Videos sorted", sorted.videosSorted()));
        ResultLines.addWhenAny(lines, GUESSED_LABEL, guessed.videosSorted());
        ResultLines.addWhenAny(lines, "Already in your Library", sorted.reimportsDeleted());
        ResultLines.addWhenAny(lines, "Identical copies removed", sorted.byteDupsDeleted());
        ResultLines.addWhenAny(lines, "Moved to Review", sorted.lowRes());
        ResultLines.addWhenAny(lines, GUESSED_LABEL, guessed.lowRes());
        ResultLines.addWhenAny(lines, "Could not be dated", sorted.unsorted());
        return lines;
    }

    /**
     * What a stopped run says above its counts.
     *
     * <p>Zero is reachable, so it gets its own sentence rather than a line reading "0 photos and
     * videos are still in your Inbox".
     *
     * @param leftBehind int in-scope files still in the Inbox
     * @return {@link String} the line
     */
    private static String stoppedLine(final int leftBehind) {
        if (leftBehind == 0) {
            return "Stopped. Everything this run picked up was sorted.";
        }
        if (leftBehind == 1) {
            return "Stopped. One photo or video is still in your Inbox.";
        }
        return "Stopped. " + ResultLines.grouped(leftBehind) + " photos and videos are still in your Inbox.";
    }
}
