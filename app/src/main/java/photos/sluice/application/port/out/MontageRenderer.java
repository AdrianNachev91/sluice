package photos.sluice.application.port.out;

import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.ProgressCallback;

public interface MontageRenderer {

    PrepDir build(CullScope scope, MontageConfig config);

    // Progress-aware sibling of build() above, ticked once per montage written. Defaulted to
    // silently ignore progress so an implementation that doesn't override it still satisfies the
    // port. CullMontageRenderer overrides this one directly. Its plain build() delegates to it
    // instead, so the real work lives in exactly one place.
    default PrepDir build(CullScope scope, MontageConfig config, ProgressCallback progress) {
        return build(scope, config);
    }
}
