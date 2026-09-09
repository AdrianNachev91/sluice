package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.WorkingRootBusyException;
import photos.sluice.application.port.out.WorkingRootLock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claims a working root by holding an OS file lock on a marker file inside it, for as long as this
 * process holds the root.
 *
 * <p>The kernel drops that lock when the process dies, however it dies. That is the property this
 * needs, and it is why the marker is a lock rather than a file naming a process id. A deliberate
 * force-quit or a kill leaves nothing behind to clean up.
 *
 * <p>An OS file lock belongs to the whole process, not to the object that took it. Two facts follow,
 * and both shape the code below.
 *
 * <p>First, a root already claimed here is refused by the registry this class keeps, before a second
 * channel is opened on its marker. On Linux the kernel releases a lock of this kind as soon as any
 * descriptor for that file closes in the same process. A refusal that opened the marker and closed
 * it again would therefore release the lock the first claim still believes it holds.
 *
 * <p>Second, the channel of a live claim stays open for the whole of that claim, and its path is
 * never reopened.
 *
 * <p>The marker file itself is left on disk after a release. Deleting it would race a process that
 * is opening it at that moment. An abandoned marker is an empty file and costs nothing.
 *
 * <p>Flowchart, root identity, the renamed-root gap and what the claim is worth:
 * {@code app/docs/design/adapter/fs/working-root-lock.md}.
 */
@Component
public class FileChannelWorkingRootLock implements WorkingRootLock {

    static final String LOCK_FILE_NAME = ".sluice-lock";

    // Every root this process has claimed, however many instances of this class took them. The
    // constraint being modelled belongs to the process rather than to any one instance, so the
    // record of it has to as well.
    private static final Set<Path> CLAIMED_ROOTS = ConcurrentHashMap.newKeySet();

    // Guards the registry and every instance's claim together. Each acquire reads the registry, the
    // filesystem and its own claim field, then writes all three, and the answer is only correct if
    // nothing moves in between. A per-instance guard would order an instance against itself while
    // leaving it racing every other instance for the same process-wide registry.
    private static final Object CLAIMS = new Object();

    // Every root this instance holds, keyed by the canonical form of that root. More than one entry
    // only while a settings save moves the working root. The new root is taken while the old one is
    // still held, so a write that fails leaves the old claim exactly where it was.
    //
    // Keyed by path. A claimed directory renamed or deleted underneath its claim stops matching that
    // key, so release() misses it and the descriptor is held until the process ends. The kernel
    // still drops the lock at exit.
    private final Map<Path, Held> claims = new HashMap<>();

    /**
     * One held claim: the root it covers, and the open channel and lock keeping it.
     *
     * <p>Package-private rather than private, so a test overriding {@link #close} can name what it
     * is being handed.
     *
     * @param root {@link Path} the claimed working root, in canonical form
     * @param channel {@link FileChannel} the open channel the lock is held on
     * @param lock {@link FileLock} the OS lock itself
     */
    record Claim(Path root, FileChannel channel, FileLock lock) {
    }

    /**
     * One claim and how many callers are holding it.
     *
     * <p>The count exists because two paths a caller reads as different roots can canonicalise to
     * one, and the folder has to survive a caller releasing what it believes is the other. So a
     * claim is given up when its last holder does, rather than on the first release.
     *
     * @param claim {@link Claim} the held claim
     * @param holders int how many acquires are outstanding against it
     */
    private record Held(Claim claim, int holders) {

        private Held plusHolder() {
            return new Held(this.claim, this.holders + 1);
        }

        private Held minusHolder() {
            return new Held(this.claim, this.holders - 1);
        }
    }

