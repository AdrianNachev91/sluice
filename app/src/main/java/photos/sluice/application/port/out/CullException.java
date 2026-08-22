package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link VisionCuller#cull} when the provider cannot yield a complete, valid set of
 * decision shards for the prepared montages. The message carries the aggregated reason: which
 * montages lack a shard, which shards are off-contract.
 *
 * <p>That lets the culler fix everything in one pass instead of one error per re-run. Checked,
 * because an incomplete cull is an expected, recoverable outcome: the caller re-runs once the
 * shards are corrected.
 *
 * <p>It also carries the report the abandoned run had built so far, when the provider builds one. A
 * run that fails partway has still been billed for everything it sent, and has judged whatever it
 * judged before giving up. The report that would normally say both is the return value this throw
 * replaces.
 */
public class CullException extends Exception {

    private final transient @Nullable CullReport report;

    /**
     * Creates the exception with an aggregated problem message.
     *
     * @param message {@link String} the aggregated validation problems
     */
    public CullException(final String message) {
        this(message, (CullReport) null);
    }

    /**
     * Creates the exception with an aggregated problem message and what the abandoned run had done
     * up to the throw.
     *
     * @param message {@link String} the aggregated validation problems
     * @param report {@link CullReport} what the run had judged and consumed, or null when the
     *        provider consumes nothing and counts nothing
     */
    public CullException(final String message, final @Nullable CullReport report) {
        super(message);
        this.report = report;
    }

    /**
     * Creates the exception with an aggregated problem message and an underlying cause.
     *
     * @param message {@link String} the aggregated validation problems
     * @param cause {@link Throwable} the underlying cause
     */
    public CullException(final String message, final Throwable cause) {
        super(message, cause);
        this.report = null;
    }

    /**
     * What the abandoned run had judged and consumed, or null when the provider counts nothing.
     *
     * @return {@link CullReport} the run's own report up to the throw, or null
     */
    public @Nullable CullReport report() {
        return this.report;
    }
}
