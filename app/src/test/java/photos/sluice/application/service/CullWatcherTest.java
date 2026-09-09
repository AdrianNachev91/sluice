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
        // The latch's own timeout bounds the negative. await() returns the moment the countdown
        // happens, so a bug fails this fast rather than after a guessed sleep.
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
            // Throttles the poll loop itself, not a guess at how long the watcher takes.
            //noinspection BusyWait
            Thread.sleep(5);
        }
    }
}
