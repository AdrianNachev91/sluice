package photos.sluice.adapter.ui;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;

/**
 * The work the desktop app does once its Spring context is up: claim the working root, then run the
 * housekeeping that reaches files inside it.
 *
 * <p>The order carries the whole point. The sweep deletes outright, and an armed watcher can
 * auto-resume a waiting run straight into an apply. Neither may happen before this process owns the
 * root.
 *
 * <p>Arming is the desktop's own concern rather than every caller's. A watcher is only worth
 * arming in a process that stays open for it to poll in.
 */
@Component
@Profile("!cli")
public class StartupSequence {

    private final WorkingRootLock workingRootLock;
    private final PathsPort paths;
    private final Pipeline pipeline;

    /**
     * Creates the startup sequence.
     *
     * @param workingRootLock {@link WorkingRootLock} claims the working root for this process
     * @param paths {@link PathsPort} resolves the configured working root
     * @param pipeline {@link Pipeline} the facade carrying the two startup steps
     */
    public StartupSequence(final WorkingRootLock workingRootLock, final PathsPort paths, final Pipeline pipeline) {
        this.workingRootLock = workingRootLock;
        this.paths = paths;
        this.pipeline = pipeline;
    }

    /**
     * Claims the working root, then runs the startup housekeeping inside it. A refused claim stops
     * the sequence before anything has touched a file.
     *
     * @throws WorkingRootBusyException if another process holds the working root
     */
    public void run() {
        this.workingRootLock.acquire(this.paths.repoRoot());
        this.pipeline.armWatchesForResumableRuns();
        this.pipeline.sweepExpiredDisasterDrawers();
    }

    /**
     * Gives the working root back, so another process can take it without waiting for this one to
     * be reaped.
     */
    public void shutdown() {
        this.workingRootLock.release();
    }
}
