package photos.sluice.domain.job;

/**
 * Polled between items in a long-running loop to check whether the caller should stop.
 *
 * <p>{@code NEVER} always answers false. It is the default constant for callers with nothing to
 * cancel.
 *
 * <p>Cancellation is cooperative: a caller only sees it at the next poll point, never mid-item.
 */
@FunctionalInterface
public interface CancellationSignal {

    CancellationSignal NEVER = () -> false;

    /**
     * Checks whether cancellation has been requested.
     *
     * @return boolean true if the caller should stop at the next poll point
     */
    boolean isCancelled();
}
