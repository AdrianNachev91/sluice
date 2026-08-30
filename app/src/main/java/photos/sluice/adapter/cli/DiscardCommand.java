package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.DiscardReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Gives up on a run, archiving its records.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>Loses the run's paid sheet decisions, so it refuses without {@code --yes}, naming what is lost
 * and where it goes.
 */
@Component
@Profile("cli")
@Command(name = DiscardCommand.VERB, description = "Give up on a run, archiving its records.")
public class DiscardCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "discard";

    private final Pipeline pipeline;
    private final JobReports reports;
    private final RunAddress address;
    private final DiscardConfirmation confirmation;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "RUN",
            description = "A scope tag, as 'runs' prints it, or the folder's own full path.")
    @SuppressWarnings("unused")
    private @Nullable String run;

    @Option(names = "--yes", description = "Confirm giving up the run's paid sheet decisions.")
    @SuppressWarnings("unused")
    private boolean yes;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that discards the run
     * @param reports {@link JobReports} runs the job and writes whatever came of it
     * @param address {@link RunAddress} turns what was typed into the run's own folder
     * @param confirmation {@link DiscardConfirmation} what to say without {@code --yes}
     */
    public DiscardCommand(final Pipeline pipeline, final JobReports reports, final RunAddress address,
                          final DiscardConfirmation confirmation) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.address = address;
        this.confirmation = confirmation;
    }

    /**
     * Discards the run the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.reportUninterruptible(running, VERB, this::folder, this.pipeline::discard,
                DiscardCommand::discarded);
    }

    /**
     * What the command makes of a finished discard.
     *
     * @param finished a {@link JobReports.Finished} of {@link DiscardReport} what the discard did
     * @return {@link CommandOutcome} the outcome
     */
    static CommandOutcome discarded(final JobReports.Finished<DiscardReport> finished) {
        final DiscardReport report = finished.answer();
        final String setAside = report.shardsSetAside() == 0
                ? ""
                : ResultLines.grouped(report.shardsSetAside()) + " sheet decision"
                        + (report.shardsSetAside() == 1 ? "" : "s") + " are set aside with it. ";
        final List<String> lines = List.of("Discarded. " + setAside + "Its records are archived in "
                + report.graveyard() + " for 30 days.");
        return CommandOutcome.done(RecoveryPayloads.discarded(report), lines);
    }

    /**
     * Which run this call was asked to discard.
     *
     * @return {@link Path} the run's own folder
     * @throws ScopeRefusedException when the address names no sift on disk
     * @throws ConfirmationRequiredException when {@code --yes} was not given
     */
    private Path folder() {
        final Path prepDir = this.address.folderFor(Objects.requireNonNull(this.run,
                "picocli refuses a missing positional before this runs"));
        if (!this.yes) {
            throw new ConfirmationRequiredException(this.confirmation.messageFor(prepDir));
        }
        return prepDir;
    }
}
