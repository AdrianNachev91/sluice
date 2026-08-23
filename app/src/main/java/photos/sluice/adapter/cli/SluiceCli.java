package photos.sluice.adapter.cli;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * The command a user types. Every verb hangs off this one as a subcommand, so a single parser
 * covers the whole surface. Its help text generates from the same annotations that parser reads.
 *
 * <p>Reached only when the user passed arguments. Sluice with none opens the desktop window
 * instead, which is why nothing here has to mean "no command given, so show the app".
 */
@Component
@Profile("cli")
@Command(name = "sluice", description = "Sluice organises your photos and videos.")
public class SluiceCli implements Callable<Integer> {

    // Both fields are filled in by the parser rather than by construction, which is why neither is
    // final and neither is read anywhere in this class. The spec is the only route from a command
    // back to the parse that ran it. The help option exists to be declared: what it does is turn
    // --help into help, which the parser handles before this class is reached.
    @Spec
    @SuppressWarnings("unused")
    private @Nullable CommandSpec spec;

    // Declared rather than taken from the parser's standard mixin, which pairs --help with a
    // --version that has no version to print until the packaged build supplies one.
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this message.")
    @SuppressWarnings("unused")
    private boolean helpRequested;

    /**
     * Builds the parser for this surface, configured the one way every verb inherits.
     *
     * <p>Here rather than at the launcher, so a test drives the same parser a user does. What it
     * configures is how a refusal reads, which is the part no test would notice going wrong.
     *
     * @param root {@link SluiceCli} the command every verb hangs off
     * @param factory {@link CommandLine.IFactory} builds each subcommand
     * @return {@link CommandLine} the parser
     */
    public static CommandLine parser(final SluiceCli root, final CommandLine.IFactory factory) {
        return new CommandLine(root, factory).setParameterExceptionHandler(new UsageErrorReport());
    }

    /**
     * Answers arguments that named no command.
     *
     * <p>A usage error rather than a request for help, so it goes to the error stream and the exit
     * code says the arguments were not understood. It points at the help rather than reprinting it,
     * matching what a mistyped argument gets.
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
