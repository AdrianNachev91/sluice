package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.LibraryRootMoveOutcome;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.UnfinishedRunsException;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.domain.copy.CopySummary;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.PrepDirHealth.State;

import java.nio.file.Path;
import java.util.List;

/**
 * Moves the library root, taking the hash index with it the way the caller asked for.
 *
 * <p>Its own class rather than another method on {@link SettingsService}. That class is one short
 * story about the order a save claims, writes and puts settings in force. This is a different
 * story. It is what a library full of photos, and an index describing it, have to do before that
 * save is allowed to happen at all.
 *
 * <p>The save itself still goes through {@link SettingsService}, because the claim-write-apply
 * order is exactly what must not be written twice.
 *
 * <p>Both resolutions run as one job, so nothing else touches the trees while a library is being
 * copied. Both put the settings in force last. A copy that stops partway therefore leaves the app
 * on the old library, its index still true of it, and the partial copy sitting as inert files.
 */
@Component
public class LibraryRootMoveService implements LibraryRootUseCase {

    private static final String COPYING = "Copying the library...";

    private final SettingsService settings;
    private final JobRunner jobRunner;
    private final PhaseRunner phaseRunner;
    private final CopyEngine copyEngine;
    private final HashIndexPort hashIndex;
    private final PathsPort paths;
    private final PrepDirDoctor prepDirDoctor;
    private final RootsGuard rootsGuard;

    /**
     * Creates the move service.
     *
     * @param settings {@link SettingsService} performs the save once the resolution's work is done
     * @param jobRunner {@link JobRunner} runs the move as one cancellable job
     * @param copyEngine {@link CopyEngine} copies the old library into the new one
     * @param hashIndex {@link HashIndexPort} the index a fresh-index move files aside
     * @param paths {@link PathsPort} resolves the roots in force, before the move
     * @param prepDirDoctor {@link PrepDirDoctor} says which cull runs have not finished
     * @param pathValidation {@link PathValidationUseCase} checks the roots the app is running on
     * @param progressPort {@link ProgressPort} reports the copy's progress
     */
    public LibraryRootMoveService(final SettingsService settings, final JobRunner jobRunner,
                                  final CopyEngine copyEngine, final HashIndexPort hashIndex,
                                  final PathsPort paths, final PrepDirDoctor prepDirDoctor,
                                  final PathValidationUseCase pathValidation, final ProgressPort progressPort) {
        this.settings = settings;
        this.jobRunner = jobRunner;
        this.copyEngine = copyEngine;
        this.hashIndex = hashIndex;
        this.paths = paths;
        this.prepDirDoctor = prepDirDoctor;
        this.rootsGuard = new RootsGuard(pathValidation);
        this.phaseRunner = new PhaseRunner(progressPort);
    }

    /**
     * Moves the library root to the given folder.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @param resolution {@link LibraryRootResolution} what to do about the hash index
     * @return a {@link JobHandle} of {@link LibraryRootMoveOutcome} a handle to the running move
     */
    @Override
    public JobHandle<LibraryRootMoveOutcome> moveLibraryRoot(final Path newLibraryRoot,
                                                             final LibraryRootResolution resolution) {
        // Every check runs before the job takes the slot, so a refusal costs nothing and changes
        // nothing. Copying a library first and refusing afterwards is the failure this order exists
        // to rule out.
        this.rootsGuard.requireUsable();
        requireItNamesAFolder(newLibraryRoot);
        final Path movingTo = newLibraryRoot.toAbsolutePath().normalize();
        this.requireItIsActuallyMoving(movingTo);
        this.settings.requireLibraryRootIsUsable(movingTo);
        this.requireEveryRunHasFinished();
        final Path movingFrom = this.paths.library();
        return this.jobRunner.submit(handle -> switch (resolution) {
            case COPY_AND_KEEP_INDEX -> this.copyThenMove(movingFrom, movingTo, handle);
            case START_A_FRESH_INDEX -> this.fileTheIndexAsideThenMove(movingTo);
        });
    }

    /**
     * Copies the old library into the new one, and moves the root only once that has finished.
     *
     * <p>The order is the whole point. The copy runs while the settings still name the old library.
     * A cancelled or failed one therefore leaves the app entirely consistent, on the old library
     * with an index describing it.
     *
     * @param movingFrom {@link Path} the library root in force, which is what gets copied
     * @param movingTo {@link Path} the folder it is copied into, and the new root afterwards
     * @param handle a {@link JobHandle} of {@link LibraryRootMoveOutcome} this job's own handle,
     *         asked whether cancellation has been requested
     * @return {@link LibraryRootMoveOutcome} what happened
     */
    private LibraryRootMoveOutcome copyThenMove(final Path movingFrom, final Path movingTo,
                                                final JobHandle<LibraryRootMoveOutcome> handle) throws Exception {
        final CopySummary copy = this.phaseRunner.run(COPYING,
                progress -> this.copyEngine.copyTree(movingFrom, movingTo, progress,
                        handle::isCancellationRequested));
        if (copy.cancelled()) {
            return new LibraryRootMoveOutcome.CopyCancelled(copy.filesCopied(), copy.filesFound());
        }
        this.settings.saveMovingTheLibraryRoot(movingTo);
        return new LibraryRootMoveOutcome.CopiedAndMoved(copy.filesCopied(), copy.filesFound());
    }

