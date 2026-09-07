package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.ShuttingDownException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs one job at a time so a driving caller (a desktop UI, or a future CLI) can start work
 * without blocking. It gets a typed {@link JobHandle} back right away.
 *
 * <p>A second {@link #submit} while one job is still in flight throws, rather than letting two
 * engines move the same tree at once.
 *
 * <p>Every wait on the slot is bounded. Taking it can mean queueing behind a {@link #runIfIdle}
 * caller, whose work is arbitrary and can reach a folder that never answers. That is a narrow way
 * in, and what makes it worth closing is the cost once opened rather than how often it opens. An
 * unbounded wait there would take the whole process with it and stay taken, with nothing on screen
 * to say why. So the slot is a {@link ReentrantLock} rather than a monitor, and a caller that
 * cannot have it is refused rather than parked.
 *
 * <p>Virtual threads are always daemon threads, so the executor needs no explicit shutdown for
 * the process to exit cleanly. {@link #shutdown} exists for the opposite reason: to give a job that
 * is mid-move the chance to stop somewhere consistent before the process goes.
 */
@Component
public class JobRunner {

    // How long a caller waits for the slot before being refused.
    //
    // What it is really for is the case where the holder never returns at all, and any finite value
    // closes that. The value is picked by the ordinary case it must not refuse. That case is a
    // settings save landing at the same moment: a small config file written, then a survey of the
    // runs on disk. The survey grows with the number of runs, so no value covers it with certainty.
    //
    // Five seconds is well clear of it on a working disk. A refusal costs little either way, since a
    // poller retries on its next tick and a screen re-enables its own button. Erring long rather
    // than short, because both outcomes are reachable only inside the narrow window of a save that
    // moves a folder root. Of the two, a refusal with no visible cause is the more confusing.
    private static final Duration DEFAULT_SLOT_WAIT = Duration.ofSeconds(5);

    private static final String CLOSING = "Sluice is closing and will not take on new work.";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final JobCompletions completions = new JobCompletions();
    // Held across taking the slot, and across any work that must see the slot stay as it found it.
    // Jobs start from more than one thread: a screen's button, and a watcher polling a prep dir.
    private final ReentrantLock slot = new ReentrantLock();
    private final Duration slotWait;
    // The job in flight, for shutdown() to ask to stop and then wait on. Written under the slot, so
    // a shutdown reading it cannot interleave with a submit admitting one. Cleared by the job's own
    // completion, and only if no later job has replaced it by then.
    private final AtomicReference<@Nullable JobHandle<?>> inFlight = new AtomicReference<>();
    // Volatile rather than slot-guarded, because shutdown() has to set it without waiting for the
    // slot. Once true it never goes back: a process that has begun closing does not reopen.
    //
    // Writing it outside the slot means a job can still be admitted after it is set. A submit that
    // read it a moment earlier has yet to reach inFlight. So the guarantee is not "no job starts
    // after the shut". It is that no job starts which the drain fails to account for. That submit
    // holds the slot until inFlight names its job. Shutdown reads inFlight only after taking the
    // same slot, so it can never look before the answer is there.
    private volatile boolean shutDown;

    /**
     * The one Spring builds. With no constructor annotated and no single candidate to infer, Spring
     * falls back to the no-argument one, which is this.
     */
    public JobRunner() {
        this(DEFAULT_SLOT_WAIT);
    }

    /**
     * Test seam: a wait short enough that proving a refusal takes milliseconds rather than seconds.
     *
     * @param slotWait {@link Duration} how long a caller waits for the slot before being refused
     */
    JobRunner(final Duration slotWait) {
        this.slotWait = slotWait;
    }

    /**
     * Starts a job on the executor if none is currently running.
     *
     * <p>Waits for the slot, but never for ever. A {@link #runIfIdle} caller holding it shut past
     * the wait ends in a refusal, the same answer a caller gets for a job that is genuinely running.
     * So this does not always return at once. A driving adapter calling from a thread it cannot
     * afford to block, such as a UI's own event thread, accounts for that wait.
     *
     * @param work a {@link JobWork} of T the job logic to execute
     * @return a {@link JobHandle} of T a handle for the started job
     * @throws JobInProgressException if a job is already running, or the slot did not come free in
     *         time
     * @throws ShuttingDownException if the app has stopped taking work on
     */
    public <T> JobHandle<T> submit(final JobWork<T> work) {
        final var resultFuture = new CompletableFuture<T>();
        final var handle = new JobHandle<>(resultFuture);
        if (!this.takeSlotWithin(this.slotWait)) {
            // Asked before the busy answer, since both can be true at once and only one of them is
            // worth acting on. A closing app told to try again offers a retry that cannot succeed.
            throw this.refusal();
        }
        try {
            if (this.shutDown) {
                throw new ShuttingDownException(CLOSING);
            }
            if (!this.busy.compareAndSet(false, true)) {
                throw new JobInProgressException(
                        "Something else is running. Wait for it to finish, then try again.");
            }
            this.inFlight.set(handle);
        } finally {
            this.slot.unlock();
        }
        this.executor.execute(() -> this.run(work, handle, resultFuture));
        return handle;
    }

    /**
     * Runs work with the slot held shut, so no job can start while it runs, and reports whether it
     * ran at all. A caller whose work is only safe with nothing else touching the tree asks through
     * here. Checking {@link #isBusy} and then acting leaves a window a job can start in.
     *
     * <p>One requirement on the work, not enforced here. It must not start a job itself. The slot it
     * holds readmits the thread already holding it, so such a call would succeed, and leave a job
     * running under work that asked for none.
     *
     * <p>Returning promptly is a courtesy rather than a requirement. Work that overruns costs every
     * concurrent {@link #submit} its whole wait and then a refusal, which is recoverable.
     *
     * @param work {@link Runnable} the work to run while no job can start
     * @return boolean true when the work ran, false when the slot was not free for it
     * @throws ShuttingDownException if the app has stopped taking work on
     */
    public boolean runIfIdle(final Runnable work) {
        if (!this.takeSlotWithin(this.slotWait)) {
            if (this.shutDown) {
                throw new ShuttingDownException(CLOSING);
            }
            return false;
        }
        try {
            if (this.shutDown) {
                throw new ShuttingDownException(CLOSING);
            }
            if (this.busy.get()) {
                return false;
            }
            work.run();
            return true;
        } finally {
            this.slot.unlock();
        }
    }

    /**
     * Stops taking work on for good, asks the job in flight to stop, and waits up to timeout for it
     * to do so. Answers whether nothing here is still reaching files by the time it returns.
     *
     * <p>That is narrower than nothing running at all. A job's own {@link #onJobFinished} listeners
     * are not waited for, and run after a true answer has been given. They are the caller's own
     * code and touch no file of the job's, so what the caller acts on here is unaffected. Waiting
     * for them would put arbitrary listener code inside a budget this method exists to bound.
     *
     * <p>The two halves are one method because either alone is worthless. A wait with nothing shut
     * returns an answer that a poller can falsify a microsecond later. A shut with no wait leaves the
     * caller's next step racing a thread that is still moving files. That next step is giving up the
     * working root, and closing the context the job is running against.
     *
     * <p>Cancellation is cooperative, so what this waits for is the job noticing at its next stage
     * boundary. A false answer means it had not by the deadline, and the caller keeps whatever it was
     * about to hand back. Nothing here forces the job to stop: a virtual thread is a daemon thread,
     * and process exit is what ends one that will not.
     *
     * <p>Shutting and draining are separately reliable, which is why the shut does not wait for the
     * slot. Work already holding it can outlast the whole budget, and that is the case where leaving
     * the runner open matters most. The caller has been told it may not hand the root back, so a job
     * admitted afterwards would run against a context that is closing.
     *
     * <p>A second call always finds the runner already shut. What it costs depends on which branch
     * the first one took. One that reached the job returns on a future already completed. One that
     * never reached the slot queues for it again, for the same budget.
     *
     * @param timeout {@link Duration} the whole budget, covering both taking the slot and the wait
     * @return boolean true when no job of this runner's is still reaching files
     */
    public boolean shutdown(final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        this.shutDown = true;
        if (!this.takeSlotWithin(timeout)) {
            // The slot is held by runIfIdle work that outlasted the whole budget. No job can be
            // running, since that work would not have got in past one. But that work reaches files
            // of its own, so "nothing of ours is executing" is not true and must not be reported.
            return false;
        }
        final JobHandle<?> running;
        try {
            running = this.inFlight.get();
        } finally {
            this.slot.unlock();
        }
        if (running == null) {
            return true;
        }
        running.requestCancellation();
        return hasFinished(running, deadline - System.nanoTime());
    }

    /**
     * Asks the job in flight to give up on the file it is writing, rather than finish it.
     *
     * <p>Marks the job cancelled with it, since {@link JobHandle#requestAbandon} does both. So this
     * is an escalation of a stop rather than a second kind of one.
     *
     * <p>Says nothing about whether anything was running. A press landing just after a job ended has
     * nothing to escalate, and that is the same answer as one landing on a job that stops instantly.
     *
     * <p>Read without the slot, unlike {@link #shutdown}. Nothing here waits on what it reads, so the
     * worst a stale answer costs is a stop asked of a job that has already ended.
     */
    public void abandonInFlight() {
        final JobHandle<?> running = this.inFlight.get();
        if (running != null) {
            running.requestAbandon();
        }
    }

    /**
     * Whether a job's work is currently executing, nothing more. It says nothing about whether the
     * most recent job succeeded or failed - that's only ever knowable through that job's own
     * JobHandle. A caller can observe this go false a moment before that job's own join()/
     * onComplete() reports its outcome. That's fine: no filesystem-mutating work is still running
     * by the time this flips, only the outcome notification is still in flight.
     *
     * @return boolean true if a job is currently running
     */
    public boolean isBusy() {
        return this.busy.get();
    }

    /**
     * Asks to be told each time the job that was running finishes.
     *
     * <p>Says nothing about which job, or how it went. Either is knowable only through that job's
     * own {@link JobHandle}.
     *
     * <p>Nor does it promise the runner is still idle when the listener runs. The slot frees before
     * the announcement, so a successor can already have been admitted.
     *
     * @param listener {@link Runnable} what to run, on the finished job's own thread rather than
     *     the caller's
     */
    public void onJobFinished(final Runnable listener) {
        this.completions.onFinished(listener);
    }

    /**
     * Which refusal a caller that could not take the slot deserves.
     *
     * <p>Both conditions can hold at once, since the exit path shuts the runner and then queues for
     * the slot behind whatever is holding it. Closing wins, because it is the one a caller must not
     * retry.
     *
     * @return {@link RuntimeException} the refusal to throw
     */
    private RuntimeException refusal() {
        if (this.shutDown) {
            return new ShuttingDownException(CLOSING);
        }
        return new JobInProgressException(
                "Something else is still finishing, so this did not start. Try again in a moment.");
    }

    /**
     * Takes the slot, giving up after wait rather than parking on it indefinitely.
     *
     * <p>An interrupt is answered the same way a timeout is. Either way the slot was not taken, which
     * is the only thing a caller acts on. The flag goes back for whoever is tearing this thread down.
     * The cost is that a caller's own refusal message then names a busy runner when nothing was busy.
     * Accepted: nothing in the app interrupts a submitting thread, so the reader of that message is
     * a thread already being torn down.
     *
     * @param wait {@link Duration} how long to wait for the slot
     * @return boolean true when the slot is now held by this thread
     */
    // The name has to say this takes the slot, since that is its effect. Inverting it to match how
    // the answer is read would hide that.
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean takeSlotWithin(final Duration wait) {
        try {
            return this.slot.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Waits out what is left of a shutdown's budget for one job to stop executing.
     *
     * <p>A job that failed counts as finished. Whether it ended well is its own caller's question,
     * asked through its handle; the only question here is whether it is still touching files.
     *
     * @param job a {@link JobHandle} of ? the job to wait on
     * @param remainingNanos long what is left of the caller's budget, possibly already negative
     * @return boolean true when the job is no longer executing
     */
    private static boolean hasFinished(final JobHandle<?> job, final long remainingNanos) {
        try {
            job.onComplete().toCompletableFuture().get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
            return true;
        } catch (final ExecutionException e) {
            return true;
        } catch (final TimeoutException e) {
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Executes the job's work and completes the result future with its outcome.
     *
     * @param work a {@link JobWork} of T the job logic to execute
     * @param handle a {@link JobHandle} of T the handle passed to the job's work
     * @param resultFuture a {@link CompletableFuture} of T the future to complete with the result or failure
     */
    private <T> void run(final JobWork<T> work, final JobHandle<T> handle, final CompletableFuture<T> resultFuture) {
        T result = null;
        Throwable failure = null;
        try {
            result = work.run(handle);
        } catch (final Throwable t) {
            // Caught broadly, not just Exception. An Error can escape deep in an engine call - a
            // stack overflow walking a pathological directory tree, an out-of-memory decoding a
            // large batch. Only catching Exception would let it skip both freeing the slot and
            // completing the caller's join(), wedging every future submit() behind a job that
            // silently never finishes.
            failure = t;
        }
        // Freed before the future completes, never in a finally after it. That way a caller
        // chaining off join()/onComplete() can never observe isBusy() still true for the job it
        // just saw finish.
        this.busy.set(false);
        if (failure != null) {
            resultFuture.completeExceptionally(failure);
        } else {
            resultFuture.complete(result);
        }
        // Conditional, because a job that has already handed the slot on must not drop its
        // successor's handle. A shutdown reading null here has read a runner with nothing left
        // running, which is the same answer.
        this.inFlight.compareAndSet(handle, null);
        // Last, so a listener finds this job's result delivered and nothing of it still named as in
        // flight. Not that the runner is free: the slot went at busy.set(false) above, so a
        // successor can already be running by the time a listener reads anything. Announced even
        // for a job that failed, since what a listener acts on is that this one has stopped.
        this.completions.finished();
    }
}
