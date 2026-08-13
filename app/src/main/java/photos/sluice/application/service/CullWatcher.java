package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Polls one waiting cull's shard status on a fixed interval until it is ready to resume, then
 * attempts exactly one auto-resume. Polling is deliberate here rather than a filesystem-event
 * watch. Events would normally be preferable for prompt notice on a plain local disk. A
 * user-configured working folder can point at a cloud-synced or network location instead, exactly
 * where such events are known to be unreliable. Depending on events at all would just relocate
 * that gap somewhere less visible. A short poll interval costs nothing a human dropping files by
 * hand would ever notice.
 *
 * <p>{@code isReady} is a cheap status check ({@link CullEngine}'s own shard tally, via
 * {@link ShardTallyCalculator}). {@code attemptConsume} is the heavier action, a real resume
 * attempt, run only once {@code isReady} says so. It returns whether this watcher has anything
 * left to do. False means the job runner was busy with something else, so this watcher keeps
 * polling and retries later rather than giving up. True means this watcher's job is done, whether
 * because a resume went in or because one was refused on grounds no amount of polling will change.
 * A resume attempt can still land back in Waiting itself if a shard went bad between the tally
 * check and the real validation. When that happens, the same {@link CullEngine} call that produces
 * that outcome arms a fresh watcher. This instance does not loop on its own.
 *
 * <p>There is no time limit on the polling. A watch that never fires costs one cheap tally read per
 * interval, and a watch that does fire either completes the run or lands it Blocked and stops. So
 * the only thing a deadline could add is giving up on a run the user is still waiting for.
 */
final class CullWatcher {

    private static final Logger log = LoggerFactory.getLogger(CullWatcher.class);
    private static final ThreadFactory DAEMON_THREADS = runnable -> {
        final var thread = new Thread(runnable, "cull-watcher");
        thread.setDaemon(true);
        return thread;
    };

    private final Duration pollInterval;
    private final BooleanSupplier isReady;
    private final BooleanSupplier attemptConsume;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);
    // Null until start() runs; stop() before start() is a valid no-op (see its own doc).
    private volatile @Nullable ScheduledFuture<?> task;

    /**
     * Creates a watcher for one waiting cull, not yet running.
     *
     * @param pollInterval {@link Duration} how often to check readiness
     * @param isReady {@link BooleanSupplier} cheap readiness check
     * @param attemptConsume {@link BooleanSupplier} the real resume attempt to run once ready,
     *         answering whether this watcher is done
     */
    CullWatcher(final Duration pollInterval, final BooleanSupplier isReady, final BooleanSupplier attemptConsume) {
        this.pollInterval = pollInterval;
        this.isReady = isReady;
        this.attemptConsume = attemptConsume;
    }

    /**
     * Begins polling on a fixed delay.
     */
    void start() {
        this.task = this.executor.scheduleWithFixedDelay(
                this::poll, this.pollInterval.toMillis(), this.pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the scheduled poll and shuts the watcher's own executor down. Safe to call more than
     * once - executor.shutdown() is itself idempotent. Also safe to call from inside poll() itself:
     * ScheduledExecutorService.shutdown() never interrupts the task currently running on it.
     */
    void stop() {
        final ScheduledFuture<?> current = this.task;
        if (current != null) {
            current.cancel(false);
        }
        this.executor.shutdown();
    }

    /**
     * Reports whether the watcher's executor is still running.
     *
     * @return boolean true if not yet shut down
     */
    boolean isActive() {
        return !this.executor.isShutdown();
    }

    /**
     * A scheduleWithFixedDelay task that throws suppresses every future execution silently, per
     * ScheduledExecutorService's own contract. Caught broadly here so one bad tick - a transient
     * read failure the tally check didn't already swallow - degrades to "not ready this tick"
     * instead of quietly killing the whole watch.
     */
    private void poll() {
        try {
            this.pollUnsafe();
        } catch (final RuntimeException e) {
            log.warn("Cull watcher poll failed, will retry next tick", e);
        }
    }

    /**
     * Checks readiness and attempts one consume.
     */
    private void pollUnsafe() {
        if (this.isReady.getAsBoolean() && this.attemptConsume.getAsBoolean()) {
            this.stop();
        }
    }
}
