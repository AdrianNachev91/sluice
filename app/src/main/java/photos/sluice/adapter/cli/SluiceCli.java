package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * The command a user types. Every verb hangs off this one as a subcommand, so a single parser
 * covers the whole surface. Its help text generates from the same annotations that parser reads.
 *
 * <p>Reached whenever this process is not opening the window, which includes the empty invocation:
 * that arrives here as a request for help.
 */
@Component
@Profile("cli")
@Command(name = "sluice", description = "Sluice organises your photos and videos.",
        versionProvider = SluiceVersionProvider.class,
        subcommands = {AppCommand.class, RunsCommand.class, SortCommand.class, CommitCommand.class,
                RescueCommand.class, CullCommand.class, ResumeCommand.class, ImportCommand.class,
                TroubleshootCommand.class, AnswerCommand.class, DiscardCommand.class, PurgeCommand.class,
                RedoCommand.class})
public class SluiceCli implements Callable<Integer> {

    /**
     * The flag asking for a machine-readable document.
     */
    private static final String DOCUMENT_FLAG = "--json";

    /**
     * Where the parser stops treating arguments as options.
     */
    private static final String END_OF_OPTIONS = "--";

    // Filled in by the parser rather than by construction, which is why it is not final. It is the
    // only route from a command back to the parse that ran it.
    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    // Declared rather than taken from the parser's standard mixin, which pairs the two flags as one
    // unit with no choice over either's behaviour. The parser turns --help into help before this
    // class is reached, so nothing here ever reads the field.
    //
    // Inherited, so every verb answers it. The two-line refusal a verb gives an unknown option ends
    // by naming that verb's own --help. A verb that does not take one therefore offers a remedy
    // answering with the same refusal.
    @Option(names = {"-h", "--help"}, usageHelp = true, scope = ScopeType.INHERIT,
            description = "Show this message.")
    @SuppressWarnings("unused")
    private boolean helpRequested;

    // Not inherited, unlike --help. The version strings come from this command's own
    // versionProvider, an attribute a verb's own @Command does not carry down from here. Inheriting
    // the flag without inheriting that would make "sort --version" succeed with nothing printed,
    // which reads worse than a verb refusing an option it does not take.
    @Option(names = "--version", versionHelp = true, description = "Show which build this is.")
    @SuppressWarnings("unused")
    private boolean versionRequested;

    // Inherited, so it reads the same before the verb as after it. A caller that has to remember
    // where a global flag goes gets it wrong from a shell history entry sooner or later.
    //
    // The arity is stated rather than left to the parser. Unstated, a flag also answers to
    // --json=true, which documentAsked(String[]) cannot see: it reads the arguments without a
    // parser, on the one path where the parser never ran. Stating the arity turns that spelling
    // into a usage error, so the two readings agree by construction.
    @Option(names = "--json", scope = ScopeType.INHERIT, arity = "0",
            description = "Write the result to the output stream as one JSON document.")
    @SuppressWarnings("unused")
    private boolean documentAsked;

    // Inherited, so it reads the same before the verb as after it. That reaches the verbs which
    // report no progress too, where it does nothing. A caller would otherwise have to know which
    // verbs are long-running before deciding where the flag goes.
    @Option(names = "--quiet", scope = ScopeType.INHERIT, arity = "0",
            description = "Report no progress while the command runs. The result is unaffected.")
    @SuppressWarnings("unused")
    private boolean quietAsked;

    /**
     * Builds the parser for this surface, configured the one way every verb inherits.
     *
     * <p>Here rather than at the launcher, so a test drives the same parser a user does.
     *
     * @param root {@link SluiceCli} the command every verb hangs off
     * @param factory {@link CommandLine.IFactory} builds each subcommand
     * @return {@link CommandLine} the parser
     */
    public static CommandLine parser(final SluiceCli root, final CommandLine.IFactory factory) {
        return new CommandLine(root, factory).setParameterExceptionHandler(new UsageErrorReport());
    }

    /**
     * Whether the running command was asked for a machine-readable document.
     *
     * <p>The flag is inherited, so a verb may be given it and the value still lands on this one
     * object. A verb reading its own field would find it unset whenever the flag was typed before
     * the verb.
     *
     * @param spec {@link CommandSpec} the running command
     * @return boolean true when a document was asked for
     */
    static boolean documentAsked(final CommandSpec spec) {
        return ((SluiceCli) spec.root().userObject()).documentAsked;
    }

    /**
     * Whether the running command was asked to report no progress.
     *
     * @param spec {@link CommandSpec} the running command
     * @return boolean true when progress was turned off
     */
    static boolean quietAsked(final CommandSpec spec) {
        return ((SluiceCli) spec.root().userObject()).quietAsked;
    }

    /**
     * Whether an argument list asks for a machine-readable document, read without a parser.
     *
     * <p>For the one failure that happens before there is a parser to ask. A config file the app
     * cannot start on stops it during preparation, and the caller still has to be answered in the
     * shape it asked for.
     *
     * <p>It stops where the parser stops treating arguments as options. What follows is a value
     * somebody is insisting on, and a value that happens to read as this flag is not one.
     *
     * @param args {@link String}[] the command-line arguments
     * @return boolean true when a document was asked for
     */
    public static boolean documentAsked(final String[] args) {
        for (final String arg : args) {
            if (END_OF_OPTIONS.equals(arg)) {
                return false;
            }
            if (DOCUMENT_FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Answers arguments that named no command.
     *
     * <p>A usage error rather than a request for help, so it goes to the error stream and the exit
     * code says the arguments were not understood. It points at the help rather than reprinting it.
     *
     * @return {@link Integer} the exit code
     */
    @Override
    public Integer call() {
        final CommandLine commandLine = Objects.requireNonNull(this.spec,
                "the parser fills this in before it runs a command").commandLine();
        UsageErrorReport.report(commandLine, "No command given.");
        return CommandLine.ExitCode.USAGE;
    }
}
