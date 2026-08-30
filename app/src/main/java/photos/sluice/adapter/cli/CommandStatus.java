package photos.sluice.adapter.cli;

import picocli.CommandLine;

/**
 * How a command ended, and the exit code that says so.
 *
 * <p>A script branches on the code, and everything finer sits in the document the command wrote.
 * One refusal code covers every reason a command can refuse, and the document carries which reason
 * it was.
 *
 * <p>Arguments the parser could not understand have no value here. Nothing classified an outcome at
 * that point, so no document is written and the parser's own code is what the process leaves with.
 */
public enum CommandStatus {

    /**
     * The command did what it was asked.
     */
    DONE(CommandLine.ExitCode.OK),

    /**
     * Something failed that this app has no reading for.
     */
    FAILED(CommandLine.ExitCode.SOFTWARE),

    /**
     * The command was refused and nothing happened.
     */
    REFUSED(3),

    /**
     * The run paused with work left.
     */
    WAITING(4),

    /**
     * The run needs a look before it can go on, and it left something behind to look at.
     *
     * <p>Two shapes reach it. Every sheet came back and validation refused them anyway. Or the
     * provider gave up part way, having already been billed for what it did send. Both leave a run
     * on disk in a state a reader has to decide about, which is what separates this from a refusal.
     */
    BLOCKED(5),

    /**
     * The user stopped it.
     */
    CANCELLED(6);

    private final int exitCode;

    /**
     * Pairs a status with its exit code.
     *
     * @param exitCode int the code the process leaves with
     */
    CommandStatus(final int exitCode) {
        this.exitCode = exitCode;
    }

    /**
     * The code the process leaves with.
     *
     * @return int the exit code
     */
    public int exitCode() {
        return this.exitCode;
    }
}
