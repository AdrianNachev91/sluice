package photos.sluice.domain.rescue;

/**
 * Outcome counters from one rescue run.
 *
 * <p>{@code rescued} and {@code undated} are disjoint, and both count a file that moved. A file
 * something could date lands under its year and month. One nothing could date lands flat in the
 * undated folder, so a rescue leaves nothing behind for want of a date.
 *
 * <p>{@code alreadyInSorted} is disjoint from both, and counts a file that was deleted rather than
 * moved, its own bytes having been found at the destination first. A near-copy group is where they
 * come from: it holds a byte copy of the photo it kept, whose original never left Sorted.
 *
 * <p>{@code leftBehind} counts the media files the run never reached, still in the folder it was
 * given. The notes and non-media beside them are not in it. Those are not what a reader sees left
 * behind, and a finished run leaves them too.
 *
 * <p>{@code folderRemoved} is true only where the pass reached every file. A folder still holding
 * something a rescue does not move, such as a stray file that is not media, survives it.
 *
 * <p>{@code cancelled} is the run's own account of whether it stopped short.
 *
 * @param rescued int files moved under a year and month
 * @param undated int files moved into the undated folder, nothing having dated them
 * @param alreadyInSorted int files deleted, their own bytes already at the destination
 * @param leftBehind int media files the run never reached, still in the folder
 * @param folderRemoved boolean whether the source folder was removed
 * @param cancelled boolean whether the run gave up before reaching every file
 */
public record RescueSummary(int rescued, int undated, int alreadyInSorted, int leftBehind,
                            boolean folderRemoved, boolean cancelled) {

    /**
     * Every file this run moved, wherever it landed.
     *
     * <p>What was already in Sorted is not among them. Nothing was carried anywhere for those, and
     * a reader counting what arrived would be counting one photo twice.
     *
     * @return int the dated and undated files together
     */
    public int moved() {
        return this.rescued + this.undated;
    }
}
