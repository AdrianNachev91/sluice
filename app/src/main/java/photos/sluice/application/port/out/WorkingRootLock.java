package photos.sluice.application.port.out;

import java.nio.file.Path;

/**
 * The effect boundary a process claims a working root through, before it mutates anything inside
 * that root. One process at a time, per root.
 *
 * <p>The claim is scoped to the root rather than to the app, so two separate libraries on one
 * machine stay independently usable.
 *
 * <p>Work that only reads never claims a root. A status check from elsewhere would otherwise fail
 * whenever the desktop app happens to be open, which teaches people to close it to look at
 * something. The readers already tolerate a half-written state by design.
 *
 * <p>What this actually prevents is two engines moving one tree, and the malformed hash-index row
 * two of them appending at once can leave behind. It is a convenience rather than a safety device.
 * A delete is authorized by a hash being present in an append-only index, and no interleaving can
 * invent a hash that was never committed. So the promise never to delete a file unless its bytes
 * survive elsewhere does not rest on this.
 */
public interface WorkingRootLock {

    /**
     * Claims workingRoot for this process and holds it until it is released or the process exits.
     *
     * <p>Nothing already held is given up here. A caller moving from one root to another therefore
     * holds both until it says which to give up, and neither root is unheld in between. What makes
     * that worth the second claim is that the caller's own work between the two calls can fail. A
     * save that has taken the root it is moving to, and then cannot write, still holds the root it
     * is moving from.
     *
     * <p>Two paths a caller reads as different roots can be one folder underneath, and an
     * implementation is free to recognise that. Acquiring such a folder a second time adds a hold on
     * the claim already there rather than a second claim, and {@link #release} then gives up one
     * hold rather than the folder. So a caller pairing each acquire with a release keeps what it
     * still means to hold, whichever way the two paths resolve.
     *
     * <p>A refused claim changes nothing. The caller still holds exactly what it held before.
     *
     * @param workingRoot {@link Path} the working root to claim
     * @throws WorkingRootBusyException if another process already holds workingRoot
     */
    void acquire(Path workingRoot);

    /**
     * Gives up one hold on a claimed root, naming it. Does nothing when this process does not hold
     * it. Where the same folder was acquired more than once, it stays held until the last of those
     * is given up.
     *
     * @param workingRoot {@link Path} the working root to give up
     */
    void release(Path workingRoot);

    /**
     * Gives up every root this process holds. Does nothing when it holds none.
     *
     * <p>For a caller that has to hand everything back without knowing what is held, which is what
     * an exit path needs. A caller giving up one root of several names it instead.
     */
    void releaseAll();
}
