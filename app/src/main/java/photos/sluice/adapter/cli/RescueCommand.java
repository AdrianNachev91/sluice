package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.rescue.RescueSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Promotes what is left in a Review folder, then removes the folder once nothing is left in it.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 */
@Component
@Profile("cli")
@Command(name = RescueCommand.VERB,
        description = "Move what is left in a Review folder into your Library, and remove the folder if it "
                + "ends up empty.")
public class RescueCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "rescue";

    private final Pipeline pipeline;
    private final JobReports reports;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "FOLDER",
            description = "The folder inside Review, e.g. 2019-06 or Food.")
    @SuppressWarnings("unused")
    private @Nullable String folder;

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
        return this.reports.report(running, VERB, this::folder, this.pipeline::rescue,
                RescueSummary::cancelled, this::rescued);
    }

    /**
     * Which folder this run was asked to promote.
     *
     * @return {@link String} the folder
     * @throws ScopeRefusedException where the name is not one folder under Review
     */
    private String folder() {
        final String name = Objects.requireNonNull(this.folder,
                "picocli refuses a missing positional before this runs");
        if (!withinReview(name)) {
            throw new ScopeRefusedException(new Refusal(RefusalKind.SCOPE_VALUE_REFUSED,
                    "Not a folder in Review: " + Refusal.shown(name) + ". Name one folder, "
                            + "like 2019-06 or Food.",
                    Fields.of("parameter", "FOLDER", "value", name)));
        }
        return name;
    }

    /**
     * Whether a name stays inside Review once resolved.
     *
     * <p>Judged against a stand-in root rather than the configured one. The answer is the same for
     * either, and reading the configured one would refuse before the folders have been checked.
     *
     * @param name {@link String} the folder name as it was typed
     * @return boolean true when it names something inside Review
     */
    private static boolean withinReview(final String name) {
        final Path review = Path.of("Review");
        try {
            final Path resolved = review.resolve(name).normalize();
            return resolved.startsWith(review) && !resolved.equals(review);
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
        if (rescued.rescued() == 0 && rescued.skipped().isEmpty()) {
            return List.of(stopped
                    ? "Stopped before anything was moved to your Library."
                    : "Nothing in this folder was ready to move to your Library.");
        }
        final List<String> lines = new ArrayList<>();
        if (stopped) {
            lines.add("Stopped. The rest is still in the Review folder.");
        }
        ResultLines.addWhenAny(lines, "Moved to your Library", rescued.rescued());
        ResultLines.addWhenAny(lines, "Left behind", rescued.skipped().size());
        lines.add(rescued.folderRemoved() ? "The folder was removed." : "The folder is still there.");
        return lines;
    }
}
