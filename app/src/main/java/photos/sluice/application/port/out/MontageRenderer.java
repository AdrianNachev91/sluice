package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

public interface MontageRenderer {

    PrepDir build(CullScope scope, MontageConfig config);

    // Progress-aware sibling of build() above, ticked once per montage written. Defaulted to
    // silently ignore progress so an implementation that doesn't override it still satisfies the
    // port. CullMontageRenderer overrides the cancellation-aware sibling below directly, and both
    // of these delegate into it, so the real work lives in exactly one place.
    default PrepDir build(CullScope scope, MontageConfig config, ProgressCallback progress) {
        return build(scope, config);
    }

    // Cancellation-aware sibling, checked both in the tile-render pass and once per montage in the
    // write loop. Null iff cancelled before index.json was written - stopped before any resumable
    // state existed. This return value is the sole authority on whether the run was cancelled; a
    // caller must never re-check disk state instead. Defaulted to ignore cancellation so an
    // implementation with nothing interruptible to check still satisfies the port without
    // overriding this one too.
    default @Nullable PrepDir build(CullScope scope, MontageConfig config, ProgressCallback progress,
            CancellationSignal cancellation) {
        return build(scope, config, progress);
    }
}
