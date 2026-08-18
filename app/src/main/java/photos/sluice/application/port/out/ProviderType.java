package photos.sluice.application.port.out;

/**
 * How a vision provider arrives at its judgements, which is what decides how a caller reads a
 * {@link CullException} thrown out of {@link VisionCuller#cull}.
 *
 * <p>The set grows. Any provider that waits on something outside this app answers {@link #MANUAL},
 * whatever it waits on.
 */
public enum ProviderType {

    /**
     * The app calls a model and writes the shards from its answer. A {@link CullException} means
     * that model could not produce a valid judgement after its own retries, which is a genuine
     * failure to propagate.
     */
    API,

    /**
     * Somebody outside this app writes the shards, and the provider reports on what is there. A
     * {@link CullException} means no complete, valid shard set yet. That is the ordinary pause
     * rather than a failure, and a caller resolves it into a waiting state.
     */
    MANUAL
}
