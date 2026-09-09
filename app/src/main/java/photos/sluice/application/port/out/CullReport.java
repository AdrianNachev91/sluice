package photos.sluice.application.port.out;

/**
 * Outcome summary of a cull: what it judged, what it consumed, and whether it stopped short of the
 * work it was given.
 *
 * <p>Whether the resulting shards are any good is not what this reports. That question belongs to
 * the apply phase, the single validator of shard content, and a shard counted here may still be
 * refused there.
 *
 * <p>{@code montagesSkipped} counts montages that got no judgement, for any reason: waived by
 * {@code allowPartial}, already holding a shard from an earlier interrupted run, or unculled for
 * want of a readable sidecar.
 *
 * <p>Culled and skipped need not sum to the montage count. A run stopped early by a cancellation or
 * by the spend ceiling reports only what it reached.
 *
 * <p>{@code apiCalls} counts calls, not montages, so it is what says how often a montage needed a
 * second attempt. That ratio is the only way to turn an exact count of one call into what a montage
 * costs, and nothing else on this record carries it.
 *
 * @param montagesCulled how many montages this run obtained fresh judgement for
 * @param montagesSkipped how many montages this run got no judgement for
 * @param apiCalls how many calls the run made against the provider's model
 * @param spend {@link TokenSpend} what the run consumed, and who consumed it
 * @param stoppedAtCeiling whether the run's own spend ceiling ended it, leaving montages unjudged
 *        that nothing else prevented
 */
public record CullReport(int montagesCulled, int montagesSkipped, int apiCalls, TokenSpend spend,
                         boolean stoppedAtCeiling) {

    /**
     * A report of a cull that judged nothing and consumed nothing.
     *
     * @param providerId {@link String} the provider that was configured for the run
     * @param montagesSkipped how many montages went unjudged
     * @return {@link CullReport} a zero report attributed to that provider
     */
    public static CullReport nothingSpent(final String providerId, final int montagesSkipped) {
        return new CullReport(0, montagesSkipped, 0, TokenSpend.none(providerId), false);
    }
}
