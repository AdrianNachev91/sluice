package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

import java.io.Console;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Reports a running job's progress on the error stream.
 *
 * <p>The error stream, because the output stream carries the command's result and nothing else.
 *
 * <p>Two shapes. On a terminal one line is redrawn in place, so a long run occupies a single row.
 * Anywhere else the same counts are written as ordinary lines, because a carriage return turns a
 * log file into one unreadable line.
 *
 * <p>Counts are grouped in threes against {@link Locale#ROOT} rather than the machine's locale.
 * Every word this app prints is English, and a number punctuated one way beside sentences written
 * the other reads as a mistake.
 */
@Component
@Profile("cli")
public class ConsoleProgressPort implements ProgressPort {

    private final PrintStream stream;
    private final boolean redrawn;
    private final ProgressPace pace;

    // Written by the command thread before a job starts, and read by the job's own thread as it
    // reports. Volatile is what gives those two the happens-before.
    private volatile boolean quiet;

    // Set by phaseCutShort and read by the phaseFinished that follows it. Both arrive from the job's
    // own thread, in that order, so this one is not shared across threads.
    private boolean cutShort;

    // Two threads write this stream: the job reporting its progress, and the cancel reader saying
    // it heard a caller. Each write is several calls, so without one lock a note lands partway
    // along a row of counts. It also guards the width below, which both of them read and set.
    private final Object writing = new Object();

    // How wide the redrawn line was last time. A shorter line drawn over a longer one leaves the
    // tail of the longer one behind, so what is not overwritten is blanked.
    private int drawn;

    /**
     * Creates the reporter over the process's own error stream, drawing whichever shape this
     * process is attached to.
     */
    public ConsoleProgressPort() {
        this(System.err, drawnForAPerson());
    }

    /**
     * Creates the reporter over a given stream, in a given shape.
     *
     * @param stream {@link PrintStream} where progress is written
     * @param redrawn boolean whether one line is redrawn in place
     */
    ConsoleProgressPort(final PrintStream stream, final boolean redrawn) {
        this(stream, redrawn, new ProgressPace(redrawn ? ProgressPace.REDRAWN : ProgressPace.PLAIN));
    }

    /**
     * Creates the reporter over a given stream, in a given shape, at a given pace.
     *
     * @param stream {@link PrintStream} where progress is written
     * @param redrawn boolean whether one line is redrawn in place
     * @param pace {@link ProgressPace} decides which events are written
     */
    ConsoleProgressPort(final PrintStream stream, final boolean redrawn, final ProgressPace pace) {
        this.stream = stream;
        this.redrawn = redrawn;
        this.pace = pace;
    }

    /**
     * Turns progress reporting off, or back on.
     *
     * @param quiet boolean true to report nothing at all
     */
    public void quiet(final boolean quiet) {
        this.quiet = quiet;
    }

    /**
     * Whether this process is drawing for somebody watching it.
     *
     * @return boolean true when progress is being redrawn on a terminal
     */
    public boolean watched() {
        return this.redrawn;
    }

    /**
     * Writes one line about the running job, on the stream progress is using.
     *
     * <p>Progress may have a row open on this stream, and only this knows how wide it is. A line
     * written anywhere else lands halfway along a row of counts.
     *
     * <p>Written even when progress is off, since what a caller silenced is the reporting rather
     * than the answer to something they asked for.
     *
     * @param note {@link String} the line
     */
    public void note(final String note) {
        synchronized (this.writing) {
            this.endAnyOpenRow();
            this.stream.println(note);
            this.stream.flush();
        }
    }

    /**
     * Announces a phase.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseStarted(final String phase) {
        this.pace.phaseStarted();
        if (this.quiet) {
            return;
        }
        this.write(phase);
    }

    /**
     * Reports how far through a phase the job is.
     *
     * @param phase {@link String} the phase name
     * @param current int the units done so far
     * @param total int the units in this phase
     */
    @Override
    public void tick(final String phase, final int current, final int total) {
        if (this.quiet || !this.pace.due(current, total)) {
            return;
        }
        this.write(phase + " " + grouped(current) + "/" + grouped(total));
    }

    /**
     * Records that the running phase gave up part way, so the line closing it does not say done.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseCutShort(final String phase) {
        this.cutShort = true;
    }

    /**
     * Reports a phase as finished.
     *
     * <p>The redrawn line is ended here rather than left open. That starts the next phase on a row
     * of its own, and leaves the last count this one reached on screen.
     *
     * <p>A phase cut short is closed silently. "Done" belongs to a phase that worked through to its
     * end.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseFinished(final String phase) {
        final boolean gaveUp = this.cutShort;
        this.cutShort = false;
        if (this.quiet) {
            return;
        }
        synchronized (this.writing) {
            if (this.redrawn) {
                this.endAnyOpenRow();
            } else if (!gaveUp) {
                this.stream.println(phase + " done");
            }
        }
    }

    /**
     * Writes one progress line, in whichever shape this process draws.
     *
     * @param line {@link String} what it says
     */
    private void write(final String line) {
        synchronized (this.writing) {
            if (this.redrawn) {
                this.stream.print("\r" + line + " ".repeat(Math.max(0, this.drawn - line.length())));
                this.stream.flush();
                this.drawn = line.length();
            } else {
                this.stream.println(line);
            }
        }
    }

    /**
     * Ends the redrawn row, where one is open.
     *
     * @implNote the caller holds the write lock
     */
    private void endAnyOpenRow() {
        if (this.drawn > 0) {
            this.stream.println();
            this.drawn = 0;
        }
    }

    /**
     * Whether somebody is watching this run as it goes.
     *
     * <p>Asks the console whether it is a terminal rather than only whether one exists. A console
     * can be handed back for a run whose streams are redirected. Drawing over a row in that case
     * writes carriage returns into whatever is collecting the output.
     *
     * @return boolean true when progress should be redrawn in place
     */
    private static boolean drawnForAPerson() {
        final Console console = System.console();
        return console != null && console.isTerminal();
    }

    /**
     * Punctuates a count in threes.
     *
     * @param count int the count
     * @return {@link String} the count, written out
     */
    private static String grouped(final int count) {
        return String.format(Locale.ROOT, "%,d", count);
    }
}
