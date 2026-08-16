package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.LiveSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingsSources;
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
 * <p>A save that moves the working root holds both roots across the write, and gives the old one up
 * only once the write has succeeded. There is therefore no window where this process has stopped
 * holding the root its own settings still name. A failed save has nothing to take back, so it
 * cannot be refused a folder it needs, and cannot end up holding neither.
 *
 * <p>A folder root that moves is checked before any of that. A root set to somewhere that cannot be
 * worked in is refused. That covers text naming no path at all, a folder no directory could be
 * confirmed at, one that is there and cannot be resolved, and two roots put inside each other. A
 * root left unset is not refused. An install chooses its three folders one picker at a time, and
 * the facade goes on refusing jobs while any of them is empty.
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
    private final SettingsSources settingsSources;
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
     * @param settingsSources {@link SettingsSources} says what outranks the user's config file
     * @param folderRootsListeners a {@link List} of {@link FolderRootsChangeListener} told once a
     *         save has moved any folder root, empty in a process that wants none
     */
    public SettingsService(final LiveSettings live, final SettingsStore store,
                           final WorkingRootLock workingRootLock, final JobRunner jobRunner,
                           final PathValidationUseCase pathValidation,
                           final SettingsSources settingsSources,
                           final List<FolderRootsChangeListener> folderRootsListeners) {
        this.live = live;
        this.store = store;
        this.workingRootLock = workingRootLock;
        this.jobRunner = jobRunner;
        this.pathValidation = pathValidation;
        this.settingsSources = settingsSources;
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
     * What supplies the given setting from above the user's config file.
     *
     * @param property {@link String} the property name, as the app spells it in its own config file
     * @return an {@link Optional} of {@link SettingOverride} what supplies it from above
     */
    @Override
    public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
        return this.settingsSources.overriddenAboveTheConfigFile(property);
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
            this.requireUsableRoots(settings.paths());
            requireTheLibraryRootStaysPut(settings, previous);
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
     * Moves the library root, for a caller already holding the job slot.
     *
     * <p>Package-private, and the one way past the refusal above. What it leaves out is the job
     * gate, because {@link LibraryRootMoveService} is running as the job. Asking for the slot again
     * would be asking itself. Everything the gate protects is still true: no other job can start
     * while that one runs.
     *
     * <p>Takes the folder rather than a whole settings value. That is what keeps a move honest
     * across the time one takes. Everything else is read from the settings in force at this moment,
     * so a category saved while a library was copying is still there afterwards.
     *
     * <p>No claim is taken or given up. The working root is whatever was already in force, so there
     * is no root to move a claim between.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @throws PathsMisconfiguredException if that folder cannot be worked in beside the other roots
     */
    void saveMovingTheLibraryRoot(final Path newLibraryRoot) {
        synchronized (this.saves) {
            final Settings moved = this.withLibraryRootAt(newLibraryRoot);
            this.requireUsableRoots(moved.paths());
            this.writeAndApply(moved);
            // False by construction rather than computed: this cannot be the save that moves it.
            this.announceFolderRootsChange(false);
        }
    }

    /**
     * Refuses a library root that cannot be worked in, without saving anything.
     *
     * <p>For a caller about to spend a long time preparing the move. It can be told now rather than
     * after a library has been copied. The save above checks again against the settings in force at
     * that moment, which is what actually guarantees it. This one is the courtesy.
     *
     * @param newLibraryRoot {@link Path} the folder the library would move to
     * @throws PathsMisconfiguredException if that folder cannot be worked in beside the other roots
     */
    void requireLibraryRootIsUsable(final Path newLibraryRoot) {
        this.requireUsableRoots(this.withLibraryRootAt(newLibraryRoot).paths());
    }

    /**
     * The settings in force with the library root replaced, and every other value left as it is.
     *
     * <p>Built from the settings in force rather than from a value a caller passed earlier. A
     * library copy takes as long as a library is big. Anything saved while it ran would otherwise
     * be written back as it was before it started.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @return {@link Settings} the settings this move would put in force
     */
    private Settings withLibraryRootAt(final Path newLibraryRoot) {
        final Settings current = this.live.current();
        final PathSettings paths = current.paths();
        return new Settings(new PathSettings(paths.repoRoot(), newLibraryRoot.toString(), paths.inbox()),
                current.provider(), current.providerSettings(), current.categories(),
                current.externalAgent(), current.montage());
    }

    /**
     * Refuses a plain save that would move the library root away from a folder already configured.
     *
     * <p>Moving it strands the hash index on the old library, and that index is what authorizes
     * deleting an Inbox file as already safe. There is no answer to that a caller can be assumed to
     * want, so the seam asks for one rather than choosing. Naming no folder at all is refused the
     * same way. Otherwise a clear followed by a set would be two permitted saves adding up to the
     * move this refuses.
     *
     * <p>An install that has never had a library root is not moving one. Nothing is stranded and
     * the index is empty, so a first run saves through here like any other setting.
     *
     * <p>Runs after the roots are checked, on the same reasoning that puts that check ahead of the
     * job gate. An unusable folder names a value the user can go and correct, and it is the same
     * answer whatever else is also true. Which flow a usable move belongs in is the next question,
     * not the first one.
     *
     * @param settings {@link Settings} the settings this save would put in force
     * @param previous {@link Settings} the settings running now
     * @throws LibraryRootMoveNeedsAResolutionException when a configured library root would change
     */
    private static void requireTheLibraryRootStaysPut(final Settings settings, final Settings previous) {
        final Optional<Path> was = libraryRoot(previous);
        if (was.isEmpty() || was.equals(libraryRoot(settings))) {
            return;
        }
        throw new LibraryRootMoveNeedsAResolutionException(
                "Moving the library away from " + was.get() + " leaves Sluice's record of what it holds behind."
                        + " Say whether to copy the library across or start that record fresh.");
    }

    /**
     * The library root the given settings name, as the folder a move is judged against.
     *
     * <p>Resolved rather than compared as text, the same way the working root is. Two spellings of
     * one folder are not a move. Asking a user to state a resolution for one would be asking about
     * a library that is not going anywhere.
     *
     * @param settings {@link Settings} the settings to read the library root from
     * @return an {@link Optional} of {@link Path} the library root, empty when none is configured
     */
    private static Optional<Path> libraryRoot(final Settings settings) {
        return Optional.ofNullable(settings.paths().libraryRoot())
                .filter(libraryRoot -> !libraryRoot.isBlank())
                .map(libraryRoot -> Path.of(libraryRoot).toAbsolutePath().normalize());
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
     * <p>Both roots are held across the write, and the one being left is given up only once the
     * write has succeeded. So there is no moment where this process has stopped holding the root its
     * settings still name. A write that fails needs nothing put back: the old claim was never let
     * go, and the only claim to undo is the one this save took.
     *
     * @param settings {@link Settings} the settings to save
     * @param previous {@link Settings} the settings in force until this succeeds
     */
    private void moveRoots(final Settings settings, final Settings previous) {
        final Optional<Path> movingTo = workingRoot(settings);
        final Optional<Path> heldUntilNow = workingRoot(previous);
        if (movingTo.equals(heldUntilNow)) {
            // The inbox moved and the working root stayed put, so there is no claim to move. The job
            // gate still applied above, since a run reads all three roots.
            this.writeAndApply(settings);
            return;
        }
        movingTo.ifPresent(this.workingRootLock::acquire);
        try {
            this.writeAndApply(settings);
        } catch (final RuntimeException e) {
            movingTo.ifPresent(root -> this.releaseReporting(root, e));
            throw e;
        }
        // Covers both shapes of a successful move. A root the settings moved off, and a root the
        // settings replaced with nothing at all. Left held, either would lock a folder this process
        // is no longer working in against every other Sluice for as long as it runs.
        heldUntilNow.ifPresent(this::releaseOnceTheSaveHasLanded);
    }

    /**
     * Tells every listener a folder root has moved.
     *
     * <p>Called from {@link #save} with the job slot still held shut, which is what makes it worth
     * the delay it costs every waiting {@code submit}. A listener re-arms watchers, and arming is
     * skipped while a job runs. Announced after the slot was free, a watcher armed under the old
     * root could take that slot in between. The re-arm would then silently do nothing for the life
     * of the process.
     *
     * <p>{@link #saveMovingTheLibraryRoot} is the opposite case and needs none of that. It runs as
     * the job, so a listener's arming step self-skips. Nothing is owed: only the library root moved,
     * every prep dir is where it was, and the watchers polling them were never stranded.
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
     * Gives up the claim a failed save took, reporting a failure to do so against the failure
     * already on its way out rather than in place of it.
     *
     * <p>The root the settings still in force name is untouched here, because this save never let
     * go of it. Nothing has to be taken back, so nothing can be refused.
     *
     * @param root {@link Path} the working root this save claimed
     * @param failure {@link RuntimeException} the save failure the caller is about to throw
     */
    private void releaseReporting(final Path root, final RuntimeException failure) {
        try {
            this.workingRootLock.release(root);
        } catch (final RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Gives up the root a successful save has moved off, logging a failure to do so rather than
     * raising it.
     *
     * <p>The settings have reached disk and are in force by the time this runs. A caller told the
     * save failed would be told something untrue, and would have no step left to retry. The
     * listeners after this would also never run, leaving watchers armed for a root the app has
     * moved off.
     *
     * @param root {@link Path} the working root the save moved off
     */
    private void releaseOnceTheSaveHasLanded(final Path root) {
        try {
            this.workingRootLock.release(root);
        } catch (final RuntimeException e) {
            log.warn("The save landed, but the claim on the previous working root {} was not given up", root, e);
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
