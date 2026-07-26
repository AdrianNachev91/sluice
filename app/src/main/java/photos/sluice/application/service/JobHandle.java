package photos.sluice.application.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

// The caller's view of a job JobRunner just started. There is no JobId: JobRunner only ever runs
// one job at a time, so this handle IS that job's identity for as long as it's running. The same
// instance is also handed to the running JobWork itself, so a long-running pipeline can poll
// isCancellationRequested() at its own stage boundaries. Cancellation here is cooperative, not a
// thread interrupt, so a stage already in flight always finishes before a request takes effect.
public final class JobHandle<T> {

    private final CompletableFuture<T> result;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    /**
     * Wraps the future backing this job's result.
     *
     * @param result a {@link CompletableFuture} of T the job's in-flight result
     */
    JobHandle(CompletableFuture<T> result) {
        this.result = result;
    }

    /**
     * Marks cancellation as requested for this job.
     */
    public void requestCancellation() {
        cancellationRequested.set(true);
    }

    /**
     * Reports whether cancellation has been requested for this job.
     *
     * @return boolean true if cancellation was requested
     */
    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /**
     * Blocks until the job completes and returns its result.
     *
     * @return T the job's result
     */
    public T join() {
        return result.join();
    }

    /**
     * A read-only view for a caller that wants to react asynchronously (thenAccept/whenComplete)
     * instead of blocking on join(). CompletionStage, not the CompletableFuture itself, so nothing
     * outside JobRunner can complete or cancel this handle's own future.
     *
     * @return a {@link CompletionStage} of T a read-only completion stage for this job's result
     */
    public CompletionStage<T> onComplete() {
        return result.minimalCompletionStage();
    }
}
