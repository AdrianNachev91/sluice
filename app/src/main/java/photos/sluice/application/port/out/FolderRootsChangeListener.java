package photos.sluice.application.port.out;

/**
 * Told when a save has moved one of the folder roots, so a driving adapter can redo whatever it
 * does inside them.
 *
 * <p>A port rather than a direct call, because the work this triggers is not every caller's. Arming
 * a watcher is only worth doing in a process that stays open long enough for it to poll in, and a
 * one-shot command line writing a config value is not one. Implementations are injected as a list,
 * so a process that wants none simply registers none.
 *
 * <p>Any of the three roots fires it, not the working root alone. A watcher's auto-resume is
 * refused while any root is unusable. So repointing the library or the inbox is as much a reason to
 * re-arm as moving the working root is. A save that only changes a category or a grid fires
 * nothing.
 *
 * <p>It fires on the value changing, never on the folder behind it. Repairing a root by remounting
 * it leaves every setting identical, so no save happens and nothing here runs. That repair is
 * picked up at the next launch.
 *
 * <p>An implementation reports its own failures and returns. The save has already reached disk by
 * the time this runs, so throwing would turn a save that succeeded into one the caller sees fail.
 */
public interface FolderRootsChangeListener {

    /**
     * Runs after a save that moved a folder root, with the new settings already in force.
     *
     * <p>Which root moved is the caller's to say, because the two halves of the work disagree about
     * it. Anything the app keeps under the working root has moved out from under it, and only a
     * working-root move does that. Everything else a listener redoes is wanted whichever root
     * changed, since work is refused while any of the three is unusable.
     *
     * <p>Answered by comparing the configured strings, absolute and normalised, without following
     * links. Two spellings of one directory therefore read as a move. The consequence is bounded to
     * retiring watchers that did not need retiring, and the alternative resolves links on a path
     * that a save is allowed to name before it exists.
     *
     * @param workingRootMoved boolean whether this save moved the working root itself, rather than
     *         only the library or the inbox
     */
    void folderRootsChanged(boolean workingRootMoved);
}
