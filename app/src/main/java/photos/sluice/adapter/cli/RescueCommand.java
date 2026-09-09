package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.rescue.RescueSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Moves what is left in one waiting folder back into Sorted, then removes the folder once nothing
 * is left in it.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 */
@Component
@Profile("cli")
@Command(name = RescueCommand.VERB,
        description = "Move what is left in a waiting folder back into Sorted, and remove the folder if it "
                + "ends up empty. A sift and a move to your Library can both reach it there.")
public class RescueCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "rescue";

    /**
     * The option naming which root holds the folder.
     */
    static final String FROM_OPTION = "--from";

    private static final String REVIEW = "review";
    private static final String UNREVIEWABLE = "unreviewable";
    private static final String DUPLICATES = "duplicates";

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "FOLDER",
            description = "The folder to empty, e.g. 2019-06 or Food. Under " + UNREVIEWABLE
                    + " it is a year and month, like 2019/06. Under " + DUPLICATES + " it is one "
                    + "near-copy group, like 2019-06_beach.")
    @SuppressWarnings("unused")
    private @Nullable String folder;

    @Option(names = FROM_OPTION, paramLabel = "ROOT",
            description = "Which root holds it: " + REVIEW + ", the default, " + UNREVIEWABLE
                    + " or " + DUPLICATES + ".")
    @SuppressWarnings("unused")
    private @Nullable String from;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the rescue
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     */
    public RescueCommand(final Pipeline pipeline, final JobReports reports) {
        this.pipeline = pipeline;
        this.reports = reports;
    }

    /**
     * Rescues the folder the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::target,
                asked -> this.pipeline.rescue(asked.root(), asked.folder()),
                RescueSummary::cancelled, this::rescued);
    }

    /**
     * Which folder under which root this run was asked to empty.
     *
     * @return {@link Target} the root and the folder inside it
     * @throws ScopeRefusedException where either argument names nothing this verb can take
     */
    private Target target() {
        return new Target(this.root(), this.folder());
    }

    /**
     * The root the folder sits under, defaulting to Review where nothing named one.
     *
     * @return {@link RescueRoot} the root asked for
     * @throws ScopeRefusedException where the value is neither root
     */
    private RescueRoot root() {
        final String asked = this.from;
        if (asked == null) {
            return RescueRoot.REVIEW;
        }
        return switch (asked.toLowerCase(Locale.UK)) {
            case REVIEW -> RescueRoot.REVIEW;
            case UNREVIEWABLE -> RescueRoot.UNREVIEWABLE;
            case DUPLICATES -> RescueRoot.DUPLICATES;
            default -> throw new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                    "Not a root: " + Refusal.shownValue(asked) + ". " + FROM_OPTION + " takes " + REVIEW + ", "
                            + UNREVIEWABLE + " or " + DUPLICATES + ".",
                    Fields.of("option", FROM_OPTION, "value", asked)));
        };
    }

    /**
     * Which folder this run was asked to empty.
     *
     * @return {@link String} the folder
     * @throws ScopeRefusedException where the name is not one folder inside a root
     */
    private String folder() {
        final String name = Objects.requireNonNull(this.folder,
                "picocli refuses a missing positional before this runs");
        if (!withinRoot(name)) {
            throw new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                    "Not a folder to rescue: " + Refusal.shownValue(name) + ". Name one folder, "
                            + "like 2019-06 or Food.",
                    Fields.of("parameter", "FOLDER", "value", name)));
        }
        return name;
    }

    /**
     * Whether a name stays inside its root once resolved.
     *
     * <p>Judged against a stand-in root rather than the configured one. The answer is the same for
     * either, and reading the configured one would refuse before the folders have been checked.
     *
     * @param name {@link String} the folder name as it was typed
     * @return boolean true when it names something inside the root
     */
    private static boolean withinRoot(final String name) {
        final Path root = Path.of("root");
        try {
            final Path resolved = root.resolve(name).normalize();
            return resolved.startsWith(root) && !resolved.equals(root);
        } catch (final InvalidPathException notAPath) {
            return false;
        }
    }

    /**
     * What the command makes of a finished rescue.
     *
     * @param finished a {@link JobReports.Finished} of {@link RescueSummary} what the rescue did,
     *        and whether the caller stopped it
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome rescued(final JobReports.Finished<RescueSummary> finished) {
        final RescueSummary rescued = finished.answer();
        return CommandOutcome.done(RescuePayloads.rescued(rescued), lines(rescued, finished.stopped()));
    }

    /**
     * What a rescue run tells a person it did.
     *
     * @param rescued {@link RescueSummary} what the run did
     * @param stopped boolean whether the caller asked it to stop
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final RescueSummary rescued, final boolean stopped) {
        if (rescued.moved() == 0 && rescued.alreadyInSorted() == 0) {
            return List.of(stopped
                    ? "Stopped before anything was moved to Sorted."
                    : "Nothing in this folder was ready to move to Sorted.");
        }
        final List<String> lines = new ArrayList<>();
        if (stopped) {
            lines.add(stoppedLine(rescued.leftBehind()));
        }
        ResultLines.addWhenAny(lines, "Moved to Sorted", rescued.rescued());
        ResultLines.addWhenAny(lines, "Moved to Unsorted", rescued.undated());
        ResultLines.addWhenAny(lines, "Deleted: already in Sorted", rescued.alreadyInSorted());
        if (rescued.undated() > 0) {
            lines.add("No year names those, so write " + CommitCommand.VERB + " "
                    + ScopeArguments.UNDATED + " to move them to your Library.");
        }
        lines.add(rescued.folderRemoved() ? "The folder was removed." : "The folder is still there.");
        return lines;
    }

    /**
     * What a stopped run says above its counts.
     *
     * <p>Zero is reachable, so it gets its own sentence rather than a line reading "0 photos and
     * videos are still in the folder you started from".
     *
     * @param leftBehind int photos and videos still in the folder the run was given
     * @return {@link String} the line
     */
    private static String stoppedLine(final int leftBehind) {
        if (leftBehind == 0) {
            return "Stopped. No photos or videos are left in the folder you started from.";
        }
        if (leftBehind == 1) {
            return "Stopped. One photo or video is still in the folder you started from.";
        }
        return "Stopped. " + ResultLines.grouped(leftBehind)
                + " photos and videos are still in the folder you started from.";
    }

    /**
     * What one rescue run was asked for.
     *
     * @param root {@link RescueRoot} which root holds the folder
     * @param folder {@link String} the folder's path below it
     */
    private record Target(RescueRoot root, String folder) {
    }
}
