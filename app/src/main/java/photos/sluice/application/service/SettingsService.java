package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.FolderRootsChangeListener;
import photos.sluice.application.port.out.WorkingRootLock;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Saves settings: claim, write, then put in force.
 *
 * <p>The order is what this class is for. Claiming first refuses a folder another Sluice already
 * has open, before anything has been written or changed. A refused save therefore leaves the app
 * exactly as it was. Writing before putting the new values in force means the app never runs on
 * settings that failed to reach disk.
 *
 * <p>A folder root that moves is checked before any of that. A root set to somewhere that cannot be
 * worked in is refused. That covers text naming no path at all, a folder that is not there, and two
 * roots put inside each other. A root left unset is not refused. An install chooses its three
 * folders one picker at a time, and the facade goes on refusing jobs while any of them is empty.
 *
 * <p>A save that moves a folder root runs with the job slot held shut. Jobs start from more than
 * one thread. Asking whether one is running and then saving would leave a window for a job to start
 * against the roots it reads as it goes.
 *
 * <p>Only a save that moves the working root touches the lock at all. A save that changes a
 * category, a grid, or the library and inbox roots asks for nothing. That matters in a process
 * which never claimed a root, and would otherwise be refused its own settings change by a desktop
 * app left open.
 *
 * <p>One save at a time, so a second one cannot decide what to do from settings the first is
 * halfway through replacing.
 *
 * <p>A save that moved any of the folder roots then tells its {@link FolderRootsChangeListener}s,
 * and says whether the working root was one of them. Whatever a driving adapter does inside those
 * roots, it did against the old ones, and this is the only moment that fact is known.
 */
