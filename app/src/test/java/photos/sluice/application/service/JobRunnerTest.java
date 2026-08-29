package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.ShuttingDownException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRunnerTest {

    // Long enough that nothing here is refused for being slow, short enough that a regression
    // reintroducing an unbounded wait fails the suite rather than hanging it.
    private static final Duration GENEROUS = Duration.ofSeconds(30);
    // For the tests whose subject is the wait running out. Their own held latch is what decides
    // when the slot frees, so no value here can make one flaky.
    private static final Duration BRIEF = Duration.ofMillis(50);

    private final JobRunner runner = new JobRunner();

    @Test
    void joinReturnsTheWorkResult() {
        final JobHandle<String> handle = this.runner.submit(_ -> "done");

        assertThat(handle.join()).isEqualTo("done");
    }

    @Test
    void workRunsOffTheSubmittingThread() {
        final long submittingThreadId = Thread.currentThread().threadId();
        final var workThreadId = new AtomicLong(submittingThreadId);
        final JobHandle<String> handle = this.runner.submit(_ -> {
            workThreadId.set(Thread.currentThread().threadId());
            return "done";
        });

        handle.join();

        assertThat(workThreadId.get()).isNotEqualTo(submittingThreadId);
    }

    @Test
    void isBusyWhileRunningThenFreeOnceTheJobCompletes() throws InterruptedException {
        // Two latches, one for each direction. "started" lets the worker thread prove it has
        // actually begun executing before this test trusts isBusy(). Without it, isBusy() could
        // read false just because the virtual thread hasn't been scheduled yet. "release" then
        // holds the job open on command, so it doesn't finish before the test gets a chance to
        // assert anything.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> handle = this.runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        assertThat(this.runner.isBusy()).isTrue();

        release.countDown();
        handle.join();

        assertThat(this.runner.isBusy()).isFalse();
    }

    @Test
    void submitWhileAJobIsRunningThrows() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes. Needed here so the
        // second submit() below is proven to race a job that's genuinely still in flight, not one
        // that happened to finish first.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> first = this.runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        assertThatThrownBy(() -> this.runner.submit(_ -> "second"))
                .isInstanceOf(JobInProgressException.class);

        release.countDown();
        first.join();
    }

    @Test
    void runIfIdleRunsTheWorkAndSaysSoWhenNoJobIsRunning() {
        final var ran = new AtomicBoolean(false);

        final boolean reported = this.runner.runIfIdle(() -> ran.set(true));

        assertThat(reported).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    void runIfIdleLeavesTheWorkUnrunAndSaysSoWhileAJobIsRunning() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes. Needed so the call
        // below is proven to meet a job genuinely still in flight.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var ran = new AtomicBoolean(false);
        final JobHandle<String> job = this.runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        final boolean reported = this.runner.runIfIdle(() -> ran.set(true));

        assertThat(reported).isFalse();
        assertThat(ran).isFalse();
        release.countDown();
        job.join();
    }

    // The whole point of the method. A job that could start here would be moving the tree while the
    // work is deciding what the tree is.
    @Test
    void noJobStartsWhileRunIfIdleWorkIsStillRunning() throws InterruptedException {
        final var working = new CountDownLatch(1);
        final var submitted = new CountDownLatch(1);
        final var reachedSubmit = new CountDownLatch(1);
        final var finishWork = new CountDownLatch(1);
        final var running = Thread.ofVirtual().start(() -> this.runner.runIfIdle(() -> {
            working.countDown();
            await(finishWork);
        }));
        working.await();
        final var submitting = Thread.ofVirtual().start(() -> {
            reachedSubmit.countDown();
            this.runner.submit(_ -> "done").join();
            submitted.countDown();
        });
        reachedSubmit.await();

        try {
            // Margin rather than a guess at a duration. A submit that waits cannot get through at
            // all until the work below is released, so no load can make this fail.
            assertThat(submitted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            finishWork.countDown();
            running.join();
            submitting.join();
        }
        assertThat(this.runner.isBusy()).isFalse();
    }

    // A monitor left held would wedge every later job behind work that already gave up.
    @Test
    void theSlotIsUsableAgainAfterRunIfIdleWorkThrows() {
        assertThatThrownBy(() -> this.runner.runIfIdle(() -> {
            throw new IllegalStateException("the work gave up");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(this.runner.submit(_ -> "done").join()).isEqualTo("done");
    }

    @Test
    void slotFreesAndFailureIsWrappedWhenWorkThrows() {
        final var failure = new RuntimeException("Defqon 1 canceled, queue the next edition");
        final JobHandle<String> handle = this.runner.submit(_ -> {
            throw failure;
        });

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        assertThat(this.runner.isBusy()).isFalse();
    }

    @Test
    void slotFreesAndFailureIsWrappedWhenWorkThrowsAnErrorNotJustAnException() {
        // Guards the catch (Throwable), not catch (Exception), at JobRunner.java's own run().
        // An Error can escape deep in an engine call: a stack overflow walking a pathological
        // directory tree, an out-of-memory decoding a large batch. Catching only Exception would
        // let it skip both freeing the slot and completing the caller's join(). Every future
        // submit() would then wedge behind a job that never finishes. This asserts through a
        // bounded get(), not join(). join()'s own wait is non-interruptible and cannot be timed
        // out. If this exact regression ever recurred, join() would hang forever here, taking the
        // whole test run down with it instead of failing cleanly.
        final var failure = new Error("simulated stack overflow");
        final JobHandle<String> handle = this.runner.submit(_ -> {
            throw failure;
        });

        assertThatThrownBy(() -> handle.onComplete().toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
        assertThat(this.runner.isBusy()).isFalse();
    }

    @Test
    void slotIsFreeAgainOnceTheFirstJobFinishes() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> first = this.runner.submit(_ -> {
            started.countDown();
            release.await();
            return "first";
        });
        started.await();
        release.countDown();
        first.join();

        final JobHandle<String> second = this.runner.submit(_ -> "second");

        assertThat(second.join()).isEqualTo("second");
    }

    @Test
    void handedInHandleReflectsARequestedCancellation() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes. requestCancellation()
        // is called while the job is still paused on release.await(), so the flag is guaranteed to
        // already be set by the time the job resumes and reads it.
        final var observedCancellation = new AtomicBoolean(true);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> handle = this.runner.submit(h -> {
            started.countDown();
            release.await();
            observedCancellation.set(h.isCancellationRequested());
            return "done";
        });
        started.await();

        assertThat(handle.isCancellationRequested()).isFalse();
        handle.requestCancellation();
        release.countDown();
        handle.join();

        assertThat(observedCancellation.get()).isTrue();
    }

    @Test
    void requestingAbandonSetsCancellationWithIt() {
        final JobHandle<String> handle = this.runner.submit(_ -> "done");
        handle.join();

        handle.requestAbandon();

        assertThat(handle.stopSignal().isAbandonRequested()).isTrue();
        assertThat(handle.stopSignal().isCancelled()).isTrue();
    }

    @Test
    void anOrdinaryCancelDoesNotAskToAbandonTheFileInFlight() {
        final JobHandle<String> handle = this.runner.submit(_ -> "done");
        handle.join();

        handle.requestCancellation();

        assertThat(handle.stopSignal().isCancelled()).isTrue();
        assertThat(handle.stopSignal().isAbandonRequested()).isFalse();
    }

    @Test
    void aJobNobodyStoppedIsAskedToAbandonNothing() {
        final JobHandle<String> handle = this.runner.submit(_ -> "done");
        handle.join();

        assertThat(handle.stopSignal().isCancelled()).isFalse();
        assertThat(handle.stopSignal().isAbandonRequested()).isFalse();
    }

    @Test
    void onCompleteFiresOnceTheResultIsAvailable() {
        final var received = new AtomicReference<>("");
        final JobHandle<String> handle = this.runner.submit(_ -> "result");

        // join() only guarantees the future's own result is visible, not that a sibling dependent
        // stage like thenAccept has already run - wait on that stage's own completion too, or this
        // assertion could occasionally race a still-pending callback.
        handle.onComplete().thenAccept(received::set).toCompletableFuture().join();

        assertThat(received.get()).isEqualTo("result");
    }

    // The only test here that a slot taken with no wait at all would fail, since refusing instantly
    // is still refusing everywhere else. The submitting thread is proven parked before the latch
    // frees the slot, so it cannot pass by arriving after the contention was already over.
    @Test
    void submitWaitsOutRunIfIdleWorkThatFreesTheSlotInsideTheWait() throws Exception {
        final var holding = new CountDownLatch(1);
        final var releaseWork = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> this.runner.runIfIdle(() -> {
            holding.countDown();
            await(releaseWork);
        }));
        holding.await();
        final var outcome = new CompletableFuture<JobHandle<String>>();
        final var submitting = offThread(outcome, () -> this.runner.submit(_ -> "done"));

        try {
            awaitParked(submitting);
        } finally {
            releaseWork.countDown();
        }
        holder.join();

        assertThat(within(outcome).join()).isEqualTo("done");
    }

    // The submit after the released latch is a second claim: a refusal on the way out of tryLock
    // has to leave the slot takeable, not held.
    @Test
    void submitIsRefusedRatherThanParkedOnceRunIfIdleWorkOutlastsTheWait() throws Exception {
        final var briefWait = new JobRunner(BRIEF);
        final var holding = new CountDownLatch(1);
        final var releaseWork = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> briefWait.runIfIdle(() -> {
            holding.countDown();
            await(releaseWork);
        }));
        holding.await();
        final var outcome = new CompletableFuture<JobHandle<String>>();
        offThread(outcome, () -> briefWait.submit(_ -> "done"));

        try {
            assertThatThrownBy(() -> within(outcome)).hasCauseInstanceOf(JobInProgressException.class);
        } finally {
            releaseWork.countDown();
            holder.join();
        }
        assertThat(briefWait.submit(_ -> "done").join()).isEqualTo("done");
    }

    @Test
    void runIfIdleReportsTheWorkUnrunOnceAnotherCallerOutlastsTheWait() throws Exception {
        final var briefWait = new JobRunner(BRIEF);
        final var holding = new CountDownLatch(1);
        final var releaseWork = new CountDownLatch(1);
        final var ran = new AtomicBoolean(false);
        final var holder = Thread.ofVirtual().start(() -> briefWait.runIfIdle(() -> {
            holding.countDown();
            await(releaseWork);
        }));
        holding.await();
        final var outcome = new CompletableFuture<Boolean>();
        offThread(outcome, () -> briefWait.runIfIdle(() -> ran.set(true)));

        try {
            assertThat(within(outcome)).isFalse();
        } finally {
            releaseWork.countDown();
            holder.join();
        }
        assertThat(ran).isFalse();
    }

    @Test
    void shutdownWithNothingEverSubmittedReportsTheRunnerDrained() {
        assertThat(this.runner.shutdown(GENEROUS)).isTrue();
    }

    @Test
    void shutdownAsksTheRunningJobToStopAndWaitsUntilItHas() throws InterruptedException {
        final var started = new CountDownLatch(1);
        final JobHandle<String> handle = this.runner.submit(job -> {
            started.countDown();
            awaitCancellation(job);
            return "stopped";
        });
        started.await();

        assertThat(this.runner.shutdown(GENEROUS)).isTrue();

        assertThat(handle.join()).isEqualTo("stopped");
        assertThat(this.runner.isBusy()).isFalse();
    }

    // The job ends only once cancellation reaches it. Give it any other exit and the shutdown can
    // find a job already gone, which proves nothing about a failing one.
    @Test
    void aJobThatFailsOnItsWayOutStillCountsAsDrained() throws InterruptedException {
        final var started = new CountDownLatch(1);
        this.runner.submit(job -> {
            started.countDown();
            awaitCancellation(job);
            throw new IllegalStateException("the job gave up on its way out");
        });
        started.await();

        assertThat(this.runner.shutdown(GENEROUS)).isTrue();
    }

    @Test
    void shutdownReportsTheRunnerUndrainedWhenTheJobOutlastsTheWait() throws Exception {
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> handle = this.runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();
        final var outcome = new CompletableFuture<Boolean>();
        offThread(outcome, () -> this.runner.shutdown(BRIEF));

        try {
            assertThat(within(outcome)).isFalse();
        } finally {
            release.countDown();
            handle.join();
        }
    }

    @Test
    void shutdownReportsTheRunnerUndrainedWhenRunIfIdleWorkOutlastsTheWait() throws Exception {
        final var holding = new CountDownLatch(1);
        final var releaseWork = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> this.runner.runIfIdle(() -> {
            holding.countDown();
            await(releaseWork);
        }));
        holding.await();
        final var outcome = new CompletableFuture<Boolean>();
        offThread(outcome, () -> this.runner.shutdown(BRIEF));

        try {
            assertThat(within(outcome)).isFalse();
        } finally {
            releaseWork.countDown();
            holder.join();
        }
        // A second claim, and the one the answer above cannot make: the shut happened even though
        // the slot never came free.
        assertThatThrownBy(() -> this.runner.submit(_ -> "done")).isInstanceOf(ShuttingDownException.class);
    }

    // The fixture holds both conditions at once, which is what the name cannot say. The runner is
    // shut AND the slot is still held, so each refusal has two answers to choose between.
    @Test
    void aShutRunnerRefusesAsClosingEvenWhileTheSlotIsStillHeld() throws Exception {
        final var briefWait = new JobRunner(BRIEF);
        final var holding = new CountDownLatch(1);
        final var releaseWork = new CountDownLatch(1);
        final var holder = Thread.ofVirtual().start(() -> briefWait.runIfIdle(() -> {
            holding.countDown();
            await(releaseWork);
        }));
        holding.await();
        final var shutdown = new CompletableFuture<Boolean>();
        offThread(shutdown, () -> briefWait.shutdown(BRIEF));
        assertThat(within(shutdown)).isFalse();

        final var submitted = new CompletableFuture<JobHandle<String>>();
        offThread(submitted, () -> briefWait.submit(_ -> "done"));
        final var saved = new CompletableFuture<Boolean>();
        offThread(saved, () -> briefWait.runIfIdle(() -> { }));

        try {
            assertThatThrownBy(() -> within(submitted)).hasCauseInstanceOf(ShuttingDownException.class);
            assertThatThrownBy(() -> within(saved)).hasCauseInstanceOf(ShuttingDownException.class);
        } finally {
            releaseWork.countDown();
            holder.join();
        }
    }

    @Test
    void aSecondShutdownAnswersTheSame() {
        this.runner.submit(_ -> "done").join();
        assertThat(this.runner.shutdown(GENEROUS)).isTrue();

        assertThat(this.runner.shutdown(BRIEF)).isTrue();
    }

    @Test
    void submitAfterAShutdownIsRefusedAsClosing() {
        this.runner.shutdown(GENEROUS);

        assertThatThrownBy(() -> this.runner.submit(_ -> "done")).isInstanceOf(ShuttingDownException.class);
    }

    @Test
    void runIfIdleAfterAShutdownIsRefusedAsClosing() {
        final var ran = new AtomicBoolean(false);
        this.runner.shutdown(GENEROUS);

        assertThatThrownBy(() -> this.runner.runIfIdle(() -> ran.set(true)))
                .isInstanceOf(ShuttingDownException.class);
        assertThat(ran).isFalse();
    }

    // Every wait these tests are about is one the runner is supposed to end by itself. Asserting on
    // it from this thread would turn a regression to an endless wait into a hung suite rather than a
    // red test. So the call goes to its own thread, and the answer gets a deadline.
    private static <T> Thread offThread(final CompletableFuture<T> outcome, final Supplier<T> call) {
        return Thread.ofVirtual().start(() -> {
            try {
                outcome.complete(call.get());
            } catch (final RuntimeException e) {
                outcome.completeExceptionally(e);
            }
        });
    }

    private static <T> T within(final CompletableFuture<T> outcome) throws Exception {
        return outcome.get(GENEROUS.toMillis(), TimeUnit.MILLISECONDS);
    }

    // TIMED_WAITING is only reachable here by parking on the slot: nothing else on the way into
    // submit() waits at all. TERMINATED is checked because the real bound is the runner's own slot
    // wait, not this deadline. A thread refused and dead would otherwise be waited out in full, then
    // reported as never having parked, which names the wrong cause.
    private static void awaitParked(final Thread thread) throws InterruptedException {
        final Instant deadline = Instant.now().plus(GENEROUS);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (thread.getState() == Thread.State.TERMINATED) {
                throw new IllegalStateException("the submitting thread was refused instead of parking on the slot");
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("the submitting thread never parked on the slot");
            }
            //noinspection BusyWait
            Thread.sleep(5);
        }
    }

    // Polls the job's own flag rather than sleeping a guess at how long the request takes to arrive.
    // The deadline is what keeps a regression that never requests it a failure. Without one, the job
    // has no way left to end and takes the whole suite down with it.
    private static void awaitCancellation(final JobHandle<?> job) throws InterruptedException {
        final Instant deadline = Instant.now().plus(GENEROUS);
        while (!job.isCancellationRequested()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("cancellation was never requested");
            }
            //noinspection BusyWait
            Thread.sleep(5);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
