package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.startup.StartupFailure;
import photos.sluice.application.startup.StartupFailure.ConfigPosition;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;

/**
 * Reports a failure that stopped the app before any command could run.
 *
 * <p>The commonest of these come from a config file the user edited by hand, so they are the most
 * answerable failures this app has. Left to the framework they arrive as a stack trace with no
 * document at all, which is what a scripted caller can do nothing with. Named properly, somebody
 * who can open a text editor is one edit away.
 *
 * <p>It writes the same two shapes every command writes. A caller cannot be expected to parse one
 * thing when the app started and another when it did not. What it cannot fill in is the verb, since
 * nothing had parsed one yet.
 *
 * <p>It is built directly rather than injected. There is no context to inject from: that is what
 * failed.
 */
public final class StartupFailureReport {

    private final DocumentWriter writer;

    /**
     * Creates the report over the process's own two streams.
     *
     * @param out {@link PrintStream} the output stream
     * @param err {@link PrintStream} the error stream
     * @param asDocument boolean whether the arguments asked for a machine-readable document
     */
    public StartupFailureReport(final PrintStream out, final PrintStream err, final boolean asDocument) {
        this.writer = new DocumentWriter(new PrintWriter(out, true), new PrintWriter(err, true), asDocument,
                JsonMapper.builder().build());
    }

    /**
     * Writes the failure and answers with the code the process leaves with.
     *
     * @param failure {@link StartupFailure} what stopped the app starting
     * @return int the code the process leaves with
     */
    public int write(final StartupFailure failure) {
        return this.writer.write(null, outcomeOf(failure));
    }

    /**
     * What one startup failure amounts to.
     *
     * @param failure {@link StartupFailure} what stopped the app starting
     * @return {@link CommandOutcome} the outcome to report
     */
    private static CommandOutcome outcomeOf(final StartupFailure failure) {
        return switch (failure) {
            case final StartupFailure.WorkingRootBusy busy -> CommandOutcome.refused(new Refusal(
                    RefusalKind.WORKING_ROOT_BUSY,
                    "Another Sluice process is already running: close it and try again.",
                    Fields.of("workingRoot", busy.workingRoot().toString())));
            case final StartupFailure.RejectedSetting rejected -> CommandOutcome.refused(rejectedSetting(rejected));
            case final StartupFailure.UnparsableConfigFile broken -> CommandOutcome.refused(unparsable(broken));
            case final StartupFailure.UnusableSettings unusable -> CommandOutcome.refused(unusable(unusable));
            case final StartupFailure.Unclassified unclassified -> unclassified(unclassified);
        };
    }

    /**
     * The refusal for a setting the app would not accept.
     *
     * @param rejected {@link StartupFailure.RejectedSetting} the setting and where it came from
     * @return {@link Refusal} the refusal
     */
    private static Refusal rejectedSetting(final StartupFailure.RejectedSetting rejected) {
        final List<String> sentence = new ArrayList<>();
        sentence.add("Sluice will not start: the setting " + rejected.property() + " holds a value it cannot use.");
        sentence.add(whereToLook(rejected.spot()));
        final SequencedMap<String, Object> detail = Fields.of("property", rejected.property());
        detail.putAll(place(rejected.spot()));
        return new Refusal(RefusalKind.SETTING_REJECTED, Refusal.sentences(sentence), detail);
    }

    /**
     * The refusal for a config file the parser gave up on.
     *
     * @param broken {@link StartupFailure.UnparsableConfigFile} the file and where parsing stopped
     * @return {@link Refusal} the refusal
     */
    private static Refusal unparsable(final StartupFailure.UnparsableConfigFile broken) {
        final SequencedMap<String, Object> detail = Fields.of("problem", broken.problem());
        detail.putAll(place(broken.spot()));
        return new Refusal(RefusalKind.CONFIG_FILE_UNPARSABLE,
                Refusal.sentences(List.of(
                        "Sluice will not start: " + broken.spot().file() + " is not valid YAML.",
                        broken.problem() + at(broken.spot().position()) + ".",
                        "Nothing in the file was read.")),
                detail);
    }

    /**
     * The refusal for settings the app will not run on.
     *
     * <p>The refusal's own sentence is carried through rather than reworded. It is the only thing
     * that names what is wrong, and nothing here knows more about it than that.
     *
     * @param unusable {@link StartupFailure.UnusableSettings} the refusal and its wording
     * @return {@link Refusal} the refusal
     */
    private static Refusal unusable(final StartupFailure.UnusableSettings unusable) {
        return new Refusal(RefusalKind.SETTINGS_UNUSABLE,
                Refusal.sentences(List.of("Sluice will not start.", unusable.problem())),
                Fields.of("problem", unusable.problem()));
    }

    /**
     * The outcome for a failure nothing classified.
     *
     * <p>Not a refusal. Refusing says the app understood and declined, and nothing here understood
     * anything. So it takes the unexpected-failure code.
     *
     * @param unclassified {@link StartupFailure.Unclassified} the failure and its trace
     * @return {@link CommandOutcome} the outcome
     */
    private static CommandOutcome unclassified(final StartupFailure.Unclassified unclassified) {
        return new CommandOutcome(CommandStatus.FAILED,
                Fields.of("trace", unclassified.trace()), List.of(),
                List.of("Sluice will not start, and could not say why.", unclassified.trace()));
    }

    /**
     * Where to go and look for the offending value.
     *
     * <p>A value that did not come from the user's own config file cannot be placed any more
     * precisely than this. Once classified, the app cannot tell an environment variable, an
     * argument on this run, another settings file on the path and its own bundled defaults apart.
     * So the sentence says where to look rather than asserting which one it was.
     *
     * @param spot {@link ConfigSpot} the file and position, or null when the value came from elsewhere
     * @return {@link String} the sentence
     */
    private static String whereToLook(final @Nullable ConfigSpot spot) {
        return spot == null
                ? "It did not come from your config file. Check the environment, this run's own arguments, and any"
                        + " other settings file Sluice was pointed at."
                : "It is in " + spot.file() + at(spot.position()) + ".";
    }

    /**
     * Where in a file something sits, worded to be dropped straight into a sentence.
     *
     * @param position {@link ConfigPosition} the line and column, or null when only the file is known
     * @return {@link String} the clause, empty when there is no position
     */
    private static String at(final @Nullable ConfigPosition position) {
        return position == null ? "" : ", line " + position.line() + ", column " + position.column();
    }

    /**
     * The file and position as the document names them, or nothing when neither is known.
     *
     * @param spot {@link ConfigSpot} the file and position, or null
     * @return a {@link SequencedMap} of {@link String} to {@link Object} the fields
     */
    private static SequencedMap<String, Object> place(final @Nullable ConfigSpot spot) {
        if (spot == null) {
            return Fields.of();
        }
        final ConfigPosition position = spot.position();
        return position == null
                ? Fields.of("file", spot.file().toString())
                : Fields.of("file", spot.file().toString(), "line", position.line(),
                        "column", position.column());
    }
}
