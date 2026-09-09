package photos.sluice.application.port.out;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;

/**
 * The effect boundary application services use to render montage contact sheets for a cull scope,
 * producing the prep directory a vision provider then judges.
 */
public interface MontageRenderer {

    /**
     * Builds montage contact sheets for a cull scope.
     *
     * @param scope {@link CullScope} the media scope to render montages for
     * @param config {@link MontageConfig} the montage layout configuration
     * @return {@link PrepDir} the prep directory holding the built montages
     */
    PrepDir build(CullScope scope, MontageConfig config);

    /**
     * Progress-aware sibling of build() above, ticked once per montage written. Defaulted to
     * silently ignore progress so an implementation that doesn't override it still satisfies the
     * port.
     *
     * @param scope {@link CullScope} the media scope to render montages for
     * @param config {@link MontageConfig} the montage layout configuration
     * @param progress {@link ProgressCallback} callback ticked once per montage written
     * @return {@link PrepDir} the prep directory holding the built montages
     */
    default PrepDir build(final CullScope scope, final MontageConfig config, final ProgressCallback progress) {
        return this.build(scope, config);
    }

    /**
     * Cancellation-aware sibling, checked both in the tile-render pass and once per montage in the
     * write loop. Null iff cancelled before index.json was written - stopped before any resumable
     * state existed. This return value is the sole authority on whether the run was cancelled; a
     * caller must never re-check disk state instead. Defaulted to ignore cancellation so an
     * implementation with nothing interruptible to check still satisfies the port without
     * overriding this one too.
     *
     * @param scope {@link CullScope} the media scope to render montages for
     * @param config {@link MontageConfig} the montage layout configuration
     * @param progress {@link ProgressCallback} callback ticked once per montage written
     * @param cancellation {@link CancellationSignal} signal checked during rendering
     * @return {@link PrepDir}, or null if cancelled before any resumable state existed
     */
    default @Nullable PrepDir build(final CullScope scope, final MontageConfig config, final ProgressCallback progress,
                                    final CancellationSignal cancellation) {
        return this.build(scope, config, progress);
    }
}
