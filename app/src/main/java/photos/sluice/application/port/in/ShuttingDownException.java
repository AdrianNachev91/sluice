package photos.sluice.application.port.in;

/**
 * Thrown when work is refused because the app is closing and has stopped taking any on.
 *
 * <p>Distinct from {@link JobInProgressException}, which says wait and ask again. Nothing about this
 * one clears by waiting: the job runner is shut for the rest of the process. A screen catching the
 * two as one would offer a retry that can never succeed.
 *
 * <p>Rare by design. The desktop's exit path stops the runner once its window is already gone. The
 * one caller left to meet this is a poller that was already mid-attempt.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class ShuttingDownException extends IllegalStateException {

    /**
     * Creates the exception.
     *
     * @param message {@link String} what was refused, and why nothing will change that
     */
    public ShuttingDownException(final String message) {
        super(message);
    }
}
