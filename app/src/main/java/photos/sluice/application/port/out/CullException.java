package photos.sluice.application.port.out;

// Thrown by VisionCuller.cull() when the provider cannot yield a complete, valid set of decision
// shards for the prepared montages. The message carries the aggregated reason: which montages lack a
// shard, which shards are off-contract. That lets the culler fix everything in one pass instead of one
// error per re-run. Checked, because an incomplete cull is an expected, recoverable outcome: the
// caller re-runs once the shards are corrected.
public class CullException extends Exception {

    public CullException(String message) {
        super(message);
    }

    public CullException(String message, Throwable cause) {
        super(message, cause);
    }
}
