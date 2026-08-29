package photos.sluice.adapter.cli;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Decides which of a phase's progress events are worth writing.
 *
 * <p>Events arrive one per file, tens of thousands of them over a full Inbox. Written out one for
 * one, a redrawn counter flickers and a piped run buries its own answer under its progress.
 *
 * <p>Two events are written whatever the pace says: a phase's first, and the one that reaches its
 * total. Without the first, a short phase can finish having shown nothing. Without the one that
 * reaches the total, the figure a reader is left looking at is whichever happened to fall on the
 * interval.
 *
 * <p>A phase that stops early reaches no total, so what it leaves on screen is a count up to one
 * interval behind where it stopped. That is the case a cancel produces.
 *
 * <p>Not thread-safe. Its state is plain fields, so two phases reported at once would race.
 */
final class ProgressPace {

    /**
     * How often a redrawn counter is allowed to change.
     *
     * <p>Ten a second reads as a live count rather than a stuck one. It also holds a sort of
     * sixteen thousand files to a few hundred redraws instead of sixteen thousand.
     */
    static final Duration REDRAWN = Duration.ofMillis(100);

    /**
     * How often a plain line is allowed to be written.
     *
     * <p>Often enough to show a long run is still alive. Rare enough that ten minutes of sorting
     * leaves a few hundred lines in a log rather than one per file.
     */
    static final Duration PLAIN = Duration.ofSeconds(2);

    private final long interval;
    private final LongSupplier now;

    private boolean written;
    private long lastWritten;

    /**
     * Creates the pace over the machine's own clock.
     *
     * @param interval {@link Duration} how often an event may be written
     */
    ProgressPace(final Duration interval) {
        this(interval, System::nanoTime);
    }

    /**
     * Creates the pace over a given clock.
     *
     * @param interval {@link Duration} how often an event may be written
     * @param now a {@link LongSupplier} the current moment, in nanoseconds
     */
    ProgressPace(final Duration interval, final LongSupplier now) {
        this.interval = interval.toNanos();
        this.now = now;
    }

    /**
     * Starts a phase, so its first event is written whatever the last phase did.
     */
    void phaseStarted() {
        this.written = false;
    }

    /**
     * Whether this event is written.
     *
     * @param current int the units done so far
     * @param total int the units in this phase
     * @return boolean true when it should be written
     */
    boolean due(final int current, final int total) {
        final long moment = this.now.getAsLong();
        if (this.written && current < total && moment - this.lastWritten < this.interval) {
            return false;
        }
        this.written = true;
        this.lastWritten = moment;
        return true;
    }
}
