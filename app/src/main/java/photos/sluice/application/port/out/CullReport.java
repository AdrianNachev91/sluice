package photos.sluice.application.port.out;

/**
 * Outcome summary of a completed cull. A run returns one once it has nothing further to ask of the
 * judging model or agent. Anything less throws instead.
 *
 * <p>Whether the resulting shards are any good is not what this reports. That question belongs to
 * the apply phase, the single validator of shard content, and a shard counted here may still be
 * refused there.
 *
 * <p>{@code montagesCulled} counts montages this run obtained fresh judgement for.
 * {@code montagesSkipped} counts montages that got none, for any reason. Three reach it: waived by
 * {@code allowPartial}, already holding a shard from an earlier interrupted run, and unculled for
 * want of a readable sidecar.
 *
 * <p>The two need not sum to the montage count. A run stopped early by a cancellation reports only
 * what it reached.
 *
 * <p>The token counts are what the provider's model consumed for the run. A provider that never
 * calls a model from inside the app (the judgements arrive as ready-made shards) has nothing to
 * count and reports zero.
 */
public record CullReport(int montagesCulled, int montagesSkipped, long inputTokens, long outputTokens) {
}
