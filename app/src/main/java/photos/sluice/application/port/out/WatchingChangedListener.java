package photos.sluice.application.port.out;

/**
 * Told when a save has changed whether Sluice watches for answers, so what is polling can be
 * brought into line with it.
 *
 * <p>The setting is read when a watch is armed and never again, so a save alone changes nothing
 * about what is already running. Turning watching off leaves every watcher polling, and a reader
 * sees a run resume itself minutes after they stopped it. Turning it on arms nothing, and a reader
 * who switched it on is watched over by nothing until the next launch.
 *
 * <p>A port rather than a direct call, for the reason {@link FolderRootsChangeListener} is one.
 * Watching only exists in a process that stays open long enough to poll in, and a one-shot command
 * line writing a config value is not one. Implementations are injected as a list, so a process that
 * wants none registers none.
 *
 * <p>An implementation reports its own failures and returns. The save has already reached disk by
 * the time this runs, so throwing would turn a save that succeeded into one the caller sees fail.
 */
public interface WatchingChangedListener {

    /**
     * Runs after a save that turned watching on or off, with the new settings already in force.
     *
     * @param watching boolean whether the save leaves Sluice watching
     */
    void watchingChanged(boolean watching);
}
