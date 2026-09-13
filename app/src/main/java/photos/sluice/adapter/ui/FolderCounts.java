package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.PrepDirHealth.State;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What the Inbox, Sorted and runs folders hold, and whether that answer is in yet.
 *
 * <p>Every reading walks all three and blocks while it does, so a caller runs {@link #refresh} and
 * {@link #refreshUnprompted} off whatever thread paints.
 *
 * <p>An empty answer means three different things: the folder is empty, the walk has not got there
 * yet, and the walk failed. {@link #countsAvailable} is what tells them apart, and anything refusing on
 * a count has to ask it first.
 */
class FolderCounts {

    private static final Logger log = LoggerFactory.getLogger(FolderCounts.class);

    // How long a folder read is given before the screen says it is reading. Under this, a reader
    // sees one state rather than three. Over it, they are waiting and want to know why.
    private static final long READING_SETTLE_WINDOW = 200;

    private final Pipeline pipeline;
    private final Runnable repaint;

    // Volatile throughout: written by the read, and looked at while somebody types.
    private volatile @Nullable InboxTally inbox;
    private volatile @Nullable SortedTally sorted;
    private volatile boolean unreadable;
    private volatile List<SiftRunSummary> unfinished = List.of();

    // How many asked-for reads are somewhere between being asked for and finishing their walk. A
    // count rather than a flag, because two of them can overlap. A boolean the first cleared on its
    // way out would report the second's walk as over.
    private final AtomicInteger askedReads = new AtomicInteger();

    private volatile boolean wasReadAtLeastOnce;

    private final ReentrantLock readLock = new ReentrantLock();

    /**
     * Creates the reading over the facade it walks, and the way to draw the screen again.
     *
     * @param pipeline {@link Pipeline} the one way in to every engine
     * @param repaint {@link Runnable} draws the launcher again, on whichever screen is up
     */
    FolderCounts(final Pipeline pipeline, final Runnable repaint) {
        this.pipeline = pipeline;
        this.repaint = repaint;
    }

    /**
     * Reads what is in the Inbox, what is staged in Sorted and what sifts are on disk.
     *
     * <p>A refusal from the facade is kept rather than thrown on, and reported through
     * {@link #unreadable}.
     *
     * <p>Counts itself in before it takes the lock and out once its own walk has ended, so
     * {@link #isCounting} covers its wait as well as its walk. A read queued behind a walk already
     * running would otherwise read as finished for the rest of that walk.
     */
    void refresh() {
        this.read(true);
    }

    /**
     * Reads the same three folders for a caller nobody asked for an answer from.
     *
     * <p>Counts nothing towards {@link #isCounting}, so a poll cannot hold that answer true every
     * few seconds. It does record that a read has landed, which is what ends the state this starts
     * in, having read nothing.
     */
    void refreshUnprompted() {
        this.read(false);
    }

    /**
     * Whether the counts are being replaced.
     *
     * @return boolean true while an asked-for read is under way, and until the first read of any
     *     kind has landed
     */
    boolean isCounting() {
        return this.askedReads.get() > 0 || !this.wasReadAtLeastOnce;
    }

    /**
     * Whether a finished read has actually answered.
     *
     * @return boolean true only where a completed read succeeded
     */
    boolean countsAvailable() {
        return !this.isCounting() && !this.unreadable;
    }

    /**
     * Whether the last read failed.
     *
     * @return boolean true where the Inbox and Sorted counts could not be read
     */
    boolean unreadable() {
        return this.unreadable;
    }

    /**
     * What is waiting in the Inbox.
     *
     * @return {@link InboxTally} the last reading, or null where none has landed or one failed
     */
    @Nullable InboxTally inbox() {
        return this.inbox;
    }

    /**
     * What is staged in Sorted.
     *
     * @return {@link SortedTally} the last reading, or null where none has landed or one failed
     */
    @Nullable SortedTally sorted() {
        return this.sorted;
    }

    /**
     * Every run on disk that still owes somebody something.
     *
     * @return a {@link List} of {@link SiftRunSummary} the unfinished ones, empty where the runs
     *     folder could not be read
     */
    List<SiftRunSummary> unfinished() {
        return this.unfinished;
    }

    /**
     * What is staged, or nothing while the walk that would say is still going.
     *
     * @return a {@link List} of {@link YearRow} the staged years, newest first
     */
    List<YearRow> stagedYears() {
        final SortedTally staged = this.sorted;
        return staged == null ? List.of() : staged.years();
    }

    /**
     * How much is waiting in the undated folder, or none while the walk that would say is running.
     *
     * @return int what the last completed read found there
     */
    int undatedHeld() {
        final SortedTally staged = this.sorted;
        return staged == null ? 0 : staged.undated();
    }

    /**
     * Whether a finished read found the Inbox holding nothing.
     *
     * <p>False while the count is still going, and false where it could not be read. Refusing on
     * either would be refusing over an answer nobody has yet.
     *
     * @return boolean true only where the Inbox is known to be empty
     */
    boolean inboxIsEmpty() {
        final InboxTally waiting = this.inbox;
        return this.countsAvailable() && waiting != null && waiting.files() == 0;
    }

    /**
     * Walks the two folder trees and the runs folder, and records what they hold.
     *
     * <p>Serialised rather than allowed to overlap. Left concurrent, a reader would pair an Inbox
     * from one moment with years from another. A waiting caller re-walks rather than skipping,
     * since the read it would have skipped may predate the run that asked for this one.
     *
     * @param prompted boolean whether something happened that the screen has yet to catch up with
     */
    private void read(final boolean prompted) {
        if (prompted) {
            // Drawn before the read rather than after it, but not straight away. A wait behind
            // another walk, or a walk over a full Inbox, is long enough to be felt. A read that
            // waits for nothing and walks an empty Inbox is over in milliseconds. Going into the
            // counting state and out again inside that reads as a glitch. So the draw waits to see
            // which kind of read this turns out to be.
            this.redrawIfStillCounting();
            // Last before the lock, so nothing that allocates sits between the count going up and
            // the try that brings it down again. A count left up has no way back down, and
            // isCounting then answers true for the life of the process.
            this.askedReads.incrementAndGet();
        }
        this.readLock.lock();
        // A throw between the lock and the try would reach the caller with the lock still held,
        // and with the count above it still up. Every later read would park on that lock for the
        // rest of the run.
        try {
            this.unfinished = this.unfinishedRunsOrNone();
            this.inbox = this.pipeline.inboxTally();
            this.sorted = this.pipeline.sortedTally();
            this.unreadable = false;
        } catch (final RuntimeException e) {
            log.warn("Could not count what is waiting and what is staged: {}", e.toString());
            // Both counts go, not only the one that failed. Keeping either would let a reader be
            // shown two different moments as one.
            this.inbox = null;
            this.sorted = null;
            this.unreadable = true;
        } finally {
            // Set before the count comes down, so no reader sees an asked-for read gone while the
            // counts still read as never having landed.
            this.wasReadAtLeastOnce = true;
            if (prompted) {
                this.askedReads.decrementAndGet();
            }
            this.readLock.unlock();
        }
    }

    /**
     * Draws the screen again, but only if an asked-for read is still going by then.
     *
     * <p>Nothing cancels this. It asks the same flag the screen does when it wakes, so a read that
     * has already finished leaves it nothing to draw. A second asked-for read in the meantime
     * raises the flag again. A read nobody asked for does not.
     */
    private void redrawIfStillCounting() {
        CompletableFuture.runAsync(
                () -> {
                    if (this.isCounting()) {
                        this.repaint.run();
                    }
                },
                CompletableFuture.delayedExecutor(READING_SETTLE_WINDOW, TimeUnit.MILLISECONDS));
    }

    /**
     * Every run on disk that still owes somebody something.
     *
     * @return a {@link List} of {@link SiftRunSummary} the unfinished ones, empty where the folder
     *     could not be read
     */
    private List<SiftRunSummary> unfinishedRunsOrNone() {
        try {
            return this.pipeline.siftRuns() instanceof SiftRuns.Listed(final List<SiftRunSummary> listed)
                    ? listed.stream().filter(run -> run.health().state() != State.COMPLETE).toList()
                    : List.of();
        } catch (final RuntimeException e) {
            // Caught here rather than left to the walk's own catch. That one blanks the Inbox and
            // Sorted counts too, and tells the reader those could not be read.
            log.warn("Could not read the runs, so no timeframe is marked this pass: {}",
                    e.toString());
            return List.of();
        }
    }
}
