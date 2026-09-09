package photos.sluice.application.port.out;

import photos.sluice.domain.job.ProgressCallback;

/**
 * Told how far one file transfer has got, while it is still going.
 *
 * <p>A {@link ProgressCallback} counts whole units, so it says nothing at all between one file and
 * the next. On a small photo that gap is too short to see. On a large video over a slow connection
 * it is long enough that the only thing on screen describing the work stands still.
 *
 * <p>{@link #NONE} is what a caller passes when nothing is watching. A transfer given it still
 * reports its whole unit through whatever ticked it.
 */
@FunctionalInterface
public interface TransferProgress {

    /** Listens to nothing, for a transfer no screen is describing. */
    TransferProgress NONE = (_, _) -> {
    };

    /**
     * Reports how much of the file in flight has been written.
     *
     * @param written long bytes written so far
     * @param size long bytes the whole file holds
     */
    void moved(long written, long size);

    /**
     * Reports a transfer as part of the unit a {@link ProgressCallback} is counting.
     *
     * <p>The counted units stay whole. What this adds is where inside the current one the work has
     * reached.
     *
     * <p>A size of zero reports nothing rather than dividing by it. That is a guard on the arithmetic
     * rather than a case anything reaches: an empty file produces no block, so nothing calls this
     * for one, and it is described by its own whole-unit tick.
     *
     * @param progress {@link ProgressCallback} the callback counting whole units
     * @param done int units finished before this one
     * @param total int units in the whole run
     * @return {@link TransferProgress} a listener reporting through that callback
     */
    static TransferProgress within(final ProgressCallback progress, final int done, final int total) {
        return (written, size) -> {
            if (size > 0) {
                progress.partOf(done, total, (double) written / size);
            }
        };
    }
}
