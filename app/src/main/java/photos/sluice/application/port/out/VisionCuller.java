package photos.sluice.application.port.out;

import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

// A vision provider that turns a prepared montage directory into per-montage decision shards. The
// judgement always comes from a model or agent the user supplies. The app provides no vision of its
// own. The port abstracts only how those decisions arrive. In one mode the user's agent reads the
// montages and writes the shards out of band. In another the app calls the user's configured vision
// model and writes the shards from its response. Either way the work stays inside cull(), so the
// signature is uniform and callers never branch on which provider is selected.
public interface VisionCuller {

    // Stable identifier the dispatcher matches against the configured provider (for example
    // "external-agent" or "anthropic"). Unique across all registered cullers.
    String id();

    // Ensures every montage in prep has a present, valid decision shard, by whatever means the
    // implementation obtains the judgements, and returns a report of what the run did and spent.
    // Throws CullException when a complete, valid set cannot be yielded. The throw is the signal to
    // whoever is culling to fix the shards and run again. How far opts is honored varies by
    // provider; each documents its own take.
    CullReport cull(PrepDir prep, CullOptions opts) throws CullException;

    // Progress-aware sibling of cull() above, ticked once per montage processed. Defaulted to
    // silently ignore progress so an implementation that doesn't override it still satisfies the
    // port. Each concrete culler overrides this one directly. Its plain cull() delegates to it
    // instead, so the real work lives in exactly one place.
    default CullReport cull(PrepDir prep, CullOptions opts, ProgressCallback progress) throws CullException {
        return cull(prep, opts);
    }
}
