package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Resolves one open finding on a run, by the key and option {@code troubleshoot --json} named it.
 *
 * <p>It changes files, so it claims the working root first and a second Sluice working the same
 * folder is refused.
 *
 * <p>One option, {@value AnswerVocabulary#DISCARD_OPTION}, gives the whole run up rather than
 * answering one finding, refusing the same way without {@code --yes}. It calls
 * {@code Pipeline.discard} directly rather than through {@code JobReports}. So it reports progress
 * and honors {@code --quiet} the same as the dedicated {@code discard} verb, and takes no typed
 * cancel.
 */
@Component
@Profile("cli")
@Command(name = AnswerCommand.VERB, description = "Resolve one open finding on a run, by its key and option.")
public class AnswerCommand implements Callable<Integer> {

    /**
     * What a caller types, and what the document reports.
     */
    static final String VERB = "answer";

    private final Pipeline pipeline;
    private final CommandReports reports;
    private final RunAddress address;
    private final MutatingCommandStart start;
    private final DiscardConfirmation confirmation;
    private final ConsoleProgressPort progress;

    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    @Parameters(index = "0", paramLabel = "RUN",
            description = "A scope tag, as 'runs' prints it, or the folder's own full path.")
    @SuppressWarnings("unused")
    private @Nullable String run;

    @Parameters(index = "1", paramLabel = "KEY",
            description = "The finding's own key, as 'troubleshoot --json' names it.")
    @SuppressWarnings("unused")
    private @Nullable String key;

    @Parameters(index = "2", paramLabel = "OPTION", description = "One of the finding's own option ids.")
    @SuppressWarnings("unused")
    private @Nullable String option;

    @Option(names = "--yes", description = "Needed when the option you choose discards the run.")
    @SuppressWarnings("unused")
    private boolean yes;

    /**
     * Creates the command.
     *
     * @param pipeline {@link Pipeline} the facade that records the answer, or discards the run
     * @param reports {@link CommandReports} writes whatever this produced
     * @param address {@link RunAddress} turns what was typed into the run's own folder
     * @param start {@link MutatingCommandStart} claims the working root before anything is written
     * @param confirmation {@link DiscardConfirmation} what to say without {@code --yes} on the
     *        option that discards the run
     * @param progress {@link ConsoleProgressPort} reports the discard option's own progress, and
     *        takes {@code --quiet} the same way a job verb does
     */
    public AnswerCommand(final Pipeline pipeline, final CommandReports reports, final RunAddress address,
                         final MutatingCommandStart start, final DiscardConfirmation confirmation,
                         final ConsoleProgressPort progress) {
        this.pipeline = pipeline;
        this.reports = reports;
        this.address = address;
        this.start = start;
        this.confirmation = confirmation;
        this.progress = progress;
    }

    /**
     * Resolves the finding the arguments named.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandSpec running = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command");
        return this.reports.report(running, VERB, this::answered);
    }

    /**
     * Resolves the key and option against the run's currently open findings, and carries out
     * whichever answer they name.
     *
     * @return {@link CommandOutcome} the outcome
     * @throws ScopeRefusedException when the address names no sift on disk
     * @throws AnswerNotApplicableException when the key and option answer nothing open on it
     * @throws ConfirmationRequiredException when the option chosen discards the run and
     *         {@code --yes} was not given
     */
    private CommandOutcome answered() {
        final Path prepDir = this.folder();
        final String key = this.keyArg();
        final String option = this.optionArg();
        final AnswerVocabulary.Answer resolved = AnswerVocabulary.resolve(prepDir, key, option,
                this.openFindings(prepDir));
        return switch (resolved) {
            case final AnswerVocabulary.Answer.Choice choice -> this.answer(prepDir, choice);
            case final AnswerVocabulary.Answer.Discard discard -> this.discard(discard.prepDir());
            case final AnswerVocabulary.Answer.LookAgain lookAgain -> this.lookAgain(prepDir, lookAgain.file());
            case final AnswerVocabulary.Answer.NoMatch ignored -> this.nothingAnswers(prepDir, key, option);
        };
    }

    /**
     * Records one answer against the run's ledger.
     *
     * @param prepDir {@link Path} the run
     * @param choice {@link AnswerVocabulary.Answer.Choice} what the key and option resolved to
     * @return {@link CommandOutcome} the outcome
     */
    private CommandOutcome answer(final Path prepDir, final AnswerVocabulary.Answer.Choice choice) {
        this.start.claimAndSweep();
        this.pipeline.answer(prepDir, choice.answer(), AnswerSource.CLI);
        final String address = Objects.requireNonNull(this.run, "picocli refuses a missing positional before this runs");
        return CommandOutcome.done(null, List.of("Answered. Run 'troubleshoot " + Refusal.shown(address)
                + "' to see what is still open."));
    }

    /**
     * What to say where the key and option name nothing the run currently has open.
     *
     * @param prepDir {@link Path} the run
     * @param key {@link String} the key the caller named
     * @param option {@link String} the option the caller chose
     * @return {@link CommandOutcome} the outcome, where another look explains the absence
     * @throws AnswerNotApplicableException where nothing does
     */
    private CommandOutcome nothingAnswers(final Path prepDir, final String key, final String option) {
        final CommandOutcome restored = AnswerVocabulary.RECHECK_OPTION.equals(option)
                ? this.lookAgainAtARestoredFile(prepDir, key)
                : null;
        if (restored != null) {
            return restored;
        }
        throw new AnswerNotApplicableException(
                "Nothing open on this sift answers to " + key + " with " + option + ".");
    }

    /**
     * Reads the run again and says whether the photo is back, recording nothing either way.
     *
     * <p>The pass that found it missing ran at some earlier moment. A photo restored since then is
     * no longer a problem, and the caller has no way to learn that short of answering it away.
     *
     * @param prepDir {@link Path} the run
     * @param file {@link Path} the photo the finding named
     * @return {@link CommandOutcome} the outcome, saying which of the two it found
     */
    private CommandOutcome lookAgain(final Path prepDir, final Path file) {
        final boolean stillMissing = this.openFindings(prepDir).stream()
                .anyMatch(open -> open instanceof final Finding.MissingSource missing
                        && missing.file().equals(file));
        return CommandOutcome.done(null, List.of(stillMissing
                ? "It is still missing."
                : "That is no longer a problem."));
    }

    /**
     * Another look at a photo the run no longer reports as missing, where the file is back.
     *
     * <p>A finding is only open while the fault is, so a restored photo matches no key the run
     * carries. Looking again would then report that nothing answers to it. That is the good news
     * worded as a failure.
     *
     * @param prepDir {@link Path} the run
     * @param key {@link String} the key the caller named, which is the photo's own path
     * @return {@link CommandOutcome} the outcome, or null where the key names no file on disk
     */
    private @Nullable CommandOutcome lookAgainAtARestoredFile(final Path prepDir, final String key) {
        final Path file;
        try {
            file = Path.of(key);
        } catch (final InvalidPathException notAPath) {
            return null;
        }
        return Files.exists(file) ? this.lookAgain(prepDir, file) : null;
    }

    /**
     * Gives the whole run up, for the one option that resolves to that rather than to an answer.
     *
     * @param prepDir {@link Path} the run to discard
     * @return {@link CommandOutcome} the outcome
     * @throws ConfirmationRequiredException when {@code --yes} was not given
     */
    private CommandOutcome discard(final Path prepDir) {
        if (!this.yes) {
            throw new ConfirmationRequiredException(this.confirmation.messageFor(prepDir));
        }
        this.progress.quiet(SluiceCli.quietAsked(Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command")));
        this.start.claimAndSweep();
        final DiscardReport report = this.pipeline.discard(prepDir).join();
        return DiscardCommand.discarded(new JobReports.Finished<>(report, false));
    }

    /**
     * Which run this call was asked to answer for.
     *
     * @return {@link Path} the run's own folder
     * @throws ScopeRefusedException when the address names no sift on disk
     */
    private Path folder() {
        return this.address.folderFor(this.run);
    }

    /**
     * The key this call was asked to answer.
     *
     * @return {@link String} the key
     */
    private String keyArg() {
        return Objects.requireNonNull(this.key, "picocli refuses a missing positional before this runs");
    }

    /**
     * The option this call chose.
     *
     * @return {@link String} the option
     */
    private String optionArg() {
        return Objects.requireNonNull(this.option, "picocli refuses a missing positional before this runs");
    }

    /**
     * Every finding currently open on the run, read off a fresh sweep of the sift-prep root.
     *
     * @param prepDir {@link Path} the run
     * @return a {@link List} of {@link Finding} its open findings
     * @throws ScopeRefusedException when the run is not among those found
     */
    private List<Finding> openFindings(final Path prepDir) {
        return switch (this.pipeline.cullRuns()) {
            case CullRuns.Listed(final List<CullRunSummary> runs) -> runs.stream()
                    .filter(candidate -> candidate.prepDir().equals(prepDir))
                    .findFirst()
                    .map(candidate -> candidate.health().findings())
                    .orElseThrow(() -> new ScopeRefusedException(new Refusal(RefusalKind.RUN_NOT_FOUND,
                            "No sift at " + prepDir + ".",
                            Fields.of("prepDir", prepDir.toString()))));
            case CullRuns.Unlistable(final Path root) -> throw new ScopeRefusedException(
                    RunsRefusals.unreadable(root));
        };
    }
}
