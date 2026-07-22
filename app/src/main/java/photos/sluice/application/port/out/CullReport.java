package photos.sluice.application.port.out;

// Outcome summary of a completed cull. Only a run that yielded a complete, valid shard set returns
// one; anything less throws instead. montagesCulled counts montages whose valid shard the run
// yielded. montagesSkipped counts montages waived by allowPartial. The token counts are what the
// provider's model consumed for the run. A provider that never calls a model from inside the app
// (the judgements arrive as ready-made shards) has nothing to count and reports zero.
public record CullReport(int montagesCulled, int montagesSkipped, long inputTokens, long outputTokens) {
}
