package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsStore;
import photos.sluice.application.port.out.WorkingRootLock;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Saves settings: claim, write, then put in force.
 *
 * <p>The order is what this class is for. Claiming first refuses a folder another Sluice already
 * has open, before anything has been written or changed. A refused save therefore leaves the app
 * exactly as it was. Writing before putting the new values in force means the app never runs on
 * settings that failed to reach disk.
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
 */
@Component
public class SettingsService implements SettingsUseCase {

    // Held for a whole save. Every save reads the settings in force to work out what it is
    // changing, and that answer has to still be true when it acts on it.
    private final Object saves = new Object();
    private final LiveSettings live;
    private final SettingsStore store;
    private final WorkingRootLock workingRootLock;
    private final JobRunner jobRunner;

    /**
     * Creates the settings service.
     *
     * @param live {@link LiveSettings} holds the settings in force and swaps them
     * @param store {@link SettingsStore} writes settings so a restart reads them back
     * @param workingRootLock {@link WorkingRootLock} claims the working root for this process
     * @param jobRunner {@link JobRunner} says whether a job is running
     */
    public SettingsService(final LiveSettings live, final SettingsStore store,
                           final WorkingRootLock workingRootLock, final JobRunner jobRunner) {
        this.live = live;
        this.store = store;
        this.workingRootLock = workingRootLock;
        this.jobRunner = jobRunner;
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
     */
    @Override
    public void save(final Settings settings) {
        synchronized (this.saves) {
            final Settings previous = this.live.current();
            if (settings.paths().equals(previous.paths())) {
                this.writeAndApply(settings);
                return;
            }
            if (!this.jobRunner.runIfIdle(() -> this.moveRoots(settings, previous))) {
                throw new JobInProgressException();
            }
        }
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
