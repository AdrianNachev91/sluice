package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullException;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Supplier;

/**
 * Runs one command's own work and reports whatever came of it, in whichever shape was asked for.
 *
 * <p>Every verb goes through here, so what a caller has to handle is decided once. A verb supplies
 * only the part that differs: what it did, and how to say so. Refusals, unexpected failures, the
 * exit code and the two streams are all settled the same way whichever verb ran.
 *
 * <p>It catches what a command raised rather than letting it out. An exception reaching the parser
 * arrives as a stack trace on the wrong stream, with no document at all. A scripted caller can do
 * nothing with that.
 *
 * <p>An {@link Error} is left alone. Those say the machine is in trouble rather than the command,
 * and dressing one up as a tidy exit code would hide that.
 */
@Component
@Profile("cli")
public class CommandReports {

    private final RefusalClassifier classifier;
    private final JsonMapper mapper;

    /**
     * Creates the reporter.
     *
     * @param classifier {@link RefusalClassifier} says whether a failure was something the app refused
     */
    public CommandReports(final RefusalClassifier classifier) {
        this.classifier = classifier;
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Runs one verb's work and writes what it produced.
     *
     * @param spec {@link CommandSpec} the running command, which carries its own two streams
     * @param command {@link String} the verb's name, as the document reports it
     * @param work a {@link Supplier} of {@link CommandOutcome} the verb's own work
     * @return int the code the process leaves with
     */
    public int report(final CommandSpec spec, final String command, final Supplier<CommandOutcome> work) {
        return this.writerFor(spec).write(command, outcomeOf(work, this.classifier));
    }

    /**
     * The writer for one invocation, over the streams the parser was given.
     *
     * @param spec {@link CommandSpec} the running command
     * @return {@link DocumentWriter} the writer
     */
    private DocumentWriter writerFor(final CommandSpec spec) {
        final CommandLine commandLine = spec.commandLine();
        return new DocumentWriter(commandLine.getOut(), commandLine.getErr(),
                SluiceCli.documentAsked(spec), this.mapper);
    }

    /**
     * What the work produced, or what its failure amounts to.
     *
     * @param work a {@link Supplier} of {@link CommandOutcome} the verb's own work
     * @param classifier {@link RefusalClassifier} says whether a failure was a refusal
     * @return {@link CommandOutcome} the outcome to report
     */
    private static CommandOutcome outcomeOf(final Supplier<CommandOutcome> work,
                                            final RefusalClassifier classifier) {
        try {
            return work.get();
        } catch (final RuntimeException failure) {
            return reading(failure, classifier);
        }
    }

    /**
     * What a failure amounts to, whether or not working that out succeeds.
     *
     * <p>Reading a failure is itself work that can fail. Deciding what a missing credential means
     * asks a credential store which places hold one. A machine whose store has stopped answering is
     * exactly the machine likely to be asked.
     *
     * <p>What is reported then is the original failure, not the one raised reading it. The caller
     * asked about their command, and the first is the one that stopped it. The second rides along
     * as a suppressed exception, so whoever reads the trace still finds both.
     *
     * <p>A {@link CullException} is read before the classifier is asked, because it is not a
     * refusal.
     *
     * @param failure {@link Throwable} what the command raised
     * @param classifier {@link RefusalClassifier} says whether that was a refusal
     * @return {@link CommandOutcome} the outcome to report
     */
    private static CommandOutcome reading(final RuntimeException failure, final RefusalClassifier classifier) {
        try {
            if (RefusalClassifier.unwrapped(failure) instanceof final CullException incomplete) {
                return CullOutcomeReport.incompleteOutcome(incomplete);
            }
            final Refusal refusal = classifier.refusalFor(failure);
            return refusal == null ? CommandOutcome.failed(failure) : CommandOutcome.refused(refusal);
        } catch (final RuntimeException unreadable) {
            failure.addSuppressed(unreadable);
            return CommandOutcome.failed(failure);
        }
    }
}
