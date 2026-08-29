package photos.sluice.adapter.cli;

import photos.sluice.application.service.Pipeline;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

// Runs a command the way a shell would and hands back everything a shell would see. In this
// process, so a test names the command object it is driving and reads what came out of it.
//
// The two streams are swapped on the parser rather than on the JVM. System.out and System.err are
// process-wide, and the suite runs its tests in one process, so replacing them would leak into
// whatever ran next. Every command writes through the parser's own streams for that reason.
//
// Colour is turned off rather than left to the parser's own guess. It styles its output whenever it
// believes something is watching, and what it believes depends on the terminal the build was
// started from. Left alone, the same assertion passes in one runner and fails in the next against
// escape codes nothing prints visibly.
final class CliHarness {

    private CliHarness() {
    }

    // What one invocation left behind.
    record Result(int exitCode, String out, String err) {
    }

    // Verbs are registered by class rather than as instances, which is what a real run does. So the
    // parser constructs every one of them while it builds the command tree. A test supplying none
    // cannot build that tree at all, whether or not it means to run a verb.
    static CommandLine parser(final Object... driven) {
        final List<Object> commands = new ArrayList<>(List.of(driven));
        commands.add(new RunsCommand(mock(Pipeline.class), mock(CommandReports.class)));
        commands.add(new SortCommand(mock(Pipeline.class), mock(JobReports.class)));
        return SluiceCli.parser(new SluiceCli(), supplying(commands.toArray()));
    }

    // Hands the parser one of these where it asks for a type it recognises, and lets it build
    // anything else itself.
    static CommandLine.IFactory supplying(final Object... commands) {
        return new CommandLine.IFactory() {
            @Override
            public <K> K create(final Class<K> type) throws Exception {
                for (final Object command : commands) {
                    if (type.isInstance(command)) {
                        return type.cast(command);
                    }
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
    }

    static Result run(final CommandLine commandLine, final String... args) {
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final PrintWriter outWriter = new PrintWriter(out);
        final PrintWriter errWriter = new PrintWriter(err);
        final int exitCode = commandLine
                .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF))
                .setOut(outWriter)
                .setErr(errWriter)
                .execute(args);
        outWriter.flush();
        errWriter.flush();
        return new Result(exitCode, out.toString(), err.toString());
    }
}
