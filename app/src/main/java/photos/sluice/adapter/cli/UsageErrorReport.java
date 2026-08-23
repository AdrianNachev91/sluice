package photos.sluice.adapter.cli;

import picocli.CommandLine;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;

/**
 * What a user is told when the arguments could not be understood: what was wrong, then where to
 * look.
 *
 * <p>The parser's own answer repeats the whole usage block under the error. That buries the one
 * line explaining the mistake beneath a list nobody asked for, and the list grows with every verb
 * added. A pointer costs one line and stays one line.
 *
 * <p>Both lines go to the error stream, since neither is the result of a command.
 */
public final class UsageErrorReport implements IParameterExceptionHandler {

    /**
     * Reports arguments the parser could not make sense of.
     *
     * @param ex {@link ParameterException} what the parser refused, and why
     * @param args {@link String}[] the arguments as given
     * @return int the exit code for arguments that were not understood
     */
    @Override
    public int handleParseException(final ParameterException ex, final String[] args) {
        report(ex.getCommandLine(), ex.getMessage());
        return ex.getCommandLine().getCommandSpec().exitCodeOnInvalidInput();
    }

    /**
     * Writes both lines of a refusal: what was wrong, then where to look.
     *
     * @param command {@link CommandLine} the command the user was trying to run
     * @param problem {@link String} what was wrong
     */
    static void report(final CommandLine command, final String problem) {
        command.getErr().println(problem);
        command.getErr().println(hint(command));
    }

    /**
     * Where to look, worded the way command-line tools have worded it for decades.
     *
     * @param command {@link CommandLine} the command the user was trying to run
     * @return {@link String} the line pointing at the help
     */
    static String hint(final CommandLine command) {
        return "Try '" + command.getCommandSpec().qualifiedName() + " --help' for more information.";
    }
}
