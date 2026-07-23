package photos.sluice.application.port.out;

// Outcome summary of a completed cull. Only a run whose shard set came out valid and complete (or
// short only by explicit allowPartial waivers) returns one; anything less throws instead. montagesCulled counts montages whose valid shard this run
// produced fresh judgement for. montagesSkipped counts montages that got none: waived by
// allowPartial, or already holding a valid shard from an earlier interrupted run. The token counts
// are what the provider's model consumed for the run. A provider that never calls a model from
// inside the app (the judgements arrive as ready-made shards) has nothing to count and reports zero.
public record CullReport(int montagesCulled, int montagesSkipped, long inputTokens, long outputTokens) {
}
