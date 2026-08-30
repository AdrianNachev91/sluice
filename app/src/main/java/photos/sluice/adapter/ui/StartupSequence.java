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
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The work the desktop app does once its Spring context is up: claim the working root, then run the
 * housekeeping that reaches files inside it.
 *
 * <p>The order carries the whole point. The sweep deletes outright, and an armed watcher can
 * auto-resume a waiting run straight into an apply. Neither may happen before this process owns the
 * root.
 *
 * <p>The two steps are gated separately, on what each one needs. The claim needs a usable working
 * root and nothing else, so an install still choosing its library or inbox still owns the folder it
 * stages in. Housekeeping reaches all three roots, so it waits for all three. An install with no
 * usable working root does neither, and the claim is taken instead by the save that first names
 * one.
 *
 * <p>Arming is the desktop's own concern rather than every caller's. A watcher is only worth
 * arming in a process that stays open for it to poll in.
 */
@Component
@Profile("!cli")
public class StartupSequence {

    private static final Logger log = LoggerFactory.getLogger(StartupSequence.class);

    // How long an unattended close waits for a running job to stop before giving up on it.
    //
    // Cancellation is cooperative, so what is being waited for is the job reaching its next stage
    // boundary. In sort, commit and rescue that boundary is between two files, so the wait only has
    // to cover one file operation already under way. Five seconds is well clear of a large move on a
    // slow disk.
    //
    // A cull sitting inside a vision call is the case no value here fixes. Its next boundary is a
    // whole montage away. This path has nothing on screen to explain such a wait, and a window that
    // hangs unexplained is worse than a job given up on.
    private static final Duration DRAIN_WAIT = Duration.ofSeconds(5);

    /**
     * How long a close the reader answered for themselves waits for the job it stopped.
     *
     * <p>Sized for the one wait no cancellation shortens, which is a sift inside a call to a model.
     * Nothing polls a stop until that call comes back.
     *
     */
    public static final Duration ATTENDED_DRAIN_WAIT = Duration.ofSeconds(90);

    private final AtomicBoolean woundDown = new AtomicBoolean();

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
     * the sequence before anything has touched a file.
     *
     * <p>A working root that cannot be worked in stops it before the claim, since there is nothing
     * to hold. Everything else stops the housekeeping and not the claim: another root left unset or
     * unusable, and two roots overlapping. The folder this process stages in is still its own, and
     * holding it is what keeps a second Sluice out of a half-configured install.
     *
     * @throws WorkingRootBusyException if another process holds the working root
     */
    public void run() {
        final List<PathViolation> violations = this.pathValidation.violationsInForce();
        if (violations.stream().anyMatch(StartupSequence::leavesTheWorkingRootUnusable)) {
            return;
        }
        this.workingRootLock.acquire(this.paths.repoRoot());
        if (!violations.isEmpty()) {
            return;
        }
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
        this.windDownWithin(DRAIN_WAIT);
    }

    /**
     * The same wind-down on a budget the caller chooses, answering whether it drained cleanly.
     *
     * <p>What a quit the reader answered calls, from a thread that is not the one painting. It can
     * afford a far longer wait than {@link #shutdown} because a window is up describing it.
     *
     * <p>A budget of zero asks the job to stop and does not wait at all. That is what a reader
     * pressing past the wait gets. The root stays claimed, which strands nothing: the process exits
     * next, and the kernel drops the claim with it.
     *
     * <p>Runs once. Whichever call arrives first decides, and every later one answers true without
     * touching anything. That matters because both paths fire in one close: the quit flow winds
     * down, then the toolkit calls {@link #shutdown} on the way out. A second drain would queue for
     * the runner's slot and spend its whole budget on a job the first already gave up on.
     *
     * @param wait {@link Duration} how long to wait for a running job to stop
     * @return boolean true when nothing of this app's was still reaching files
     */
    public boolean windDownWithin(final Duration wait) {
        if (!this.woundDown.compareAndSet(false, true)) {
            return true;
        }
        this.pipeline.stopAllWatching();
        if (!this.pipeline.stopAcceptingJobs(wait)) {
            // Two things reach here and the message has to fit both: a job that outran the wait, and
            // a settings save still holding the job slot. Neither is safe to hand a folder away from.
            log.warn("Work was still in progress at exit, so the working root stays claimed until this process ends");
            return false;
        }
        this.workingRootLock.releaseAll();
        return true;
    }

    /**
     * Whether a violation says the working root itself cannot be worked in.
     *
     * <p>Only these gate the claim, because a claim covers one folder. A library root nobody has
     * chosen yet says nothing about whether this process is working in its own. An install part way
     * through choosing its three folders is still staging into one of them, and a claim is what
     * keeps a second process out of it.
     *
     * <p>Each arm earns its place, for one of two reasons. {@code NotConfigured} and
     * {@code NotAPath} are the two ways {@link PathsPort#repoRoot} itself throws, so admitting
     * either would fail before a claim was even attempted. {@code NotADirectory} and
     * {@code Unreadable} let that call return, and the claim then fails opening its marker file
     * inside a folder that is not there. Admitting those would turn a misconfigured install into a
     * hard startup failure.
     *
     * <p>An overlap does not gate it, and the reason is structural rather than a reading of what an
     * overlap means. Overlaps are only computed once all three roots have resolved to real
     * directories, so an {@code Overlap} in the list is itself proof that the working root is one.
     * That is exactly what a claim needs. Housekeeping is what such a configuration is unsafe for,
     * and that still waits for every root.
     *
     * @param violation {@link PathViolation} one reason the configured roots cannot be worked in
     * @return boolean true when the working root is the root it makes unusable
     */
    private static boolean leavesTheWorkingRootUnusable(final PathViolation violation) {
        return switch (violation) {
            case NotConfigured(final PathRole role) -> role == PathRole.WORKING_ROOT;
            case NotAPath(final PathRole role, final String _) -> role == PathRole.WORKING_ROOT;
            case NotADirectory(final PathRole role, final Path _) -> role == PathRole.WORKING_ROOT;
            case Unreadable(final PathRole role, final Path _) -> role == PathRole.WORKING_ROOT;
            case Overlap _ -> false;
        };
    }
}
