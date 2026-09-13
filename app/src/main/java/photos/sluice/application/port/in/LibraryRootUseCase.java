package photos.sluice.application.port.in;

import photos.sluice.application.service.JobHandle;

import java.nio.file.Path;

/**
 * Moving the library root, which is the one settings change that cannot be a plain save.
 *
 * <p>Separate from {@link SettingsUseCase} because it is a different kind of act. An ordinary save
 * writes a file and swaps a reference. This one can copy a library, and a library is hundreds of
 * gigabytes, so it wants progress and cancellation of its own.
 *
 * <p>What travels with it is a {@link LibraryRootResolution}, stated by the caller. Enforcing that
 * here rather than in a dialog is the point: a second caller inherits the same question.
 */
public interface LibraryRootUseCase {

    /**
     * Moves the library root to the given folder, resolving the hash index the stated way.
     *
     * <p>Runs as a job, so the caller gets a handle back rather than waiting. The new root is put in
     * force inside that job, and only once the resolution's own work has finished. A cancelled copy
     * therefore leaves the app on the old library with its index still true of it.
     *
     * <p>Takes one folder rather than a settings value, and that is the whole of what it changes.
     * The working root and the inbox stay where they are. So this cannot move a claim, and it
     * cannot strand the watchers that poll under the working root. Every other setting is read when
     * the move lands, so anything saved while a library was copying survives it.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @param resolution {@link LibraryRootResolution} what to do about the hash index
     * @return a {@link JobHandle} of {@link LibraryRootMoveOutcome} a handle to the running move
     * @throws IllegalArgumentException if that folder is where the library already is. That is not
     *         a move, and an ordinary save through {@link SettingsUseCase#save} covers it
     * @throws PathsMisconfiguredException if that folder cannot be worked in beside the roots in
     *         force, or if those roots are themselves unusable
     * @throws UnfinishedRunsException if any sift run on disk has not finished
     * @throws RunsUnreadableException if the sift-prep root itself could not be read, leaving it
     *         unknown whether any run is unfinished
     * @throws JobInProgressException if a job is already running
     * @throws ShuttingDownException if the app is closing
     */
    JobHandle<LibraryRootMoveOutcome> moveLibraryRoot(Path newLibraryRoot, LibraryRootResolution resolution);
}