    /**
     * Files the hash index aside and then moves the root, so duplicate detection starts over.
     *
     * <p>Filing is the work and the save is the commit, the same order the copy resolution runs in.
     * Saving first was the alternative and it loses on which wreckage it leaves. A filing that then
     * fails would put the app on the new library with an index still describing the old one, which
     * is the exact state this resolution exists to remove: the next sort reads a hash as already
     * safe somewhere the app has stopped filing into, and deletes the Inbox copy. This order fails
     * the other way, onto an old library with no index, where the cost is that dedup starts over.
     *
     * <p>Both windows are narrow enough that neither is likely, and that is what makes it a
     * judgement rather than an obvious call. Nothing can read the index between the two steps: this
     * runs as the job, one job runs at a time, and the save's only listener call no-ops while a job
     * is in flight.
     *
     * <p>The cost of the order chosen is that a save failing afterwards has already moved the file.
     * The outcome nobody receives is where its new name would have been, so the failure carries it
     * instead.
     *
     * @param movingTo {@link Path} the folder the library moves to
     * @return {@link LibraryRootMoveOutcome} what happened
     */
    private LibraryRootMoveOutcome fileTheIndexAsideThenMove(final Path movingTo) {
        final Path filedAt = this.paths.graveyard().resolve("library-hashes-" + DisasterTimestamp.now() + ".csv");
        final boolean thereWasOne = this.hashIndex.setAside(filedAt);
        try {
            this.settings.saveMovingTheLibraryRoot(movingTo);
        } catch (final RuntimeException e) {
            throw thereWasOne ? withTheIndexLocation(e, filedAt) : e;
        }
        return new LibraryRootMoveOutcome.MovedWithAFreshIndex(thereWasOne ? filedAt : null);
    }

    /**
     * Adds where the old index went to a failure that happened after it was filed aside.
     *
     * <p>Suppressed rather than wrapped, so a caller branching on the original type still can. The
     * path reaches a user through the failure's own report either way.
     *
     * @param failure {@link RuntimeException} what the save threw
     * @param filedAt {@link Path} where the old index was moved to
     * @return {@link RuntimeException} the same failure, now naming the index
     */
    private static RuntimeException withTheIndexLocation(final RuntimeException failure, final Path filedAt) {
        failure.addSuppressed(new IllegalStateException(
                "The previous hash index was moved to " + filedAt + " before this failed"));
        return failure;
    }

    /**
     * Refuses a path naming no folder at all.
     *
     * <p>An empty path is a legal one, and {@code toAbsolutePath} anchors it at whatever directory
     * the process was started in. So without this, a field left blank moves the library to the app's
     * own launch folder and copies the whole thing there. The seam took a settings value before this
     * one, and refused a blank library root then; it has to keep refusing it now.
     *
     * @param newLibraryRoot {@link Path} the folder the library would move to, as handed over
     * @throws IllegalArgumentException when the path names nothing
     */
    private static void requireItNamesAFolder(final Path newLibraryRoot) {
        if (newLibraryRoot.toString().isBlank()) {
            throw new IllegalArgumentException("A library-root move has to name the folder it is moving to");
        }
    }

    /**
     * Refuses a folder the library is already in.
     *
     * <p>Resolved rather than compared as text, matching the refusal on the ordinary save seam. Two
     * spellings of one folder are the same folder, and a copy between them would be a tree copied
     * into itself.
     *
     * @param movingTo {@link Path} the folder the library would move to, already resolved
     * @throws IllegalArgumentException when the library is already there
     */
    private void requireItIsActuallyMoving(final Path movingTo) {
        final String stayingAt = this.settings.settings().paths().libraryRoot();
        if (stayingAt != null && !stayingAt.isBlank()
                && movingTo.equals(Path.of(stayingAt).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "The library is already at " + movingTo + ", so this is not a move");
        }
    }

    /**
     * Refuses the move while any cull run on disk is short of complete.
     *
     * <p>Every state but complete counts, damaged included. A damaged run is one nobody has
     * established anything about, and treating an unknown as finished is the reading that lets the
     * stall through.
     *
     * @throws UnfinishedRunsException when a run has not finished
     */
    private void requireEveryRunHasFinished() {
        final List<String> unfinished = this.prepDirDoctor.runs(this.paths.cullPrep()).stream()
                .filter(run -> run.health().state() != State.COMPLETE)
                .map(CullRunSummary::scope)
                .toList();
        if (!unfinished.isEmpty()) {
            throw new UnfinishedRunsException(
                    "Sluice cannot move the library while " + unfinished.size()
                            + " cull run(s) are unfinished. Apply or discard them first.", unfinished);
        }
    }
}
