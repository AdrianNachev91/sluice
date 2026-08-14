package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one job at a time so a driving caller (a desktop UI, or a future CLI) can start work
 * without blocking. It gets a typed {@link JobHandle} back right away.
 *
 * <p>A UI is expected to disable its own start control while a job runs. The slot is enforced here
 * too, not just at the UI layer. A second {@link #submit} while one job is still in flight throws,
 * rather than letting two engines move the same tree at once.
 *
 * <p>Virtual threads are always daemon threads, so the executor needs no explicit shutdown for
 * the process to exit cleanly.
 */
@Component
public class JobRunner {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    // Held across taking the slot, and across any work that must see the slot stay as it found it.
    // Jobs start from more than one thread: a screen's button, and a watcher polling a prep dir.
    private final Object slot = new Object();

    /**
     * Starts a job on the executor if none is currently running.
     *
     * <p>Waits while a {@link #runIfIdle} caller holds the slot shut, for as long as that work
     * takes. Nothing here bounds it, so the requirement sits on that caller.
     *
     * @param work a {@link JobWork} of T the job logic to execute
     * @return a {@link JobHandle} of T a handle for the started job
     * @throws JobInProgressException if a job is already running
     */
    public <T> JobHandle<T> submit(final JobWork<T> work) {
        synchronized (this.slot) {
            if (!this.busy.compareAndSet(false, true)) {
                throw new JobInProgressException(
                        "Sluice is already running a job. Wait for it to finish, then start this one.");
            }
        }
        final var resultFuture = new CompletableFuture<T>();
        final var handle = new JobHandle<>(resultFuture);
        this.executor.execute(() -> this.run(work, handle, resultFuture));
        return handle;
    }

    /**
     * Runs work with the slot held shut, so no job can start while it runs, and reports whether it
     * ran at all. A caller whose work is only safe with nothing else touching the tree asks through
     * here. Checking {@link #isBusy} and then acting leaves a window a job can start in.
     *
     * <p>Two requirements on the work, neither enforced here. It must not start a job itself: the
     * slot it holds readmits the thread already holding it, so such a call would succeed and leave a
     * job running under work that asked for none. And it must return promptly, since every
     * {@link #submit} waits behind it.
     *
     * @param work {@link Runnable} the work to run while no job can start
     * @return boolean true when the work ran, false when a job was already running
     */
    public boolean runIfIdle(final Runnable work) {
        synchronized (this.slot) {
            if (this.busy.get()) {
                return false;
            }
            work.run();
            return true;
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
    }
}
