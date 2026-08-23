package photos.sluice.adapter.cli;

import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

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
        // Explicitly, rather than trusting the parser to have done it. A writer left unflushed
        // reads as a command that printed nothing, which is what half these tests assert.
        outWriter.flush();
        errWriter.flush();
        return new Result(exitCode, out.toString(), err.toString());
    }
}
