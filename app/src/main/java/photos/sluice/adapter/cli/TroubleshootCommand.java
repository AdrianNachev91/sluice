package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.TroubleshootReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Repairs what a sift can fix on its own, and reports what is still open afterwards.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 */
@Component
@Profile("cli")
@Command(name = TroubleshootCommand.VERB,
        description = "Repair what can be repaired without asking you, and report what is still open.")
public class TroubleshootCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "troubleshoot";

    private final Pipeline pipeline;
    private final JobReports reports;
    private final RunAddress address;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "RUN",
            description = "A scope tag, as 'runs' prints it, or the folder's own full path.")
    @SuppressWarnings("unused")
    private @Nullable String run;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that runs the repair pass
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     * @param address {@link RunAddress} turns what was typed into the run's own folder
     */
    public TroubleshootCommand(final Pipeline pipeline, final JobReports reports, final RunAddress address) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.address = address;
    }

    /**
     * Repairs the run the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.reportUninterruptible(running, VERB, this::folder, this.pipeline::troubleshoot,
                this::troubleshot);
    }

    /**
     * Which run this call was asked to repair.
     *
     * @return {@link Path} the run's own folder
     * @throws ScopeRefusedException when the address names no sift on disk
     */
    private Path folder() {
        return this.address.folderFor(this.run);
    }

    /**
     * What the command makes of a finished repair pass.
     *
     * @param finished a {@link JobReports.Finished} of {@link TroubleshootReport} what the pass found
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome troubleshot(final JobReports.Finished<TroubleshootReport> finished) {
        final TroubleshootReport report = finished.answer();
        final Object payload = TroubleshootPayloads.of(report);
        final List<String> lines = lines(report);
        return switch (report.after().state()) {
            case COMPLETE, READY -> CommandOutcome.done(payload, lines);
            case WAITING -> CommandOutcome.waiting(payload, lines, List.of());
            case BLOCKED, DAMAGED -> CommandOutcome.blocked(payload, lines, List.of());
        };
    }

    /**
     * What a repair pass tells a person it found.
     *
     * @param report {@link TroubleshootReport} what the pass found
     * @return a {@link List} of {@link String} the lines to print
     */
    private static List<String> lines(final TroubleshootReport report) {
        final List<String> lines = new ArrayList<>();
        if (report.indexRebuilt()) {
            lines.add("This sift's records were unreadable and have been rebuilt.");
        }
        if (!report.strayShardsRepaired().isEmpty()) {
            lines.add(ResultLines.countLine("Stray sheets filed into place", report.strayShardsRepaired().size()));
        }
        final List<Finding> open = report.after().findings();
        if (open.isEmpty()) {
            lines.add(nothingOpenLine(report.after().state()));
        } else {
            lines.add(ResultLines.countLine("Needs your call", open.size()));
            open.forEach(finding -> lines.add(humanLine(finding)));
        }
        return lines;
    }

    /**
     * What one open finding tells a person.
     *
     * @param finding {@link Finding} the open finding
     * @return {@link String} the line
     */
    private static String humanLine(final Finding finding) {
        final String key = AnswerVocabulary.keyFor(finding);
        if (key == null) {
            return "Not answerable here. Run this again with --json to see what it is.";
        }
        return "Needs an answer: " + key + ". Run 'answer <run> " + key + " <option>', option one of "
                + String.join(", ", AnswerVocabulary.optionsFor(finding)) + ".";
    }

    /**
     * What a repair pass says when nothing is left open.
     *
     * @param state {@link PrepDirHealth.State} the run's state once the pass has run
     * @return {@link String} the line
     */
    private static String nothingOpenLine(final PrepDirHealth.State state) {
        return switch (state) {
            case COMPLETE -> "This sift already finished.";
            case READY -> "Every sheet was judged. Run 'resume' to move the photos.";
            case WAITING -> "Waiting for the rest of the sheets to come back.";
            case BLOCKED -> "Something is still wrong with this sift, but nothing further could be found.";
            case DAMAGED -> "This sift's own records could not be read. Often another program has them open.";
        };
    }
}
