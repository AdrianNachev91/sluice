package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

// Polls one waiting cull's shard status on a fixed interval until it's fully valid, then attempts
// exactly one auto-resume. Deliberately pure polling rather than java.nio.file.WatchService. The
// design doc wants filesystem events because a plain local disk fires them promptly. That reasoning
// cuts the other way here. Settings lets a user point the working folder at a cloud-synced or
// network folder. That's exactly the case the same doc already flags as missing events. Depending
// on events at all would just relocate that gap somewhere less visible. A short poll interval costs
// nothing a human dropping files by hand would ever notice.
//
// isReady is a cheap status check (Pipeline's own shard tally). attemptConsume is the heavier
// action - a real resume() attempt - run only once isReady says so. attemptConsume returns whether
// it actually got to run. False means the job runner was busy with something else, so this watcher
// keeps polling and retries later rather than giving up. True means this watcher's job is done.
// A resume attempt can still land back in Waiting itself, if a shard went bad between the tally
// check and the real validation. When that happens, the same Pipeline call that produces that
// outcome arms a fresh watcher. This instance does not loop on its own.
//
// timeout, when present, only stops polling after that long with no ready check. It never touches
// the underlying job, matching cull.externalAgent.watchTimeout's "drops back to manual, all work
// preserved" contract.
final class CullWatcher {

    private static final Logger log = LoggerFactory.getLogger(CullWatcher.class);
    private static final ThreadFactory DAEMON_THREADS = runnable -> {
        var thread = new Thread(runnable, "cull-watcher");
        thread.setDaemon(true);
        return thread;
    };

    private final Duration pollInterval;
    private final @Nullable Duration timeout;
    private final BooleanSupplier isReady;
    private final BooleanSupplier attemptConsume;
    private final Instant armedAt;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);
    // Null until start() runs; stop() before start() is a valid no-op (see its own doc).
    private volatile @Nullable ScheduledFuture<?> task;

    CullWatcher(Duration pollInterval, @Nullable Duration timeout, BooleanSupplier isReady,
            BooleanSupplier attemptConsume, Instant armedAt) {
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.isReady = isReady;
        this.attemptConsume = attemptConsume;
        this.armedAt = armedAt;
    }

    void start() {
        task = executor.scheduleWithFixedDelay(
                this::poll, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    // Cancels the scheduled poll and shuts the watcher's own executor down. Safe to call more than
    // once - executor.shutdown() is itself idempotent. Also safe to call from inside poll() itself:
    // ScheduledExecutorService.shutdown() never interrupts the task currently running on it.
    void stop() {
        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(false);
        }
        executor.shutdown();
    }

    boolean isActive() {
        return !executor.isShutdown();
    }

    // A scheduleWithFixedDelay task that throws suppresses every future execution silently, per
    // ScheduledExecutorService's own contract. Caught broadly here so one bad tick - a transient
    // read failure the tally check didn't already swallow - degrades to "not ready this tick"
    // instead of quietly killing the whole watch.
    private void poll() {
        try {
            pollUnsafe();
        } catch (RuntimeException e) {
            log.warn("Cull watcher poll failed, will retry next tick", e);
        }
    }

    private void pollUnsafe() {
        if (timeout != null && Duration.between(armedAt, Instant.now()).compareTo(timeout) >= 0) {
            stop();
            return;
        }
        if (isReady.getAsBoolean() && attemptConsume.getAsBoolean()) {
            stop();
        }
    }
}
