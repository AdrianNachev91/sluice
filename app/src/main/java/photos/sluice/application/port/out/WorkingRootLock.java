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
     * Claims workingRoot for this process and holds it until {@link #release()} or process exit.
     * Claiming a root this process already holds does nothing.
     *
     * <p>A process holding one root and claiming another takes the new one first, and gives the old
     * one up only once that succeeds. A refused claim therefore changes nothing: the caller still
     * holds what it held before.
     *
     * @param workingRoot {@link Path} the working root to claim
     * @throws WorkingRootBusyException if another process already holds workingRoot
     */
    void acquire(Path workingRoot);

    /**
     * Gives up whatever root this process holds. Does nothing when it holds none.
     */
    void release();
}