@Component
public class SettingsService implements SettingsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    // Held for a whole save. Every save reads the settings in force to work out what it is
    // changing, and that answer has to still be true when it acts on it.
    private final Object saves = new Object();
    private final LiveSettings live;
    private final SettingsStore store;
    private final WorkingRootLock workingRootLock;
    private final JobRunner jobRunner;
    private final PathValidationUseCase pathValidation;
    private final List<FolderRootsChangeListener> folderRootsListeners;

    /**
     * Creates the settings service.
     *
     * @param live {@link LiveSettings} holds the settings in force and swaps them
     * @param store {@link SettingsStore} writes settings so a restart reads them back
     * @param workingRootLock {@link WorkingRootLock} claims the working root for this process
     * @param jobRunner {@link JobRunner} says whether a job is running
     * @param pathValidation {@link PathValidationUseCase} checks folder roots a save would put in
     *         force
     * @param folderRootsListeners a {@link List} of {@link FolderRootsChangeListener} told once a
     *         save has moved any folder root, empty in a process that wants none
     */
    public SettingsService(final LiveSettings live, final SettingsStore store,
                           final WorkingRootLock workingRootLock, final JobRunner jobRunner,
                           final PathValidationUseCase pathValidation,
                           final List<FolderRootsChangeListener> folderRootsListeners) {
        this.live = live;
        this.store = store;
        this.workingRootLock = workingRootLock;
        this.jobRunner = jobRunner;
        this.pathValidation = pathValidation;
        this.folderRootsListeners = List.copyOf(folderRootsListeners);
    }

    /**
     * The settings in force at this moment.
     *
     * @return {@link Settings} the current settings
     */
    @Override
    public Settings settings() {
        return this.live.current();
    }

    /**
     * Saves the given settings and puts them in force.
     *
     * @param settings {@link Settings} the settings to save
     * @throws PathsMisconfiguredException if a folder root this save moves is set to a folder that
     *         cannot be worked in
     */
    @Override
    public void save(final Settings settings) {
        synchronized (this.saves) {
            final Settings previous = this.live.current();
            if (settings.paths().equals(previous.paths())) {
                this.writeAndApply(settings);
                return;
            }
            this.requireUsableRoots(settings.paths());
            final boolean workingRootMoved = !workingRoot(settings).equals(workingRoot(previous));
            if (!this.jobRunner.runIfIdle(() -> {
                this.moveRoots(settings, previous);
                this.announceFolderRootsChange(workingRootMoved);
            })) {
                throw new JobInProgressException(
                        "Sluice is running a job. Finish the current run before changing where its folders are.");
            }
        }
    }

    /**
     * Refuses candidate roots that are set to somewhere unusable.
     *
     * <p>Runs before the claim and before the job slot, so a refused save has taken nothing and
     * changed nothing. Outside the slot rather than inside it, because the check reads directories.
     * A folder root on a stalled mount would otherwise cost every concurrent {@code submit} its own
     * wait on the slot, then a refusal it did nothing to deserve.
     *
     * <p>It does still run under the save monitor, which no save can avoid: working out what is
     * changing means reading the settings in force. So a stalled root holds up the next save. That
     * is one caller rather than every job in the process.
     *
     * <p>Two refusals can be due at once, when a job is running and the roots are also unusable.
     * This one wins. It names a value the user can go and correct, and it is the same answer
     * however long the job takes. Whether a job happened to be running is neither.
     *
     * <p>Only a save that moves a folder root reaches this. A user whose library drive is unplugged
     * can still change a category, since that save leaves every root exactly where it found it.
     *
     * @param paths {@link PathSettings} the folder roots this save would put in force
     * @throws PathsMisconfiguredException if a root that is set cannot be worked in
     */
    private void requireUsableRoots(final PathSettings paths) {
        final List<PathViolation> refusals = this.pathValidation.violations(paths).stream()
                .filter(SettingsService::refuses)
                .toList();
        if (!refusals.isEmpty()) {
            throw new PathsMisconfiguredException(refusals);
        }
    }

    /**
     * Whether one violation is enough on its own to refuse a save.
     *
     * <p>A switch over every case rather than a test for the one that passes. A fifth kind of
     * violation then fails to compile here until somebody says which side of the line it falls on.
     *
     * @param violation {@link PathViolation} the violation to judge
     * @return boolean true when a save carrying this violation is refused
     */
    private static boolean refuses(final PathViolation violation) {
        return switch (violation) {
            case NotConfigured _ -> false;
            case NotAPath _, NotADirectory _, Unreadable _, Overlap _ -> true;
        };
    }

    /**
     * Saves settings that move a folder root. The claim moves with them when the root that is moving
     * is the working one.
     *
     * @param settings {@link Settings} the settings to save
     * @param previous {@link Settings} the settings in force until this succeeds
     */
    private void moveRoots(final Settings settings, final Settings previous) {
        final Optional<Path> claimed = workingRoot(settings);
        if (claimed.equals(workingRoot(previous))) {
            // The library or the inbox moved and the working root stayed put, so there is no claim
            // to move. The job gate still applied above, since a run reads all three.
            this.writeAndApply(settings);
            return;
        }
        claimed.ifPresent(this.workingRootLock::acquire);
        try {
            this.writeAndApply(settings);
        } catch (final RuntimeException e) {
            // Where a claim moved, it now sits on a folder the settings naming it never landed on.
            // This process would hold a folder it is not working in, and hold nothing on the one it
            // still is. Putting the claim back where it was puts that right.
            this.restoreClaim(claimed, previous, e);
            throw e;
        }
        if (claimed.isEmpty()) {
            // These settings name no working root, and the ones they replaced did. Left held, that
            // folder would be locked against every other Sluice for as long as this process runs,
            // while this one is not working in it.
            this.workingRootLock.release();
        }
    }

    /**
     * Tells every listener a folder root has moved.
     *
     * <p>Runs with the job slot still held shut, which is what makes it worth the delay it costs
     * every waiting {@code submit}. A listener re-arms watchers, and arming is skipped while a job
     * runs. Announced after the slot was free, a watcher armed under the old root could take that
     * slot in between. The re-arm would then silently do nothing for the life of the process.
     *
     * <p>That delay is real and this is the widest {@link JobRunner#runIfIdle} gets stretched. A
     * listener surveys every run on disk. So folder roots on a slow or stalled network mount make a
     * concurrent {@code submit} wait out its own bound and then be refused. Bounded is the whole
     * difference: a stall costs a caller one refusal it can retry from, not the runner itself.
     *
     * <p>A listener that throws would report a save that has already reached disk as a failed one,
     * so each is documented to report its own failures instead. This catch is what makes the port's
     * wording true of a listener that gets it wrong rather than merely instructing one not to.
     *
     * <p>It takes {@link Throwable} because a listener walks directory trees, which is where
     * {@code JobRunner} already names an {@link Error} as a real outcome rather than a theoretical
     * one. Only the hazard is borrowed from there, not the handling: that class forwards what it
     * catches to the caller's own future, while there is no caller here left to tell. Everything
     * durable is already on disk, so a log line is the whole remedy.
     *
     * @param workingRootMoved boolean whether this save moved the working root itself
     */
    private void announceFolderRootsChange(final boolean workingRootMoved) {
        this.folderRootsListeners.forEach(listener -> {
            try {
                listener.folderRootsChanged(workingRootMoved);
            } catch (final Throwable t) {
                log.warn("A folder-roots change listener failed after the save had already landed", t);
            }
        });
    }

    /**
     * Writes the settings, then puts them in force once the write has succeeded.
     *
     * @param settings {@link Settings} the settings to save
     */
    private void writeAndApply(final Settings settings) {
        this.store.save(settings);
        this.live.apply(settings);
    }

    /**
     * Puts the claim back after a failed save. When the settings still in force name no working
     * root, there is nothing to put it back on and the claim is given up instead.
     *
     * <p>A folder taken in the meantime cannot be taken back. The claim this save made is then
     * given up too. Held on, it would lock a folder nothing names against every other Sluice, for
     * the life of this process.
     *
     * <p>Whatever went wrong here is reported against the failure already on its way out, rather
     * than in place of it.
     *
     * @param claimed an {@link Optional} of {@link Path} the working root this save claimed, if any
     * @param previous {@link Settings} the settings still in force
     * @param failure {@link RuntimeException} the save failure the caller is about to throw
     */
    private void restoreClaim(final Optional<Path> claimed, final Settings previous,
                              final RuntimeException failure) {
        if (claimed.isEmpty()) {
            return;
        }
        try {
            workingRoot(previous).ifPresentOrElse(this.workingRootLock::acquire, this.workingRootLock::release);
        } catch (final RuntimeException e) {
            failure.addSuppressed(e);
            this.releaseReporting(failure);
        }
    }

    /**
     * Gives up whatever this process holds, reporting a failure to do so against the failure already
     * on its way out.
     *
     * @param failure {@link RuntimeException} the save failure the caller is about to throw
     */
    private void releaseReporting(final RuntimeException failure) {
        try {
            this.workingRootLock.release();
        } catch (final RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * The working root the given settings name, as the folder a claim is scoped to.
     *
     * <p>An install with nothing configured yet holds no working root, because there is no folder
     * to hold. The claim is taken by the save that first names one.
     *
     * @param settings {@link Settings} the settings to read the working root from
     * @return an {@link Optional} of {@link Path} the working root, empty when none is configured
     */
    private static Optional<Path> workingRoot(final Settings settings) {
        return Optional.ofNullable(settings.paths().repoRoot())
                .filter(repoRoot -> !repoRoot.isBlank())
                .map(repoRoot -> Path.of(repoRoot).toAbsolutePath().normalize());
    }
}
