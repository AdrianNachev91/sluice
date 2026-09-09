package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.copy.CopySummary;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.paths.Containment;

import java.nio.file.Path;
import java.util.List;

/**
 * Copies one directory tree into another, keeping each file's position relative to the root it came
 * from.
 *
 * <p>Copies rather than moves, and never deletes. A caller wanting the source gone deletes it
 * itself, once it has looked at what arrived. Nothing here decides that a copy went well enough for
 * the original to go.
 *
 * <p>Interruption leaves whole files rather than half of one, because a destination that already
 * holds a name is written beside it rather than over it. So a run stopped partway can be started
 * again, and the second run's arrivals sit beside the first's rather than replacing them. That is
 * the never-overwrite rule the rest of the app moves files under.
 */
@Component
public class CopyEngine {

    private final MediaStore mediaStore;

    /**
     * Creates the engine over the store it copies through.
     *
     * @param mediaStore {@link MediaStore} lists the source and writes each copy
     */
    public CopyEngine(final MediaStore mediaStore) {
        this.mediaStore = mediaStore;
    }

    /**
     * Copies every file under source into destination, keeping each one's relative position.
     *
     * <p>The file list is taken once, before anything is written. A destination inside the source
     * would otherwise be a set that grows as it is walked. It is refused below anyway, and the
     * snapshot is what makes that refusal the only thing standing between here and a loop.
     *
     * @param source {@link Path} the directory to copy from
     * @param destination {@link Path} the directory to copy into, created if it is not there
     * @param progress {@link ProgressCallback} ticked once per file
     * @param cancelled {@link CancellationSignal} asked before each file
     * @return {@link CopySummary} how many files were found and copied, and whether it stopped early
     * @throws IllegalArgumentException when one of the two directories lies inside the other, or
     *         they are the same directory
     */
    public CopySummary copyTree(final Path source, final Path destination, final ProgressCallback progress,
                                final CancellationSignal cancelled) {
        requireSeparateTrees(source, destination);
        // Before the file list rather than per file, so an empty source still leaves the folder it
        // was asked to copy into. Copying nothing and creating nothing reports a success that left
        // no trace, which a caller cannot tell from a copy that never ran.
        this.mediaStore.ensureDirectory(destination);
        final List<Path> files = this.mediaStore.listFiles(source).stream()
                .filter(file -> !MediaStore.isIncompleteTransfer(file))
                .toList();
        final int total = files.size();
        int copied = 0;
        int seen = 0;
        try {
            for (final Path file : files) {
                if (cancelled.isCancelled()) {
                    return new CopySummary(copied, total, true);
                }
                if (this.copyKeepingItsPlace(source, destination, file, cancelled,
                        TransferProgress.within(progress, seen, total))) {
                    copied++;
                }
                progress.tick(++seen, total);
            }
        } catch (final TransferAbandonedException e) {
            // Reported the way a stop between files is, because it is one. The abandoned file left
            // no part-written copy behind, so a run started again lands it whole.
            return new CopySummary(copied, total, true);
        }
        return new CopySummary(copied, total, false);
    }

    /**
     * Copies one file into the place under destination that matches where it sat under source,
     * unless that place already holds it.
     *
     * <p>Already-there is judged by name and size. Both sides are the same tree in the same
     * operation, so a match in the position this file would land in is this file. A run started
     * again after a cancelled one therefore carries on. Without it the never-overwrite rule would
     * land every already-copied file a second time beside itself, and a library copy cancelled once
     * and retried would hold two of everything that arrived before the cancel.
     *
     * <p>A same-named file of a different size is a different file, and lands beside it. That is the
     * never-overwrite rule doing the job it exists for rather than a duplicate.
     *
     * <p>Name and size rather than content. A hash of every file in a library costs a full read of
     * it, which is the copy this is trying to avoid repeating. What that trades away is a source
     * file edited to exactly its old size between two runs of the same copy.
     *
     * @param source {@link Path} the directory being copied from
     * @param destination {@link Path} the directory being copied into
     * @param file {@link Path} the file to copy
     * @param cancelled {@link CancellationSignal} asked while the file's bytes are moving
     * @param watching {@link TransferProgress} told how far this file's bytes have got
     * @return boolean true when this run wrote it, false when it was already there
     * @throws TransferAbandonedException if cancelled escalated before the copy finished
     */
    private boolean copyKeepingItsPlace(final Path source, final Path destination, final Path file,
                                        final CancellationSignal cancelled,
                                        final TransferProgress watching) {
        final Path relative = source.relativize(file);
        final Path parent = relative.getParent();
        final Path targetDirectory = parent == null ? destination : destination.resolve(parent);
        final Path alreadyThere = targetDirectory.resolve(file.getFileName());
        if (this.mediaStore.exists(alreadyThere) && this.mediaStore.size(alreadyThere) == this.mediaStore.size(file)) {
            return false;
        }
        this.mediaStore.ensureDirectory(targetDirectory);
        this.mediaStore.copy(file, targetDirectory, cancelled, watching);
        return true;
    }

    /**
     * Refuses two directories that cannot be copied between.
     *
     * <p>A destination inside the source would be written into while it is being read from. A source
     * inside the destination would be copied into its own parent, doubling every file it holds.
     * Neither is a state a user reaches on purpose, and both are cheap to name before anything moves.
     *
     * @param source {@link Path} the directory to copy from
     * @param destination {@link Path} the directory to copy into
     * @throws IllegalArgumentException when the two overlap
     */
    private static void requireSeparateTrees(final Path source, final Path destination) {
        final Path from = source.toAbsolutePath().normalize();
        final Path to = destination.toAbsolutePath().normalize();
        if (from.equals(to) || Containment.strictlyUnder(from, to) || Containment.strictlyUnder(to, from)) {
            throw new IllegalArgumentException(
                    "Refusing to copy " + from + " into " + to + ", because one lies inside the other");
        }
    }
}
