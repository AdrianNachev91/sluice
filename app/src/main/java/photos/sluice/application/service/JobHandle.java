package photos.sluice.application.service;

import photos.sluice.domain.job.CancellationSignal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The caller's view of a job {@link JobRunner} just started. There is no job id. {@link JobRunner}
 * only ever runs one job at a time, so this handle is that job's identity for as long as it runs.
 *
 * <p>The same instance is also handed to the running {@link JobWork} itself, so a long-running
 * pipeline can poll {@link #isCancellationRequested()} at its own stage boundaries. Cancellation
 * here is cooperative, not a thread interrupt, so a stage already in flight always finishes
 * before a request takes effect. {@link #stopSignal()} is what an engine is given, carrying both
 * that request and the escalation below.
 *
 * @param <T> the type of result the job produces
 */
public final class JobHandle<T> {

    private final CompletableFuture<T> result;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private final AtomicBoolean abandonRequested = new AtomicBoolean(false);

    /**
     * Wraps the future backing this job's result.
     *
     * @param result a {@link CompletableFuture} of T the job's in-flight result
     */
    JobHandle(final CompletableFuture<T> result) {
        this.result = result;
    }

    /**
     * Marks cancellation as requested for this job.
     */
    public void requestCancellation() {
        this.cancellationRequested.set(true);
    }

    /**
     * Reports whether cancellation has been requested for this job.
     *
     * @return boolean true if cancellation was requested
     */
    public boolean isCancellationRequested() {
        return this.cancellationRequested.get();
    }

    /**
     * Marks the transfer in flight as one to give up on rather than finish, and marks the job
     * cancelled with it.
     *
     * <p>Both, so that abandoning cannot be asked for without stopping.
     */
    public void requestAbandon() {
        this.abandonRequested.set(true);
        this.cancellationRequested.set(true);
    }

    /**
     * Reports whether the transfer in flight should be abandoned.
     *
     * @return boolean true if abandoning was requested
     */
    public boolean isAbandonRequested() {
        return this.abandonRequested.get();
    }

    /**
     * This job's two stop flags as the signal an engine polls.
     *
     * @return {@link CancellationSignal} a live view of this handle, not a snapshot
     */
    public CancellationSignal stopSignal() {
        return new CancellationSignal() {
            @Override
            public boolean isCancelled() {
                return JobHandle.this.isCancellationRequested();
            }

            @Override
            public boolean isAbandonRequested() {
                return JobHandle.this.isAbandonRequested();
            }
        };
    }

    /**
     * Blocks until the job completes and returns its result.
     *
     * @return T the job's result
     */
    public T join() {
        return this.result.join();
    }

    /**
     * A read-only view for a caller that wants to react asynchronously (thenAccept/whenComplete)
     * instead of blocking on join(). CompletionStage, not the CompletableFuture itself, so nothing
     * outside JobRunner can complete or cancel this handle's own future.
     *
     * @return a {@link CompletionStage} of T a read-only completion stage for this job's result
     */
    public CompletionStage<T> onComplete() {
        return this.result.minimalCompletionStage();
    }
}