    /**
     * Claims workingRoot, keeping every root this instance already holds.
     *
     * <p>Guarded because a settings save can move the root while startup or shutdown is still
     * running, and all three touch the same claims.
     *
     * <p>A root already held gains a holder and nothing else. No marker file is opened and no lock
     * is taken, and that is the point rather than a shortcut. A second channel on a marker this
     * process already holds would hand the first claim's lock away as soon as either descriptor
     * closed.
     *
     * @param workingRoot {@link Path} the working root to claim
     */
    @Override
    public void acquire(final Path workingRoot) {
        synchronized (CLAIMS) {
            final Path root = canonical(workingRoot);
            final Held current = this.claims.get(root);
            if (current != null) {
                this.claims.put(root, current.plusHolder());
                return;
            }
            // Nothing is given up here. A refusal throws out of claimOf() with every existing claim
            // untouched, which is what makes a rejected settings save change nothing.
            this.claims.put(root, new Held(claimOf(root), 1));
        }
    }

    /**
     * Gives up one hold on a claimed root, leaving any other root this instance holds.
     *
     * <p>The claim itself goes when its last holder does. A caller that took the same folder twice,
     * under two spellings it read as different roots, still holds it after giving one of them up.
     *
     * @param workingRoot {@link Path} the working root to give up
     */
    @Override
    public void release(final Path workingRoot) {
        synchronized (CLAIMS) {
            final Path root = canonical(workingRoot);
            final Held current = this.claims.get(root);
            if (current == null) {
                return;
            }
            if (current.holders() > 1) {
                this.claims.put(root, current.minusHolder());
                return;
            }
            this.claims.remove(root);
            this.close(current.claim());
        }
    }

