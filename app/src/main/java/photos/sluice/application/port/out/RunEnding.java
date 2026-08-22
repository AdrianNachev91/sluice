package photos.sluice.application.port.out;

/**
 * How a cull run ended, as the spend ledger records it.
 *
 * <p>Not the outcome set one for one, because the distinctions the two draw are different. A
 * cancellation before montage rendering finished and one after dispatch ran are separate outcomes,
 * since only the second leaves something resumable. Both are {@link #CANCELLED} here. What separates
 * them is how far the run got, which the montage counts and token totals on the same line already
 * say.
 *
 * <p>{@link #FAILED} runs the other way: it has no outcome at all, since that run left by way of an
 * exception. A record of what was spent must still have a line for it.
 */
public enum RunEnding {

    /**
     * A usable shard set came back and apply moved the files.
     */
    APPLIED,

    /**
     * Every montage had a shard and apply's validation refused anyway.
     */
    BLOCKED,

    /**
     * The user cancelled, at any point.
     */
    CANCELLED,

    /**
     * The run paused with montages still unjudged, waiting on judgements that arrive out of band.
     */
    SHARDS_OUTSTANDING,

    /**
     * The run's own spend ceiling ended it.
     */
    CEILING_REACHED,

    /**
     * The provider gave up on a montage and the run was abandoned. What it had already consumed by
     * then was still billed, which is why this is a line rather than an absence of one.
     */
    FAILED
}
