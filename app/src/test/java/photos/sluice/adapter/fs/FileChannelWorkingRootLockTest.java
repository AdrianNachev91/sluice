package photos.sluice.adapter.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.application.port.out.WorkingRootBusyException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class FileChannelWorkingRootLockTest {

    private static final int PROCESS_TIMEOUT_SECONDS = 60;

    // Every lock built by a test, released afterwards whether the test passed or not. A claim left
    // behind fails the next test for an unrelated reason, and on Windows breaks the temp-directory
    // cleanup on a handle still open.
    private final List<FileChannelWorkingRootLock> locks = new ArrayList<>();

    @AfterEach
    void releaseLocks() {
        // Every lock is attempted, so one failing release cannot leave the ones after it holding
        // roots. The first failure is still reported rather than swallowed.
        RuntimeException failure = null;
        for (final var lock : this.locks) {
            try {
                lock.releaseAll();
            } catch (final RuntimeException e) {
                failure = failure == null ? e : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void acquireClaimsTheRootAndCreatesItsMarkerFile(@TempDir final Path root) {
        final var lock = this.lock();

        lock.acquire(root);

        assertThat(lock.holds(root)).isTrue();
        assertThat(root.resolve(FileChannelWorkingRootLock.LOCK_FILE_NAME)).exists();
    }

    // Two claims on one root inside a single process. The refusal has to arrive before a second
    // channel is ever opened on the marker. On Linux, closing any descriptor for that file releases
    // the lock the first claim holds. A refusal that opened and closed one would therefore hand the
    // root away while both claims still looked intact.
    @Test
    void acquireRefusesARootThisProcessAlreadyHolds(@TempDir final Path root) throws Exception {
        final var first = this.lock();
        first.acquire(root);

        final var refusal = catchThrowableOfType(WorkingRootBusyException.class, () -> this.lock().acquire(root));

        assertThat(refusal.workingRoot()).isEqualTo(root.toRealPath());
        assertThat(first.holds(root)).isTrue();
    }

    // The refusal a second Sluice install gets, which no in-process test can reach.
    @Test
    void acquireRefusesARootHeldByASeparateProcess(@TempDir final Path root) throws Exception {
        final Process holder = startHolder(root);
        try {
            assertThatThrownBy(() -> this.lock().acquire(root)).isInstanceOf(WorkingRootBusyException.class);
        } finally {
            stopHolder(holder);
        }
    }

    // The other half: once that process is gone, the root is free again with nothing to clean up.
    @Test
    void aRootIsFreeAgainOnceTheHoldingProcessExits(@TempDir final Path root) throws Exception {
        final Process holder = startHolder(root);
        try {
            stopHolder(holder);
        } finally {
            holder.destroyForcibly();
        }
        final var lock = this.lock();

        lock.acquire(root);

        assertThat(lock.holds(root)).isTrue();
    }

    // A holder killed outright never runs a line of its own cleanup, so the kernel is the only
    // thing that can free the root.
    @Test
    void aRootIsFreeAgainOnceTheHoldingProcessIsKilled(@TempDir final Path root) throws Exception {
        killHolder(startHolder(root));
        final var lock = this.lock();

        lock.acquire(root);

        assertThat(lock.holds(root)).isTrue();
    }

    @Test
    void releaseLetsAnotherHolderTakeTheRoot(@TempDir final Path root) {
        final var first = this.lock();
        first.acquire(root);
        first.release(root);
        final var second = this.lock();

        second.acquire(root);

        assertThat(second.holds(root)).isTrue();
        assertThat(first.holds(root)).isFalse();
    }

    @Test
    void acquireOfTheSameRootTwiceCountsASecondHolder(@TempDir final Path root) {
        final var lock = this.lock();
        lock.acquire(root);

        lock.acquire(root);

        assertThat(lock.holds(root)).isTrue();
        assertThat(lock.holdersOf(root)).isEqualTo(2);
    }

    @Test
    void aRootAcquiredTwiceStaysHeldUntilBothHoldsAreGivenUp(@TempDir final Path root) {
        final var lock = this.lock();
        lock.acquire(root);
        lock.acquire(root);

        lock.release(root);

        assertThat(lock.holds(root)).isTrue();
        // Still genuinely locked, not merely still recorded. A second instance stands in for the
        // next process to try the folder.
        assertThatThrownBy(() -> this.lock().acquire(root)).isInstanceOf(WorkingRootBusyException.class);

        lock.release(root);

        assertThat(lock.holds(root)).isFalse();
        this.lock().acquire(root);
    }

    // The same root reached by a different spelling is the same root. Otherwise a settings save
    // that only re-spells the path would refuse the user the root they are already working in.
    @Test
    void acquireTreatsAnUnnormalizedPathAsTheSameRoot(@TempDir final Path root) {
        final var lock = this.lock();
        lock.acquire(root);

        lock.acquire(root.resolve("sub").resolve(".."));

        assertThat(lock.holds(root)).isTrue();
    }

    // A caller moving between roots holds both until it says which to give up. So the root its own
    // settings still name is never unheld, and nothing else can take it while the move is in doubt.
    @Test
    void acquireOfANewRootKeepsTheOldOne(@TempDir final Path oldRoot, @TempDir final Path newRoot) {
        final var lock = this.lock();
        lock.acquire(oldRoot);

        lock.acquire(newRoot);

        assertThat(lock.holds(newRoot)).isTrue();
        assertThat(lock.holds(oldRoot)).isTrue();
    }

    @Test
    void releasingOneOfTwoHeldRootsLeavesTheOtherHeld(@TempDir final Path oldRoot, @TempDir final Path newRoot) {
        final var lock = this.lock();
        lock.acquire(oldRoot);
        lock.acquire(newRoot);

        lock.release(oldRoot);

        assertThat(lock.holds(newRoot)).isTrue();
        assertThat(lock.holds(oldRoot)).isFalse();
        // The freed root really is free, rather than merely forgotten by the instance that held it.
        this.lock().acquire(oldRoot);
    }

    @Test
    void releaseAllGivesUpEveryHeldRoot(@TempDir final Path oldRoot, @TempDir final Path newRoot) {
        final var lock = this.lock();
        lock.acquire(oldRoot);
        lock.acquire(newRoot);

        lock.releaseAll();

        assertThat(lock.holds(oldRoot)).isFalse();
        assertThat(lock.holds(newRoot)).isFalse();
        this.lock().acquire(oldRoot);
        this.lock().acquire(newRoot);
    }

    // A close that fails still gives the claim up. A caller that cannot act on the failure is then
    // not left holding a root nothing will free before the process ends.
    //
    // No portable way exists to make a real channel refuse to close. The failure is injected at the
    // one seam inside the class where the decision is made.
    @Test
    void aReleaseWhoseCloseFailsStillGivesTheRootUp(@TempDir final Path root) {
        final var lock = new FailsToGiveUpAClaim();
        this.locks.add(lock);
        lock.acquire(root);

        assertThatThrownBy(() -> lock.release(root)).isInstanceOf(UncheckedIOException.class);

        assertThat(lock.holds(root)).isFalse();
        this.lock().acquire(root);
    }

    // One failing close must not strand the claims behind it, which is the whole reason an exit path
    // calls this rather than releasing each root by name.
    @Test
    void releaseAllGivesUpEveryRootEvenWhenOneCloseFails(@TempDir final Path first, @TempDir final Path second) {
        final var lock = new FailsToGiveUpAClaim();
        this.locks.add(lock);
        lock.acquire(first);
        lock.acquire(second);

        assertThatThrownBy(lock::releaseAll).isInstanceOf(UncheckedIOException.class);

        assertThat(lock.holds(first)).isFalse();
        assertThat(lock.holds(second)).isFalse();
        this.lock().acquire(first);
        this.lock().acquire(second);
    }

    // Every failure is carried, not just whichever the map happened to hand back first. One close
    // that fails silently would be a root nobody knows is still locked.
    @Test
    void releaseAllCarriesEveryCloseFailureItMet(@TempDir final Path first, @TempDir final Path second) {
        final var lock = new FailsEveryClose();
        this.locks.add(lock);
        lock.acquire(first);
        lock.acquire(second);

        final var failure = catchThrowableOfType(UncheckedIOException.class, lock::releaseAll);

        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(lock.holds(first)).isFalse();
        assertThat(lock.holds(second)).isFalse();
    }

    // An exit path hands everything back without knowing what is held, so a root held twice has to
    // go too. Counting holders here would leave it claimed for the life of the process.
    @Test
    void releaseAllGivesUpARootHeldMoreThanOnce(@TempDir final Path root) {
        final var lock = this.lock();
        lock.acquire(root);
        lock.acquire(root);

        lock.releaseAll();

        assertThat(lock.holds(root)).isFalse();
        this.lock().acquire(root);
    }

    // The half of the move that has to hold when it goes wrong. A save naming a root somebody else
    // has must leave this process exactly where it was, still holding what it held.
    @Test
    void aRefusedMoveKeepsTheRootAlreadyHeld(@TempDir final Path oldRoot, @TempDir final Path takenRoot) {
        final var lock = this.lock();
        lock.acquire(oldRoot);
        this.lock().acquire(takenRoot);

        assertThatThrownBy(() -> lock.acquire(takenRoot)).isInstanceOf(WorkingRootBusyException.class);

        assertThat(lock.holds(oldRoot)).isTrue();
        assertThat(lock.holds(takenRoot)).isFalse();
    }

    // A refused move must also leave the root free to claim later. Registering the new root before
    // locking it would otherwise strand it: this process would refuse itself that root for good,
    // long after whoever held it had gone.
    @Test
    void aRefusedMoveLeavesTheRefusedRootClaimableLater(@TempDir final Path oldRoot, @TempDir final Path takenRoot) {
        final var lock = this.lock();
        lock.acquire(oldRoot);
        final var holder = this.lock();
        holder.acquire(takenRoot);
        assertThatThrownBy(() -> lock.acquire(takenRoot)).isInstanceOf(WorkingRootBusyException.class);

        holder.release(takenRoot);
        lock.acquire(takenRoot);

        assertThat(lock.holds(takenRoot)).isTrue();
    }

    @Test
    void acquireReportsARootItCannotOpenAMarkerFileIn(@TempDir final Path root) {
        final Path missing = root.resolve("no-such-directory");

        assertThatThrownBy(() -> this.lock().acquire(missing))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to open the lock file in working root")
                .hasMessageContaining("no-such-directory");
    }

    @Test
    void releaseWithoutAClaimLeavesTheRootFree(@TempDir final Path root) {
        final var lock = this.lock();

        lock.release(root);

        final var other = this.lock();
        other.acquire(root);
        assertThat(other.holds(root)).isTrue();
    }

    // A marker left behind by a process that has since exited carries no claim of its own. The lock
    // is the kernel's, not the file's, so a stale marker never has to be cleaned up before startup.
    @Test
    void acquireClaimsARootWhoseMarkerFileAlreadyExists(@TempDir final Path root) throws Exception {
        Files.writeString(root.resolve(FileChannelWorkingRootLock.LOCK_FILE_NAME), "");
        final var lock = this.lock();

        lock.acquire(root);

        assertThat(lock.holds(root)).isTrue();
    }

    @Test
    void releaseLeavesTheMarkerFileOnDisk(@TempDir final Path root) {
        final var lock = this.lock();
        lock.acquire(root);

        lock.release(root);

        assertThat(root.resolve(FileChannelWorkingRootLock.LOCK_FILE_NAME)).exists();
    }

    // Every lock a test takes comes from here, so the @AfterEach above knows about all of them.
    private FileChannelWorkingRootLock lock() {
        final var lock = new FileChannelWorkingRootLock();
        this.locks.add(lock);
        return lock;
    }

    // Starts a second JVM holding the root, and returns once it says it has the claim. Waiting for
    // that line rather than for a duration is what keeps a test from racing its own fixture.
    private static Process startHolder(final Path root) throws Exception {
        final String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        final Process holder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"),
                WorkingRootHolder.class.getName(), root.toString())
                .redirectErrorStream(true)
                .start();
        // A holder that survives a failed handshake would keep the root, and on Windows an open
        // handle inside the temp directory with it. The kill is not waited on here: whatever went
        // wrong is already being thrown, and this is only stopping it from becoming two failures.
        try {
            final var output = new BufferedReader(new InputStreamReader(holder.getInputStream()));
            final String claimed = CompletableFuture.supplyAsync(() -> awaitClaim(output))
                    .get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(claimed)
                    .withFailMessage("the holder process never claimed the root; its output was:%n%s", claimed)
                    .isEqualTo(WorkingRootHolder.CLAIMED);
            return holder;
        } catch (final Throwable t) {
            holder.destroyForcibly();
            throw t;
        }
    }

    // Reads the holder's output until it announces the claim, or until it dies without doing so.
    // On that second path it returns everything the holder said, which is what the caller reports.
    private static String awaitClaim(final BufferedReader output) {
        final var said = new StringBuilder();
        try {
            String line = output.readLine();
            while (line != null && !line.equals(WorkingRootHolder.CLAIMED)) {
                said.append(line).append(System.lineSeparator());
                line = output.readLine();
            }
            return line == null ? said.toString() : line;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Kills the holder outright, giving it no chance to release, then waits for it to go.
    private static void killHolder(final Process holder) throws Exception {
        holder.destroyForcibly();
        // Waiting matters as much as killing. The lock is the kernel's, and the kernel gives it up
        // as the process is reaped, not as the signal is sent.
        assertThat(holder.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .withFailMessage("the killed holder process did not exit").isTrue();
    }

    // Closes the holder's input, which is its cue to release and exit, then waits for it to go.
    private static void stopHolder(final Process holder) throws Exception {
        holder.getOutputStream().close();
        assertThat(holder.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .withFailMessage("the holder process did not exit").isTrue();
    }

    // Fails the first attempt to give a claim up, the way a channel that refuses to close would.
    // The channel is still closed and the registry entry still dropped first. So the failure is what
    // a real one looks like, reported after the work rather than instead of it. Only the first is
    // failed, so the teardown release can still run.
    private static final class FailsToGiveUpAClaim extends FileChannelWorkingRootLock {

        private boolean failed;

        @Override
        void close(final Claim claim) {
            super.close(claim);
            if (!this.failed) {
                this.failed = true;
                throw new UncheckedIOException(new IOException("the channel refused to close"));
            }
        }
    }

    // The channel is closed for real first, so a test using this leaves no root behind. Only the
    // reporting fails.
    private static final class FailsEveryClose extends FileChannelWorkingRootLock {

        @Override
        void close(final Claim claim) {
            super.close(claim);
            throw new UncheckedIOException(new IOException("the channel refused to close"));
        }
    }
}
