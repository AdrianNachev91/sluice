package photos.sluice.application.service;

/**
 * The unit of work {@link JobRunner#submit} runs in the background. It takes the same
 * {@link JobHandle} the caller gets back from {@code submit}, so the work itself can poll
 * {@link JobHandle#isCancellationRequested()} at its own natural boundaries.
 *
 * <p>Declares {@code throws Exception}, like {@code Callable<T>}. That lets it call straight
 * through to a checked-exception engine method (for example {@code ApplyEngine.apply()} or
 * {@code VisionSieve.sift()}) with no wrap-and-rethrow boilerplate at the call site.
 *
 * @param <T> the type of result this work produces
 */
@FunctionalInterface
public interface JobWork<T> {

    /**
     * Runs the unit of work, using the handle to poll for cancellation.
     *
     * @param handle a {@link JobHandle} of T the handle this work runs under
     * @return T the job's result
     */
    T run(JobHandle<T> handle) throws Exception;
}
