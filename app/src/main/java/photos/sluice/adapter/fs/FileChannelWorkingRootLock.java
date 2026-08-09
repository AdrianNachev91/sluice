package photos.sluice.adapter.fs;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The registry recognises a root by the path it resolves to, so any spelling of a claimed
 * directory is refused. Renaming that directory while its claim is live defeats that, since the new
 * name resolves somewhere the registry has never seen. The claim is still refused, by the lock
 * itself rather than the registry, at the cost of one leaked descriptor.
 *
 * <p>Second, the channel of a live claim stays open for the whole of that claim, and its path is
 * never reopened.
 *
 * <p>The marker file itself is left on disk after a release. Deleting it would race a process that
 * is opening it at that moment. An abandoned marker is an empty file and costs nothing.
 */
@Component
public class FileChannelWorkingRootLock implements WorkingRootLock {

    static final String LOCK_FILE_NAME = ".sluice-lock";

    private static final Logger log = LoggerFactory.getLogger(FileChannelWorkingRootLock.class);

    // Every root this process has claimed, however many instances of this class took them. The
    // constraint being modelled belongs to the process rather than to any one instance, so the
    // record of it has to as well.
    private static final Set<Path> CLAIMED_ROOTS = ConcurrentHashMap.newKeySet();

    // Guards the registry and every instance's claim together. Each acquire reads the registry, the
    // filesystem and its own claim field, then writes all three, and the answer is only correct if
    // nothing moves in between. A per-instance guard would order an instance against itself while
    // leaving it racing every other instance for the same process-wide registry.
    private static final Object CLAIMS = new Object();

    private @Nullable Claim claim;

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
     * Claims workingRoot, taking a new root before giving up a currently held one.
     *
     * <p>Guarded because a settings save can move the root while startup or shutdown is still
     * running, and both touch the same claim.
     *
     * @param workingRoot {@link Path} the working root to claim
     */
    @Override
    public void acquire(final Path workingRoot) {
        synchronized (CLAIMS) {
            final Path root = canonical(workingRoot);
            final Claim current = this.claim;
            if (current != null && current.root().equals(root)) {
                return;
            }
            // The new claim is taken first and recorded before the old one is given up. A refusal
            // throws out of claimOf() with the old root still held, which is what makes a rejected
            // settings save change nothing.
            this.claim = claimOf(root);
            if (current != null) {
                this.closeQuietly(current);
            }
        }
    }

    /**
     * Gives up the held root, if there is one.
     */
    @Override
    public void release() {
        synchronized (CLAIMS) {
            final Claim current = this.claim;
            if (current == null) {
                return;
            }
            this.claim = null;
            this.close(current);
        }
    }

    /**
     * Test seam: whether this instance currently holds workingRoot. Nothing in the app asks, since
     * a caller either took the root or was refused it.
     *
     * @param workingRoot {@link Path} the working root to check
     * @return boolean true when this instance holds exactly that root
     */
    boolean holds(final Path workingRoot) {
        synchronized (CLAIMS) {
            final Claim current = this.claim;
            return current != null && current.root().equals(canonical(workingRoot));
        }
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
     * <p>An instance method, and package-private, so a test can make giving up a claim fail. That
     * failure is the whole reason {@link #closeQuietly} exists, and no portable way to make a real
     * channel refuse to close is available.
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
     * path in a slightly different form would read as a different root. The user would then be
     * refused the folder they are already working in.
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
            throw closing(channel, new UncheckedIOException("Failed to claim working root " + root + ".", e));
        }
        if (lock == null) {
            // Another process holds it. Closing this channel is safe here, since the lock being
            // protected belongs to a different process and no descriptor of ours touches it.
            throw closing(channel, new WorkingRootBusyException(root));
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
     * Gives up the root a move has just replaced, reporting a failure to close rather than raising
     * it.
     *
     * <p>The claim on the new root is already taken and recorded by the time this runs. A close
     * failure raised here would tell the caller its claim was refused, while the caller does in fact
     * hold the new root. It would then go looking for the old one to take back.
     *
     * <p>Nothing is lost by not raising it. A failed close frees the registry key while the OS lock
     * may still be held, and this process cannot detect that either way. That trade is the same one
     * {@link #close} already documents.
     *
     * @param claim {@link Claim} the claim being given up
     */
    private void closeQuietly(final Claim claim) {
        try {
            this.close(claim);
        } catch (final UncheckedIOException e) {
            log.warn("Failed to release the claim on the previous working root {}.", claim.root(), e);
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
    private static <E extends RuntimeException> E closing(final FileChannel channel, final E failure) {
        try {
            channel.close();
        } catch (final IOException e) {
            failure.addSuppressed(e);
        }
        return failure;
    }
}
