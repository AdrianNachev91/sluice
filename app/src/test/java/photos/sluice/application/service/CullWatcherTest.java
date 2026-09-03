package photos.sluice.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CullWatcherTest {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(15);

    @Test
    void doesNotAttemptConsumeUntilReady() throws InterruptedException {
        final var ready = new AtomicBoolean(false);
        final var consumeAttempts = new AtomicInteger(0);
        final var consumed = new CountDownLatch(1);
        final var watcher = new CullWatcher(POLL_INTERVAL, ready::get, () -> {
            consumeAttempts.incrementAndGet();
            consumed.countDown();
            return true;
        });

        watcher.start();
        // A bounded negative proof via the latch's own timeout, not a guessed-duration Thread.sleep
        // plus a manual counter check. await() returns the moment either the countdown happens
        // (proving a bug) or the timeout elapses (the expected path here) - no slower than the
        // window actually needs and no less deterministic than the positive-wait case below.
        assertThat(consumed.await(POLL_INTERVAL.toMillis() * 3, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(consumeAttempts.get()).isZero();
        assertThat(watcher.isActive()).isTrue();

        ready.set(true);
        assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
        watcher.stop();
    }

    @Test
    void stopsPollingAfterASuccessfulConsume() throws InterruptedException {
        final var consumeAttempts = new AtomicInteger(0);
        final var firstConsume = new CountDownLatch(1);
        final var secondConsume = new CountDownLatch(1);
        final var watcher = new CullWatcher(POLL_INTERVAL, () -> true, () -> {
            if (consumeAttempts.incrementAndGet() == 1) {
                firstConsume.countDown();
            } else {
                secondConsume.countDown();
            }
            return true;
        });

        watcher.start();

        assertThat(firstConsume.await(2, TimeUnit.SECONDS)).isTrue();
        // Same bounded-negative-proof idiom as doesNotAttemptConsumeUntilReady above: a second
        // countdown should never come, since a successful consume stops the watcher.
        assertThat(secondConsume.await(POLL_INTERVAL.toMillis() * 3, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(consumeAttempts.get()).isEqualTo(1);
        assertThat(watcher.isActive()).isFalse();
    }

    @Test
    void keepsPollingWhenAttemptConsumeReportsBusyUntilItSucceeds() throws InterruptedException {
        final var consumeAttempts = new AtomicInteger(0);
        final var succeeded = new CountDownLatch(1);
        final var watcher = new CullWatcher(POLL_INTERVAL, () -> true, () -> {
            final boolean isThirdAttempt = consumeAttempts.incrementAndGet() >= 3;
            if (isThirdAttempt) {
                succeeded.countDown();
            }
            return isThirdAttempt;
        });

        watcher.start();

        assertThat(succeeded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(consumeAttempts.get()).isEqualTo(3);
        watcher.stop();
    }

    // A ScheduledExecutorService silently suppresses every future execution of a task that throws,
    // and the exception vanishes into a ScheduledFuture nobody inspects. So without poll()'s catch,
    // the first bad tick ends the watch while isActive() keeps reporting true. The waiting cull
    // then never auto-resumes, with nothing anywhere saying why. The route in is real: the readiness
    // check reads shards an agent outside this app wrote, so malformed input is the expected case.
    @Test
    void keepsPollingAfterAReadinessCheckThrows() throws InterruptedException {
        final var readyChecks = new AtomicInteger(0);
        final var consumed = new CountDownLatch(1);
        final var watcher = new CullWatcher(POLL_INTERVAL, () -> {
            if (readyChecks.incrementAndGet() == 1) {
                throw new IllegalStateException("first tick blows up");
            }
            return true;
        }, () -> {
            consumed.countDown();
            return true;
        });

        watcher.start();

        assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(readyChecks.get()).isGreaterThan(1);
    }

    @Test
    void staysActiveWhileARecoveredPollFailureIsTheOnlyThingThatHappened() throws InterruptedException {
        final var readyChecks = new AtomicInteger(0);
        final var watcher = new CullWatcher(POLL_INTERVAL, () -> {
            readyChecks.incrementAndGet();
            throw new IllegalStateException("every tick blows up");
        }, () -> true);

        watcher.start();
        // Two throwing ticks, so the second one proves the first did not suppress the schedule.
        waitUntil(() -> readyChecks.get() >= 2, Duration.ofSeconds(2));

        assertThat(watcher.isActive()).isTrue();
        watcher.stop();
    }

    @Test
    void stopIsIdempotentAndSafeBeforeStart() {
        final var watcher = new CullWatcher(POLL_INTERVAL, () -> false, () -> true);

        watcher.stop();
        watcher.stop();

        assertThat(watcher.isActive()).isFalse();
    }

    private static void waitUntil(final BooleanSupplier condition, final Duration timeout) throws InterruptedException {
        final Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            // Throttles the poll loop itself, not a guess at how long the watcher takes - same
            // pattern as PipelineTestSupport.waitUntil's own suppression.
            //noinspection BusyWait
            Thread.sleep(5);
        }
    }
}
