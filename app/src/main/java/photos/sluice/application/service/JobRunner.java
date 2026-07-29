package photos.sluice.application.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one job at a time so a driving caller (a desktop UI, or a future CLI) can start work
 * without blocking. It gets a typed {@link JobHandle} back right away.
 *
 * <p>A UI is expected to disable its own start control while a job runs. The slot is enforced
 * here too, not just at the UI layer: a second {@link #submit} call while one is still in flight
 * throws, rather than letting two engines move the same tree at once.
 *
 * <p>Virtual threads are always daemon threads, so the executor needs no explicit shutdown for
 * the process to exit cleanly.
 */
@Component
public class JobRunner {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * Starts a job on the executor if none is currently running.
     *
     * @param work a {@link JobWork} of T the job logic to execute
     * @return a {@link JobHandle} of T a handle for the started job
     */
    public <T> JobHandle<T> submit(JobWork<T> work) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("A job is already running; only one job runs at a time");
        }
        var resultFuture = new CompletableFuture<T>();
        var handle = new JobHandle<>(resultFuture);
        executor.execute(() -> run(work, handle, resultFuture));
        return handle;
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
        return busy.get();
    }

    /**
     * Executes the job's work and completes the result future with its outcome.
     *
     * @param work a {@link JobWork} of T the job logic to execute
     * @param handle a {@link JobHandle} of T the handle passed to the job's work
     * @param resultFuture a {@link CompletableFuture} of T the future to complete with the result or failure
     */
    private <T> void run(JobWork<T> work, JobHandle<T> handle, CompletableFuture<T> resultFuture) {
        T result = null;
        Throwable failure = null;
        try {
            result = work.run(handle);
        } catch (Throwable t) {
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
        busy.set(false);
        if (failure != null) {
            resultFuture.completeExceptionally(failure);
        } else {
            resultFuture.complete(result);
        }
    }
}
