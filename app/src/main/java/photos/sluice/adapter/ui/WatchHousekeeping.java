package photos.sluice.adapter.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.WatchingChangedListener;
import photos.sluice.application.service.Pipeline;

/**
 * Brings what is polling into line with a save that changed whether Sluice watches for answers.
 *
 * <p>Every failure is logged and swallowed, the same as {@link FolderRootsHousekeeping}. The save
 * that triggered this has already reached disk, so a throw would report a successful save as a
 * failed one.
 */
@Component
@Profile("!cli")
public class WatchHousekeeping implements WatchingChangedListener {

    private static final Logger log = LoggerFactory.getLogger(WatchHousekeeping.class);

    private final Pipeline pipeline;

    /**
     * Creates the listener.
     *
     * @param pipeline {@link Pipeline} the facade both moves go through
     */
    public WatchHousekeeping(final Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Stops or starts every watcher this process could have polling.
     *
     * <p>All of them rather than a chosen few, in either direction. Nothing records which watch was
     * armed because the setting said so and which by a run of its own. The setting the reader just
     * changed is also the one that armed every watch a screen can reach.
     *
     * @param watching boolean whether the save leaves Sluice watching
     */
    @Override
    public void watchingChanged(final boolean watching) {
        try {
            if (watching) {
                this.pipeline.armWatchesForResumableRuns();
            } else {
                this.pipeline.stopAllWatching();
            }
        } catch (final Throwable t) {
            log.warn("Bringing the watchers into line after watching changed did not complete", t);
        }
    }
}
