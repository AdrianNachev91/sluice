package photos.sluice.domain.job;

/**
 * Polled between items in a long-running loop to check whether the caller should stop.
 *
 * <p>{@code NEVER} always answers false to both questions. It is the default constant for callers
 * with nothing to cancel.
 *
 * <p>Two levels, asked at different rates. {@link #isCancelled()} is cooperative: a caller sees it
 * at the next poll point, so a stop lands between items and the item in flight finishes.
 * {@link #isAbandonRequested()} is the escalation, for the one wait long enough to read as a button
 * doing nothing. That is a single large file being transferred. Only a transfer asks it, and only
 * mid-file.
 *
 * <p>Nothing polls the second except a transfer. A signal that escalated without also cancelling
 * would abandon one file and let the loop around it carry straight on to the next.
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

    /**
     * Checks whether the transfer in flight should be given up on rather than finished.
     *
     * <p>Answers false unless an implementation says otherwise, so a signal written as a lambda
     * means what its single method says: stop between items, never mid-item.
     *
     * @return boolean true if a transfer should abandon the file it is part-way through
     */
    default boolean isAbandonRequested() {
        return false;
    }
}
