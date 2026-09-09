package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.JobRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Most tests here wait on a job that runs until it is cancelled. A cancel that stops working leaves
// that wait with nothing to end it. Bounded, that is a red test naming the cancel. Unbounded, it is
// a suite that never finishes and never says why.
//
// On its own thread, because the default runs the test on this one and can only report a timeout
// once the method returns. A method blocked forever never returns, so the bound would never fire.
@Timeout(value = TypedCancelTest.PATIENCE, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TypedCancelTest {

    static final int PATIENCE = 10;

    private final ByteArrayOutputStream printedBytes = new ByteArrayOutputStream();

    private final ConsoleProgressPort progress =
            new ConsoleProgressPort(new PrintStream(this.printedBytes, true, StandardCharsets.UTF_8), false);

    private final JobRunner runner = new JobRunner();

    @Test
    void aTypedCStopsTheRunningJob() {
        final JobHandle<String> job = this.jobUntilCancelled();

        try (final var _ = new TypedCancel(typed("c\n"), this.progress).watch(job)) {
            assertThat(job.join()).isEqualTo("stopped");
        }
    }

    @Test
    void aCapitalCAndTheBlanksAroundItAreTheSameLine() {
        final JobHandle<String> job = this.jobUntilCancelled();

        try (final var _ = new TypedCancel(typed("  C  \n"), this.progress).watch(job)) {
            assertThat(job.join()).isEqualTo("stopped");
        }
    }

    @Test
    void aCancelCarryingTheMarkAWindowsShellPrefixesIsStillACancel() {
        final JobHandle<String> job = this.jobUntilCancelled();

        try (final var _ = new TypedCancel(typed(Character.toString(0xFEFF) + "c\n"), this.progress).watch(job)) {
            assertThat(job.join()).isEqualTo("stopped");
        }
    }

    // Driven to the end of the input rather than for a moment, so what this proves is that the lines
    // were read and dropped. Left as a wait it would pass just as well against a reader that had not
    // reached them yet.
    @Test
    void aLineThatIsNotACIsReadAndLeavesTheJobAlone() throws Exception {
        final CountDownLatch drained = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final JobHandle<String> job = this.jobUntilReleased(release);

        try (final var _ = new TypedCancel(typed("cull\nnot c\n", drained), this.progress).watch(job)) {
            assertThat(drained.await(PATIENCE, TimeUnit.SECONDS)).isTrue();
            assertThat(job.isCancellationRequested()).isFalse();
        } finally {
            release.countDown();
        }

        assertThat(job.join()).isEqualTo("ran to the end");
    }

    @Test
    void anEmptyInputLeavesTheJobAlone() throws Exception {
        final CountDownLatch drained = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final JobHandle<String> job = this.jobUntilReleased(release);

        try (final var _ = new TypedCancel(typed("", drained), this.progress).watch(job)) {
            assertThat(drained.await(PATIENCE, TimeUnit.SECONDS)).isTrue();
            assertThat(job.isCancellationRequested()).isFalse();
        } finally {
            release.countDown();
        }

        assertThat(job.join()).isEqualTo("ran to the end");
    }

    @Test
    void theFirstCancelSaysTheFileInHandIsFinishedFirst() {
        final JobHandle<String> job = this.jobUntilCancelled();

        try (final var _ = new TypedCancel(typed("c\n"), this.progress).watch(job)) {
            job.join();
        }

        assertThat(this.printedText()).contains("Stopping.").contains("finishes what it is on");
    }

    // The job waits for the input to run out as well as for the cancel, so both lines have been
    // acted on by the time it answers. Waiting on the cancel alone would let it finish between the
    // two and assert against one of them.
    @Test
    void askingAgainGivesUpOnTheFileRatherThanWaitingForIt() {
        final CountDownLatch drained = new CountDownLatch(1);
        final JobHandle<String> job = this.runner.submit(handle -> {
            while (!handle.isCancellationRequested() || drained.getCount() > 0) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return "stopped";
        });

        try (final var _ = new TypedCancel(typed("c\nc\n", drained), this.progress).watch(job)) {
            assertThat(job.join()).isEqualTo("stopped");
        }

        assertThat(this.printedText().lines()).containsExactly(
                "Stopping. It finishes what it is on, then says what it did.",
                "Stopping now. A file still being copied is abandoned rather than finished.");
    }

    // Nothing is typed until the watch has been closed, so this cannot pass by the reader simply
    // being slower than the close.
    @Test
    void aWatchThatIsClosedActsOnNothingTypedAfterwards() throws Exception {
        final CountDownLatch given = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final JobHandle<String> job = this.runner.submit(_ -> "finished before anyone typed");
        job.join();

        final var watching = new TypedCancel(typedAfter(closed, "c\n", given), this.progress).watch(job);
        watching.close();
        closed.countDown();

        assertThat(given.await(PATIENCE, TimeUnit.SECONDS)).isTrue();
        assertThat(this.printedText()).isEmpty();
    }

    private JobHandle<String> jobUntilCancelled() {
        return this.runner.submit(handle -> {
            while (!handle.isCancellationRequested()) {
                //noinspection BusyWait
                Thread.sleep(1);
            }
            return "stopped";
        });
    }

    private JobHandle<String> jobUntilReleased(final CountDownLatch release) {
        return this.runner.submit(_ -> {
            release.await();
            return "ran to the end";
        });
    }

    private String printedText() {
        return this.printedBytes.toString(StandardCharsets.UTF_8);
    }

    private static InputStream typed(final String lines) {
        return new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8));
    }

    // Counts down once the reader has read past the last line, so a test waits on the reading rather
    // than on the clock.
    private static InputStream typed(final String lines, final CountDownLatch drained) {
        return new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(final byte[] into, final int from, final int wanted) {
                final int read = super.read(into, from, wanted);
                if (read < 0) {
                    drained.countDown();
                }
                return read;
            }
        };
    }

    // Counts down once the line has been handed over rather than at the end of the input. A reader
    // that stops on a closed watch never reads as far as the end.
    private static InputStream typedAfter(final CountDownLatch until, final String lines,
                                          final CountDownLatch given) {
        return new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(final byte[] into, final int from, final int wanted) {
                try {
                    until.await();
                } catch (final InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                final int read = super.read(into, from, wanted);
                given.countDown();
                return read;
            }
        };
    }
}
