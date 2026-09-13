package photos.sluice.application.port.in;

/**
 * Why a sift run paused rather than finished.
 *
 * <p>Coarse on purpose. It answers what a person cares about, so the several places a run can be
 * cancelled from collapse into one value. How far a cancelled run got is a question about money,
 * and {@code SiftJobOutcome.siftReport()} is where that is answered.
 */
public enum WaitingReason {

    /**
     * The user cancelled, at any point between prep finishing and apply completing.
     */
    CANCELLED,

    /**
     * At least one montage has no shard yet, and the provider's judgements arrive out of band. The
     * user's own agent still has work to do.
     */
    SHARDS_OUTSTANDING,

    /**
     * The run's own spend ceiling ended it, with montages left unjudged that nothing else
     * prevented.
     */
    CEILING_REACHED
}
