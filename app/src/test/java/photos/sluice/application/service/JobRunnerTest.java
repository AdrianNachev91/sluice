package photos.sluice.application.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRunnerTest {

    private final JobRunner runner = new JobRunner();

    @Test
    void joinReturnsTheWorkResult() {
        final JobHandle<String> handle = runner.submit(_ -> "done");

        assertThat(handle.join()).isEqualTo("done");
    }

    @Test
    void workRunsOffTheSubmittingThread() {
        final long submittingThreadId = Thread.currentThread().threadId();
        final var workThreadId = new AtomicLong(submittingThreadId);
        final JobHandle<String> handle = runner.submit(_ -> {
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
        final JobHandle<String> handle = runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        assertThat(runner.isBusy()).isTrue();

        release.countDown();
        handle.join();

        assertThat(runner.isBusy()).isFalse();
    }

    @Test
    void submitWhileAJobIsRunningThrows() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes. Needed here so the
        // second submit() below is proven to race a job that's genuinely still in flight, not one
        // that happened to finish first.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> first = runner.submit(_ -> {
            started.countDown();
            release.await();
            return "done";
        });
        started.await();

        assertThatThrownBy(() -> runner.submit(_ -> "second"))
                .isInstanceOf(IllegalStateException.class);

        release.countDown();
        first.join();
    }

    @Test
    void slotFreesAndFailureIsWrappedWhenWorkThrows() {
        final var failure = new RuntimeException("Defqon 1 canceled, queue the next edition");
        final JobHandle<String> handle = runner.submit(_ -> {
            throw failure;
        });

        assertThatThrownBy(handle::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        assertThat(runner.isBusy()).isFalse();
    }

    @Test
    void slotIsFreeAgainOnceTheFirstJobFinishes() throws InterruptedException {
        // started/release: see isBusyWhileRunningThenFreeOnceTheJobCompletes.
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final JobHandle<String> first = runner.submit(_ -> {
            started.countDown();
            release.await();
            return "first";
        });
        started.await();
        release.countDown();
        first.join();

        final JobHandle<String> second = runner.submit(_ -> "second");

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
        final JobHandle<String> handle = runner.submit(h -> {
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
        final JobHandle<String> handle = runner.submit(_ -> "result");

        // join() only guarantees the future's own result is visible, not that a sibling dependent
        // stage like thenAccept has already run - wait on that stage's own completion too, or this
        // assertion could occasionally race a still-pending callback.
        handle.onComplete().thenAccept(received::set).toCompletableFuture().join();

        assertThat(received.get()).isEqualTo("result");
    }
}
