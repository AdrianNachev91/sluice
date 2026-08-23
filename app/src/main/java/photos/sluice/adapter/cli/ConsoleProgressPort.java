package photos.sluice.adapter.cli;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Reports a running job's progress on the error stream, one line per event.
 *
 * <p>The error stream rather than the output one, because the output stream carries the command's
 * result. A run piped into another program then arrives as the result, while the person watching it
 * still sees what it is doing. Progress joins the framework's own log output there, which
 * {@code logback-spring.xml} moves off the output stream for this profile.
 *
 * <p>Counts are grouped in threes against {@link Locale#ROOT} rather than the machine's locale.
 * Every word this app prints is English, and a number punctuated one way beside sentences written
 * the other reads as a mistake.
 */
@Component
@Profile("cli")
public class ConsoleProgressPort implements ProgressPort {

    private final PrintStream stream;

    /**
     * Creates the reporter over the process's own error stream.
     */
    public ConsoleProgressPort() {
        this(System.err);
    }

    /**
     * Creates the reporter over a given stream.
     *
     * @param stream {@link PrintStream} where progress is written
     */
    ConsoleProgressPort(final PrintStream stream) {
        this.stream = stream;
    }

    /**
     * Announces a phase.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseStarted(final String phase) {
        this.stream.println(phase);
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
        this.stream.println(phase + " " + grouped(current) + "/" + grouped(total));
    }

    /**
     * Reports a phase as finished.
     *
     * @param phase {@link String} the phase name
     */
    @Override
    public void phaseFinished(final String phase) {
        this.stream.println(phase + " done");
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
