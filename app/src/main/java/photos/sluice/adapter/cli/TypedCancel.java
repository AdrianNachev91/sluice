package photos.sluice.adapter.cli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.service.JobHandle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Stops a running job when the caller types {@code c}.
 *
 * <p>A typed line rather than Ctrl-C, because a reader expects Ctrl-C to end the program and it
 * still does. Stopping a run is a different act: the work stops, the run says what it did, and the
 * process leaves with a code naming that.
 *
 * <p>A line also reaches this surface from either kind of caller. A person at a terminal types it,
 * and an agent driving a command writes it to that process's own input.
 *
 * <p>Nothing waits on the input and nothing gates on it. A command given an empty or closed input
 * runs exactly as it would with no reader at all. That is what keeps this inside the rule that this
 * surface never prompts.
 *
 * <p>Only the line {@code c} counts, and anything else is read and dropped. So what a caller has to
 * avoid piping into a run is that one line, rather than input in general.
 */
@Component
@Profile("cli")
public class TypedCancel {

    /**
     * The line that stops a job.
     */
    public static final String CANCEL = "c";

    /**
     * What a person watching a run is told, once, before it starts.
     */
    public static final String HINT = "Type c and press Enter to stop.";

    /**
     * The invisible character a Windows shell puts at the head of what it sends.
     */
    private static final String BYTE_ORDER_MARK = Character.toString(0xFEFF);

    // Names no unit, because every verb routed through here stops between a different one.
    private static final String STOPPING =
            "Stopping. It finishes what it is on, then says what it did.";

    private static final String STOPPING_NOW =
            "Stopping now. A file still being copied is abandoned rather than finished.";

    private final InputStream input;
    private final ConsoleProgressPort progress;

    /**
     * Creates the reader over the process's own input.
     *
     * <p>Explicit {@code @Autowired}: Spring's implicit single-constructor injection only applies
     * to a class with exactly one constructor. The second constructor makes two, so this one
     * has to be named as the one to build from.
     *
     * @param progress {@link ConsoleProgressPort} owns the stream these notes share with progress
     */
    @Autowired
    public TypedCancel(final ConsoleProgressPort progress) {
        this(System.in, progress);
    }

    /**
     * Creates the reader over a given input.
     *
     * @param input {@link InputStream} where the typed lines arrive
     * @param progress {@link ConsoleProgressPort} owns the stream these notes share with progress
     */
    TypedCancel(final InputStream input, final ConsoleProgressPort progress) {
        this.input = input;
        this.progress = progress;
    }

    /**
     * Watches for a typed cancel until the returned watch is closed.
     *
     * <p>The reading thread is a daemon and is never interrupted. A read of a terminal's input
     * cannot be cancelled portably, and a one-shot process has nothing left to tidy up. Closing
     * stops this acting on anything read afterwards, and the thread goes when the process does.
     *
     * @param job a {@link JobHandle} the running job to stop
     * @return {@link Watch} closed once the job has finished
     */
    public Watch watch(final JobHandle<?> job) {
        final Watch watch = new Watch();
        final Thread reader = new Thread(() -> this.read(job, watch), "sluice-cancel");
        reader.setDaemon(true);
        reader.start();
        return watch;
    }

    /**
     * Reads until the input ends, acting on each cancel.
     *
     * @param job a {@link JobHandle} the running job to stop
     * @param watch {@link Watch} open while the job is still running
     */
    private void read(final JobHandle<?> job, final Watch watch) {
        int asked = 0;
        try (final var lines = new BufferedReader(new InputStreamReader(this.input, Charset.defaultCharset()))) {
            String line = lines.readLine();
            while (line != null && watch.isOpen()) {
                if (CANCEL.equals(typed(line))) {
                    asked++;
                    this.cancel(job, asked);
                }
                line = lines.readLine();
            }
        } catch (final IOException ended) {
            // An input that stops answering ends the reading and nothing else. The job carries on,
            // and a caller who cannot type has asked for nothing.
        }
    }

    /**
     * One typed line, as it is compared against the word that cancels.
     *
     * <p>A byte-order mark is dropped along with the blanks around it. Windows shells write one at
     * the head of what they send, and Java's own readers hand it straight through. Left in, it
     * makes a correctly typed cancel do nothing at all, with no sign of why.
     *
     * @param line {@link String} the line as it arrived
     * @return {@link String} the word it amounts to
     */
    private static String typed(final String line) {
        return line.replace(BYTE_ORDER_MARK, "").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Acts on one cancel.
     *
     * <p>The first asks the run to stop between files. The second gives up on a file already being
     * copied, rather than waiting for it to finish. A third and any after it repeat the second,
     * there being nothing further to escalate to.
     *
     * @param job a {@link JobHandle} the running job to stop
     * @param asked int how many cancels have arrived, this one included
     */
    private void cancel(final JobHandle<?> job, final int asked) {
        if (asked == 1) {
            job.requestCancellation();
            this.progress.note(STOPPING);
            return;
        }
        job.requestAbandon();
        this.progress.note(STOPPING_NOW);
    }

    /**
     * One job's watch, open for as long as that job is running.
     */
    public static final class Watch implements AutoCloseable {

        // Set by the command thread when its job ends, read by the reading thread between lines.
        private volatile boolean open = true;

        /**
         * Whether the job this watches is still running.
         *
         * @return boolean true while it is
         */
        boolean isOpen() {
            return this.open;
        }

        /**
         * Stops this acting on anything typed from now on.
         */
        @Override
        public void close() {
            this.open = false;
        }
    }
}
