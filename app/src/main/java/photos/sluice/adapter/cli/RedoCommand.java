package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Sets aside a run's rejected answers and hands back fresh instructions to judge them again.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>Not a job. The work is setting aside a handful of small files. It spends against the provider
 * account balance rather than losing anything, so it takes no {@code --yes}. The note on the cost
 * is printed alongside the instructions instead.
 */
@Component
@Profile("cli")
@Command(name = RedoCommand.VERB,
        description = "Free a run's rejected sheets to be judged again, and print fresh instructions for them.")
public class RedoCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "redo";

    /**
     * Printed on the error stream alongside the instructions.
     */
    private static final String SPEND_NOTE = "Any sheets still missing are judged too. That spends "
            + "from your provider account balance.";

    private final Pipeline pipeline;
    private final CommandReports reports;
    private final RunAddress address;
    private final MutatingCommandStart start;

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
     * @param pipeline {@link Pipeline} the facade that sets the answers aside
     * @param reports {@link CommandReports} writes whatever this produced
     * @param address {@link RunAddress} turns what was typed into the run's own folder
     * @param start {@link MutatingCommandStart} claims the working root before anything is written
     */
    public RedoCommand(final Pipeline pipeline, final CommandReports reports, final RunAddress address,
                       final MutatingCommandStart start) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.address = address;
        this.start = start;
    }

    /**
     * Sets aside the rejected answers on the run the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::redo);
    }

    /**
     * Sets the run's rejected answers aside and reports the instructions to judge them again.
     *
     * @return {@link CommandOutcome} the outcome
     * @throws ScopeRefusedException when the address names no sift on disk
     * @throws Pipeline.NothingToRedoException when a fresh diagnosis of the run blames no sheet
     */
    private CommandOutcome redo() {
        final Path prepDir = this.folder();
        this.start.claimAndSweep();
        final String prompt = this.pipeline.redoRejectedAnswers(prepDir);
        return CommandOutcome.done(new RedoPayloads.PromptPayload(prompt), List.of(prompt), List.of(SPEND_NOTE));
    }

    /**
     * Which run this call was asked to redo.
     *
     * @return {@link Path} the run's own folder
     * @throws ScopeRefusedException when the address names no sift on disk
     */
    private Path folder() {
        return this.address.folderFor(this.run);
    }
}