    /**
     * Gives up every root this instance holds, however many holders each has.
     *
     * <p>For a caller handing everything back at once, where counting holders would only leave a
     * claim behind. Every claim is attempted even after one fails to close, and one failure carries
     * the rest. Stopping at the first would strand the claims after it for the life of the process,
     * which is the one outcome an exit path exists to avoid.
     */
    @Override
    public void releaseAll() {
        synchronized (CLAIMS) {
            final List<Held> held = List.copyOf(this.claims.values());
            this.claims.clear();
            UncheckedIOException failure = null;
            for (final Held entry : held) {
                try {
                    this.close(entry.claim());
                } catch (final UncheckedIOException e) {
                    failure = combineFailures(failure, e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Whether this instance currently holds workingRoot.
     *
     * @param workingRoot {@link Path} the working root to check
     * @return boolean true when this instance holds that root
     */
    boolean holds(final Path workingRoot) {
        synchronized (CLAIMS) {
            return this.claims.containsKey(canonical(workingRoot));
        }
    }

    /**
     * How many outstanding acquires this instance has against workingRoot.
     *
     * <p>A count rather than a boolean, because a folder reached under two spellings is only
     * distinguishable from an ordinary single hold by the number.
     *
     * @param workingRoot {@link Path} the working root to count holders for
     * @return int how many acquires are outstanding, zero when the root is not held
     */
    int holdersOf(final Path workingRoot) {
        synchronized (CLAIMS) {
            final Held current = this.claims.get(canonical(workingRoot));
            return current == null ? 0 : current.holders();
        }
    }

    /**
     * Keeps one failure of a run to report and hangs every later one off it as suppressed.
     *
     * <p>Which one is kept is whichever the map handed back first, and that order is arbitrary. All
     * of them are carried either way, and nothing acts on a close failure beyond reporting it.
     *
     * @param kept {@link UncheckedIOException} the failure kept so far, null until one happens
     * @param next {@link UncheckedIOException} the failure just caught
     * @return {@link UncheckedIOException} the failure to keep
     */
    private static UncheckedIOException combineFailures(final @Nullable UncheckedIOException kept,
                                                        final UncheckedIOException next) {
        if (kept == null) {
            return next;
        }
        kept.addSuppressed(next);
        return kept;
    }

    /**
     * Releases a claim by closing its channel, which drops the OS lock with it. The registry entry
     * is given up after the close rather than before. For an ordinary release it therefore covers
     * the root for as long as a lock on it can exist.
     *
     * <p>It goes in a finally, which is a deliberate trade rather than an oversight. A close that
     * fails frees the registry key while the OS lock may still be held, and nothing in this process
     * can then detect that. The alternative strands the root until the process exits, which is
     * worse and far easier to hit.
     *
     * <p>An instance method, and package-private, so a test can make giving up a claim fail. There
     * is no portable way to make a real channel refuse to close.
     *
     * @param claim {@link Claim} the claim to give up
     */
    void close(final Claim claim) {
        try {
            claim.channel().close();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to release the claim on working root " + claim.root() + ".", e);
        } finally {
            CLAIMED_ROOTS.remove(claim.root());
        }
    }

    /**
     * Reduces a path to one agreed form, so that two ways of writing the same folder count as the
     * same folder. A relative step, a symlink and a junction all disappear here.
     *
     * <p>Everything this class stores or compares goes through it first. Without that, saving a
     * path in a slightly different form would refuse the user the folder they are already working
     * in.
     *
     * @param workingRoot {@link Path} the working root as configured
     * @return {@link Path} the form this class stores and compares by
     */
    private static Path canonical(final Path workingRoot) {
        // Tidy first, follow second. toRealPath() fails on any part of a path that is not on disk.
        // A step like "sub/.." can name a folder that never existed. Tidying removes the step, so
        // what gets followed is a path that is really there.
        final Path normalized = workingRoot.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (final IOException e) {
            // The folder itself is missing, so there is nothing to follow and the tidied path is
            // the best form available. Claiming it fails a moment later, on opening the marker,
            // and says so in terms of the path the user configured.
            return normalized;
        }
    }

    /**
     * Registers the root as this process's, then locks its marker file.
     *
     * @param root {@link Path} the working root to claim, in canonical form
     * @return {@link Claim} the held claim
     */
    private static Claim claimOf(final Path root) {
        if (!CLAIMED_ROOTS.add(root)) {
            throw new WorkingRootBusyException(root);
        }
        try {
            return lockedClaim(root);
        } catch (final RuntimeException e) {
            CLAIMED_ROOTS.remove(root);
            throw e;
        }
    }

    /**
     * Opens the marker file under root and takes the OS lock on it.
     *
     * @param root {@link Path} the working root to claim, in canonical form
     * @return {@link Claim} the held claim
     */
    private static Claim lockedClaim(final Path root) {
        final Path lockFile = root.resolve(LOCK_FILE_NAME);
        final FileChannel channel = open(lockFile, root);
        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (final OverlappingFileLockException e) {
            // Reached only when the registry did not recognise the root as one this process holds,
            // which a rename underneath a live claim can do. The channel is left open on purpose.
            // Closing it is exactly what would release the other claim's lock on Linux, and a
            // leaked descriptor costs less than a claim that silently stops holding anything.
            throw new WorkingRootBusyException(root, e);
        } catch (final IOException e) {
            throw closeAndAttach(channel, new UncheckedIOException("Failed to claim working root " + root + ".", e));
        }
        if (lock == null) {
            // Another process holds it. Closing this channel is safe here, since the lock being
            // protected belongs to a different process and no descriptor of ours touches it.
            throw closeAndAttach(channel, new WorkingRootBusyException(root));
        }
        return new Claim(root, channel, lock);
    }

    /**
     * Opens the marker file for writing, creating it when it does not exist yet.
     *
     * @param lockFile {@link Path} the marker file to open
     * @param root {@link Path} the working root it sits in, for the failure message
     * @return {@link FileChannel} the open channel
     */
    private static FileChannel open(final Path lockFile, final Path root) {
        try {
            return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to open the lock file in working root " + root + ".", e);
        }
    }

    /**
     * Closes a channel on the way out of a failed claim, attaching any close failure to the failure
     * already being reported rather than replacing it.
     *
     * @param channel {@link FileChannel} the channel to close
     * @param failure E the failure that is about to be thrown
     * @return E that same failure, for the caller to throw
     */
    private static <E extends RuntimeException> E closeAndAttach(final FileChannel channel, final E failure) {
        try {
            channel.close();
        } catch (final IOException e) {
            failure.addSuppressed(e);
        }
        return failure;
    }
}
