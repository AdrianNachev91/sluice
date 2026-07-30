package photos.sluice.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CullWatcherTest {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(15);

    @Test
    void doesNotAttemptConsumeUntilReady() throws InterruptedException {
        final var ready = new AtomicBoolean(false);
        final var consumeAttempts = new AtomicInteger(0);
        final var consumed = new CountDownLatch(1);
        final var watcher = new CullWatcher(POLL_INTERVAL, null, ready::get, () -> {
            consumeAttempts.incrementAndGet();
            consumed.countDown();
            return true;
        }, Instant.now());

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
        final var watcher = new CullWatcher(POLL_INTERVAL, null, () -> true, () -> {
            if (consumeAttempts.incrementAndGet() == 1) {
                firstConsume.countDown();
            } else {
                secondConsume.countDown();
            }
            return true;
        }, Instant.now());

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
        final var watcher = new CullWatcher(POLL_INTERVAL, null, () -> true, () -> {
            final boolean isThirdAttempt = consumeAttempts.incrementAndGet() >= 3;
            if (isThirdAttempt) {
                succeeded.countDown();
            }
            return isThirdAttempt;
        }, Instant.now());

        watcher.start();

        assertThat(succeeded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(consumeAttempts.get()).isEqualTo(3);
        watcher.stop();
    }

    @Test
    void stopsWithoutEverConsumingOnceTheTimeoutElapses() throws InterruptedException {
        final var consumeAttempts = new AtomicInteger(0);
        final var watcher = new CullWatcher(POLL_INTERVAL, Duration.ofMillis(40), () -> false,
                () -> {
                    consumeAttempts.incrementAndGet();
                    return true;
                }, Instant.now());

        watcher.start();
        // Polls for the real signal - the watcher actually stopping itself once the timeout fires -
        // instead of guessing a fixed sleep duration long enough to cover it.
        waitUntilInactive(watcher, Duration.ofSeconds(2));

        assertThat(consumeAttempts.get()).isZero();
        assertThat(watcher.isActive()).isFalse();
    }

    @Test
    void stopIsIdempotentAndSafeBeforeStart() {
        final var watcher = new CullWatcher(POLL_INTERVAL, null, () -> false, () -> true, Instant.now());

        watcher.stop();
        watcher.stop();

        assertThat(watcher.isActive()).isFalse();
    }

    private static void waitUntilInactive(final CullWatcher watcher, final Duration timeout) throws InterruptedException {
        final Instant deadline = Instant.now().plus(timeout);
        while (watcher.isActive()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("watcher still active after " + timeout);
            }
            // Throttles the poll loop itself, not a guess at how long the watcher takes to stop -
            // same pattern as PipelineTest.waitUntil's own suppression.
            //noinspection BusyWait
            Thread.sleep(5);
        }
    }
}
