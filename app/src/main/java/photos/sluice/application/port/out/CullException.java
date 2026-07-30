package photos.sluice.application.port.out;

/**
 * Thrown by {@link VisionCuller#cull} when the provider cannot yield a complete, valid set of
 * decision shards for the prepared montages. The message carries the aggregated reason: which
 * montages lack a shard, which shards are off-contract.
 *
 * <p>That lets the culler fix everything in one pass instead of one error per re-run. Checked,
 * because an incomplete cull is an expected, recoverable outcome: the caller re-runs once the
 * shards are corrected.
 */
public class CullException extends Exception {

    /**
     * Creates the exception with an aggregated problem message.
     *
     * @param message {@link String} the aggregated validation problems
     */
    public CullException(final String message) {
        super(message);
    }

    /**
     * Creates the exception with an aggregated problem message and an underlying cause.
     *
     * @param message {@link String} the aggregated validation problems
     * @param cause {@link Throwable} the underlying cause
     */
    public CullException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
