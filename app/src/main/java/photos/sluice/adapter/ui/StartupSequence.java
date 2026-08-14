package photos.sluice.adapter.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.application.service.Pipeline;

import java.time.Duration;

/**
 * The work the desktop app does once its Spring context is up: claim the working root, then run the
 * housekeeping that reaches files inside it.
 *
 * <p>The order carries the whole point. The sweep deletes outright, and an armed watcher can
 * auto-resume a waiting run straight into an apply. Neither may happen before this process owns the
 * root.
 *
 * <p>An install whose folders are not set up yet does none of it. There is no folder to claim, and
 * nothing to house-keep inside one. The claim is taken instead by the save that first names a
 * working root, which is what completing first-run configuration does.
 *
 * <p>Arming is the desktop's own concern rather than every caller's. A watcher is only worth
 * arming in a process that stays open for it to poll in.
 */
@Component
@Profile("!cli")
public class StartupSequence {

    private static final Logger log = LoggerFactory.getLogger(StartupSequence.class);

    // How long a close waits for a running job to stop before giving up on it.
    //
    // Cancellation is cooperative, so what is being waited for is the job reaching its next stage
    // boundary. In sort, commit and rescue that boundary is between two files, so the wait only has
    // to cover one file operation already under way. Five seconds is well clear of a large move on a
    // slow disk.
    //
    // A cull sitting inside a vision call is the case no value here fixes. Its next boundary is a
    // whole montage away. Blocking a window close for that long is worse than giving up, so that
    // job is abandoned at process exit. Doing better means showing the wait and letting the user
    // force-quit out of it. That cannot live here: by the time this runs, the window it would have
    // to appear in is already gone.
    private static final Duration DRAIN_WAIT = Duration.ofSeconds(5);

    private final WorkingRootLock workingRootLock;
    private final PathsPort paths;
    private final Pipeline pipeline;
    private final PathValidationUseCase pathValidation;

    /**
     * Creates the startup sequence.
     *
     * @param workingRootLock {@link WorkingRootLock} claims the working root for this process
     * @param paths {@link PathsPort} resolves the configured working root
     * @param pipeline {@link Pipeline} the facade carrying the two startup steps
     * @param pathValidation {@link PathValidationUseCase} says whether there is a folder to claim
     */
    public StartupSequence(final WorkingRootLock workingRootLock, final PathsPort paths, final Pipeline pipeline,
                           final PathValidationUseCase pathValidation) {
        this.workingRootLock = workingRootLock;
        this.paths = paths;
        this.pipeline = pipeline;
        this.pathValidation = pathValidation;
    }

    /**
     * Claims the working root, then runs the startup housekeeping inside it. A refused claim stops
     * the sequence before anything has touched a file. Folders that are not set up yet stop it
     * before the claim.
     *
     * @throws WorkingRootBusyException if another process holds the working root
     */
    public void run() {
        if (!this.pathValidation.violationsInForce().isEmpty()) {
            return;
        }
        this.workingRootLock.acquire(this.paths.repoRoot());
        this.pipeline.armWatchesForResumableRuns();
        this.pipeline.sweepExpiredDisasterDrawers();
    }

    /**
     * Stops the app touching files, then gives the working root back so another process can take it
     * without waiting for this one to be reaped.
     *
     * <p>The order is the whole point again, run backwards. Watchers go first, so nothing is still
     * deciding to start a job. The runner is then shut and the job in flight asked to stop, which is
     * what settles whether anything is still moving files. Only then is the root handed over.
     *
     * <p>Work still running past that wait keeps the root. In a desktop run the process exits right
     * after this, and the kernel gives the claim up then, so holding on strands nothing. Handing it
     * over early is not recoverable in the same way. The next Sluice would take a folder this one is
     * still writing into, which is what the claim exists to prevent.
     *
     * <p>An embedder that calls this without then exiting keeps the root for the life of its
     * process. That is the deliberate direction: a stranded claim is recoverable by quitting, and
     * two processes in one folder is not.
     */
    public void shutdown() {
        this.pipeline.stopAllWatching();
        if (!this.pipeline.stopAcceptingJobs(DRAIN_WAIT)) {
            // Two things reach here and the message has to fit both: a job that outran the wait, and
            // a settings save still holding the job slot. Neither is safe to hand a folder away from.
            log.warn("Work was still in progress at exit, so the working root stays claimed until this process ends");
            return;
        }
        this.workingRootLock.release();
    }
}
