package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.JobInProgressException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRunnerTest {

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
    void onCompleteFiresOnceTheResultIsAvailable() {
        final var received = new AtomicReference<>("");
        final JobHandle<String> handle = this.runner.submit(_ -> "result");

        // join() only guarantees the future's own result is visible, not that a sibling dependent
        // stage like thenAccept has already run - wait on that stage's own completion too, or this
        // assertion could occasionally race a still-pending callback.
        handle.onComplete().thenAccept(received::set).toCompletableFuture().join();

        assertThat(received.get()).isEqualTo("result");
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
