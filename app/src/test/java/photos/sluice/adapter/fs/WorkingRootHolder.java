package photos.sluice.adapter.fs;

import java.io.PrintWriter;
import java.nio.file.Path;

// Run as a separate JVM by FileChannelWorkingRootLockTest. It claims the working root named in
// args[0], says so on stdout, and holds it until its stdin closes. A genuinely separate process is
// the only way to reach the refusal a second Sluice install gets. Two claims inside one JVM are
// stopped a step earlier, by the lock's own registry.
public final class WorkingRootHolder {

    // The line the test waits for before it tries to claim the same root.
    static final String CLAIMED = "CLAIMED";

    private WorkingRootHolder() {
    }

    static void main(final String[] args) throws Exception {
        final var lock = new FileChannelWorkingRootLock();
        lock.acquire(Path.of(args[0]));
        // Auto-flushing, so the parent sees the line as soon as it is written rather than whenever
        // the buffer happens to drain.
        final var out = new PrintWriter(System.out, true);
        out.println(CLAIMED);
        // Blocks until the parent closes this process's input, which is how the test says it is
        // done. Reading rather than sleeping holds the root for exactly as long as the test needs.
        //noinspection StatementWithEmptyBody -- waiting for the close is the point; any bytes sent are discarded
        while (System.in.read() != -1) {}
        lock.release();
    }
}
