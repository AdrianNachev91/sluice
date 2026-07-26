package photos.sluice.domain.job;

// Polled between items in a long-running loop. NEVER always answers false - the default when no
// caller has anything to cancel. Cancellation is cooperative: a caller sees it only at the next
// poll point, never mid-item.
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
