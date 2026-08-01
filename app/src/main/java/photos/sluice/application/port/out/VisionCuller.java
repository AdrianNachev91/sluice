package photos.sluice.application.port.out;

import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

/**
 * The effect boundary application services use to turn a prepared montage directory into
 * per-montage decision shards. The judgement always comes from a model or agent the user supplies;
 * the app provides no vision of its own. The port abstracts only how those decisions arrive.
 *
 * <p>In one mode the user's agent reads the montages and writes the shards out of band. In another
 * the app calls the user's configured vision model and writes the shards from its response. Either
 * way the work stays inside {@link #cull}, so the signature is uniform and callers never branch on
 * which provider is selected.
 */
public interface VisionCuller {

    // The id ExternalAgentCuller registers under. A CullException thrown by cull() while this is the
    // configured provider always means "no complete, valid shard set yet" - the normal manual-mode
    // pause, never a failure. A caller resolves it into a waiting state. From any other (automated)
    // provider, a CullException means the model itself could not produce a valid judgement after its
    // own retries - a genuine failure a caller should propagate.
    String MANUAL_MODE_PROVIDER_ID = "external-agent";

    /**
     * Stable identifier the dispatcher matches against the configured provider (for example
     * "external-agent" or "anthropic"). Unique across all registered cullers.
     *
     * @return {@link String} the provider's stable identifier
     */
    String id();

    /**
     * Obtains a decision shard for every montage in prep, by whatever means the implementation
     * gets its judgements. Returns a report of what the run did and spent, and throws
     * CullException when it cannot. The throw is the signal to whoever is culling to try again.
     * How far opts is honored varies by provider; each documents its own take.
     *
     * <p>An implementation does not vouch for the shards' content. That is the apply phase's to
     * judge, since it alone reads the user's own answers to earlier findings. So a culler checks
     * only what it is placed to check. The manual-mode provider checks that a shard is there at
     * all. A provider calling a model checks that model's response before writing it.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @return {@link CullReport} a report of what the run did and spent
     */
    CullReport cull(PrepDir prep, CullOptions opts) throws CullException;

    /**
     * Progress-aware sibling of cull() above, ticked once per montage processed. Defaulted to
     * silently ignore progress so an implementation that doesn't override it still satisfies the
     * port. Each concrete culler overrides this one directly. Its plain cull() delegates to it
     * instead, so the real work lives in exactly one place.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @param progress {@link ProgressCallback} callback ticked once per montage processed
     * @return {@link CullReport} a report of what the run did and spent
     */
    default CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress) throws CullException {
        return this.cull(prep, opts);
    }

    /**
     * Cancellation-aware sibling of the two above, checked between montages. Defaulted to ignore
     * cancellation so an implementation with nothing interruptible to check (the external-agent
     * provider's single presence check) still satisfies the port without overriding this one too.
     * An automated provider overrides it directly, the same way it overrides the progress-aware
     * cull() above.
     *
     * @param prep {@link PrepDir} the prep directory holding montages to judge
     * @param opts {@link CullOptions} options controlling how the culler runs
     * @param progress {@link ProgressCallback} callback ticked once per montage processed
     * @param cancellation {@link CancellationSignal} signal checked between montages
     * @return {@link CullReport} a report of what the run did and spent
     */
    default CullReport cull(final PrepDir prep, final CullOptions opts, final ProgressCallback progress,
                            final CancellationSignal cancellation)
            throws CullException {
        return this.cull(prep, opts, progress);
    }
}
