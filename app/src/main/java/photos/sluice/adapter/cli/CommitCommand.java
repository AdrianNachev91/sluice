package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;
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
 * Moves what is staged in Sorted into the library.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>A year or {@code all} has to be named. A typo in this position should not sweep everything
 * staged into the library, so there is no default for it.
 */
@Component
@Profile("cli")
@Command(name = CommitCommand.VERB,
        description = "Move what is staged in Sorted into the library, under a year or " + CommitCommand.ALL + ".")
public class CommitCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "commit";

    /**
     * The word standing for the whole library.
     */
    static final String ALL = ScopeArguments.ALL;

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "YEAR|" + ALL,
            description = "The year to move, or " + ALL + " for the whole library.")
    @SuppressWarnings("unused")
    private @Nullable String year;

    @Option(names = ScopeArguments.MONTHS, paramLabel = "MONTHS",
            description = "Months within that year, as a span: 6-8. A gapped list is refused.")
    @SuppressWarnings("unused")
    private @Nullable String months;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the move
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     */
    public CommitCommand(final Pipeline pipeline, final JobReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Moves what the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::scope, this.pipeline::commit,
                CommitSummary::cancelled, this::committed);
    }

    /**
     * Which files this run was asked to move.
     *
     * @return {@link CommitScope} what to move
     * @throws ScopeRefusedException when the arguments name no scope this verb can build
     */
    private CommitScope scope() {
        return new ScopeArguments(this.year, this.months, null).commitScope(VERB);
    }

    /**
     * What the command makes of a finished move.
     *
     * @param finished a {@link JobReports.Finished} of {@link CommitSummary} what the run did, and
     *        whether the caller stopped it
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome committed(final JobReports.Finished<CommitSummary> finished) {
        final CommitSummary committed = finished.answer();
        return CommandOutcome.done(CommitPayloads.committed(committed), lines(committed, finished.stopped()));
    }

    /**
     * What a move-to-library run tells a person it did.
     *
     * @param committed {@link CommitSummary} what the run did
     * @param stopped boolean whether the caller asked it to stop
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final CommitSummary committed, final boolean stopped) {
        if (committed.committed() == 0) {
            return stopped
                    ? List.of("Stopped before anything was moved to the library. Your Sorted folder is unchanged.")
                    : List.of("Nothing to move for this scope.");
        }
        final List<String> lines = new ArrayList<>();
        if (stopped) {
            lines.add(stoppedLine(committed.leftBehind()));
        }
        lines.add(ResultLines.count("Moved to your library", committed.committed()));
        for (final LibraryBucket bucket : LibraryBucket.values()) {
            ResultLines.addWhenAny(lines, label(bucket), committed.byBucket().getOrDefault(bucket, 0));
        }
        return lines;
    }

    /**
     * What a stopped run says above its counts.
     *
     * @param leftBehind int in-scope files still in Sorted
     * @return {@link String} the line
     */
    private static String stoppedLine(final int leftBehind) {
        if (leftBehind == 0) {
            return "Stopped. Everything this run picked up was moved.";
        }
        if (leftBehind == 1) {
            return "Stopped. One photo or video is still in Sorted.";
        }
        return "Stopped. " + ResultLines.grouped(leftBehind) + " photos and videos are still in Sorted.";
    }

    /**
     * What a person calls one library bucket.
     *
     * @param bucket {@link LibraryBucket} the bucket
     * @return {@link String} the label
     */
    private static String label(final LibraryBucket bucket) {
        return switch (bucket) {
            case PHOTOS -> "Photos";
            case VIDEOS -> "Videos";
            case FUNNY -> "Funny";
            case OTHER -> "Elsewhere in your library";
        };
    }
}
