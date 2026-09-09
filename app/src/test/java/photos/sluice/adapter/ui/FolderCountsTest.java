package photos.sluice.adapter.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRuns;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FolderCountsTest {

    // Comfortably past the 200ms a read is given before the screen says it is reading.
    private static final long SLOWER_THAN_THE_WAIT = 400;

    private final Pipeline pipeline = mock(Pipeline.class);

    private volatile Runnable redraw = () -> { };

    @BeforeEach
    void aStagedLibraryAndAnInboxWithSomethingInIt() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10)))), 0));
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));
    }

    @Test
    void aReadNobodyAskedForIsOverAsFarAsAnyoneWaitingIsConcerned() {
        final FolderCounts counts = this.counts();

        counts.refreshUnprompted();

        assertThat(counts.isCounting()).isFalse();
    }

    @Test
    void aReadTheScreenAskedForCountsForAsLongAsItWalks() {
        final FolderCounts counts = this.counts();
        final var walks = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            walks.incrementAndGet();
            assertThat(counts.isCounting()).isTrue();
            return new InboxTally(300, 1_000_000L);
        });

        counts.refresh();

        // A read that never reached the tally would pass the assertion above having checked
        // nothing.
        assertThat(walks.get()).isEqualTo(1);
        assertThat(counts.isCounting()).isFalse();
    }

    @Test
    void aReadNobodyAskedForCannotLowerTheFlagAReadThatWasAskedForRaised() throws Exception {
        final FolderCounts counts = this.counts();
        final var pollIsWalking = new CountDownLatch(1);
        final var letThePollFinish = new CountDownLatch(1);
        final var walks = new AtomicInteger();
        final var countingDuringTheAskedForWalk = new AtomicBoolean();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            if (walks.incrementAndGet() == 1) {
                pollIsWalking.countDown();
                assertThat(letThePollFinish.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                countingDuringTheAskedForWalk.set(counts.isCounting());
            }
            return new InboxTally(300, 1_000_000L);
        });
        final var poll = new Thread(counts::refreshUnprompted);
        poll.start();
        assertThat(pollIsWalking.await(5, TimeUnit.SECONDS)).isTrue();

        final var afterTheRun = new Thread(counts::refresh);
        afterTheRun.start();
        // Parked on the lock, which is the whole arrangement. Released before it gets there, the
        // asked-for read never queues behind the poll and the test proves nothing.
        waitUntilParked(afterTheRun);
        letThePollFinish.countDown();
        poll.join(5_000);
        afterTheRun.join(5_000);

        // The flag defaults to the passing value, so a walk that never happened would read as a
        // pass.
        assertThat(walks.get()).isEqualTo(2);
        assertThat(countingDuringTheAskedForWalk).isTrue();
    }

    // Under a read that counts itself in only once it holds the lock, nothing is counting here and
    // this fails.
    @Test
    void aReadThatWasAskedForCountsWhileItWaitsForTheWalkAhead() throws Exception {
        final FolderCounts counts = this.counts();
        final var pollIsWalking = new CountDownLatch(1);
        final var letThePollFinish = new CountDownLatch(1);
        final var walks = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            if (walks.incrementAndGet() == 2) {
                pollIsWalking.countDown();
                assertThat(letThePollFinish.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return new InboxTally(300, 1_000_000L);
        });
        // A first read that lands, so this is past the state it opens in having read nothing.
        counts.refreshUnprompted();
        assertThat(counts.isCounting()).isFalse();

        final var poll = new Thread(counts::refreshUnprompted);
        poll.start();
        assertThat(pollIsWalking.await(5, TimeUnit.SECONDS)).isTrue();
        final var afterTheRun = new Thread(counts::refresh);
        afterTheRun.start();
        waitUntilParked(afterTheRun);

        final boolean countingWhileItWaited = counts.isCounting();
        letThePollFinish.countDown();
        poll.join(5_000);
        afterTheRun.join(5_000);

        assertThat(walks.get()).isEqualTo(3);
        assertThat(countingWhileItWaited).isTrue();
        // A read that counts itself in and never out passes everything above this line.
        assertThat(counts.isCounting()).isFalse();
    }

    // A flag cleared on the first read's way out reads as finished here.
    @Test
    void anAskedForReadStillWalkingHoldsTheCountAFinishedOneWouldHaveDropped() throws Exception {
        final FolderCounts counts = this.counts();
        final var firstIsWalking = new CountDownLatch(1);
        final var letTheFirstFinish = new CountDownLatch(1);
        final var secondIsWalking = new CountDownLatch(1);
        final var letTheSecondFinish = new CountDownLatch(1);
        final var walks = new AtomicInteger();
        // The second read is held mid-walk as well as the first. Released, it would finish in
        // microseconds and the moment this test is about would be gone before it could be read.
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            final int walk = walks.incrementAndGet();
            if (walk == 2) {
                firstIsWalking.countDown();
                assertThat(letTheFirstFinish.await(5, TimeUnit.SECONDS)).isTrue();
            } else if (walk == 3) {
                secondIsWalking.countDown();
                assertThat(letTheSecondFinish.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return new InboxTally(300, 1_000_000L);
        });
        counts.refreshUnprompted();

        final var first = new Thread(counts::refresh);
        first.start();
        assertThat(firstIsWalking.await(5, TimeUnit.SECONDS)).isTrue();
        final var second = new Thread(counts::refresh);
        second.start();
        waitUntilParked(second);
        letTheFirstFinish.countDown();
        first.join(5_000);
        assertThat(secondIsWalking.await(5, TimeUnit.SECONDS)).isTrue();

        final boolean countingOnceTheFirstWasOut = counts.isCounting();
        letTheSecondFinish.countDown();
        second.join(5_000);

        assertThat(walks.get()).isEqualTo(3);
        assertThat(countingOnceTheFirstWasOut).isTrue();
    }

    // The window is what makes this weak rather than flaky. On a slow enough machine the second
    // read might not have reached the tally even unserialised, and it would pass for the wrong
    // reason. It cannot fail against correct code.
    @Test
    void oneReadWaitsForAnotherRatherThanWalkingBesideIt() throws Exception {
        final FolderCounts counts = this.counts();
        final var arrived = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var entries = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            entries.incrementAndGet();
            arrived.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new InboxTally(1, 1L);
        });

        final Thread first = Thread.ofVirtual().start(counts::refresh);
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
        final Thread second = Thread.ofVirtual().start(counts::refresh);
        assertThat(second.join(Duration.ofMillis(200))).isFalse();
        assertThat(entries).hasValue(1);

        release.countDown();
        assertThat(first.join(Duration.ofSeconds(5))).isTrue();
        assertThat(second.join(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aReadThatFinishesAtOnceNeverDrawsTheScreen() throws Exception {
        final var repaints = new AtomicInteger();
        this.redraw = repaints::incrementAndGet;

        this.counts().refresh();

        Thread.sleep(SLOWER_THAN_THE_WAIT);
        assertThat(repaints.get()).isZero();
    }

    @Test
    void aReadStillGoingAfterTheWaitDrawsTheScreenWhileItIsStillCounting() throws Exception {
        final var held = new CountDownLatch(1);
        final List<Boolean> countingWhenDrawn = new ArrayList<>();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            return new InboxTally(12, 4_300);
        });
        final FolderCounts slow = this.counts();
        this.redraw = () -> countingWhenDrawn.add(slow.isCounting());

        final Thread walking = Thread.ofVirtual().start(slow::refresh);
        Thread.sleep(SLOWER_THAN_THE_WAIT);

        // Drawn after the read had finished, the screen would say it is counting when it is not.
        assertThat(countingWhenDrawn).containsExactly(true);
        held.countDown();
        assertThat(walking.join(Duration.ofSeconds(5))).isTrue();
    }

    // Without its own catch the failure falls to the walk's, which blanks the Inbox and Sorted
    // counts and tells the reader those could not be read.
    @Test
    void aRunsFolderThatThrowsLeavesTheInboxAndSortedCountsAlone() {
        final FolderCounts counts = this.counts();
        when(this.pipeline.cullRuns()).thenThrow(new IllegalStateException("the folder is gone"));

        counts.refresh();

        assertThat(counts.unreadable()).isFalse();
        assertThat(counts.countsAreIn()).isTrue();
        assertThat(counts.stagedYears()).isNotEmpty();
        assertThat(requireNonNull(counts.inbox()).files()).isEqualTo(300);
        assertThat(counts.unfinished()).isEmpty();
    }

    // A closing window refuses the handover to the screen with this.
    @Test
    void aRepaintTheScreenRefusesLeavesTheNextReadAbleToRun() throws Exception {
        final FolderCounts counts = this.counts();
        final var held = new CountDownLatch(1);
        final var refusals = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            return new InboxTally(300, 1_000_000L);
        });
        this.redraw = () -> {
            refusals.incrementAndGet();
            throw new IllegalStateException("Toolkit not running");
        };
        // Held past the wait, or the read finishes first and the redraw finds nothing to draw.
        final Thread walking = Thread.ofVirtual().start(counts::refresh);
        Thread.sleep(SLOWER_THAN_THE_WAIT);
        held.countDown();
        assertThat(walking.join(Duration.ofSeconds(5))).isTrue();
        assertThat(refusals.get()).isEqualTo(1);

        this.redraw = () -> { };
        counts.refresh();

        assertThat(counts.stagedYears()).isNotEmpty();
        assertThat(counts.isCounting()).isFalse();
    }

    private FolderCounts counts() {
        return new FolderCounts(this.pipeline, () -> this.redraw.run());
    }

    // Waits for a thread to block acquiring the read lock. Two states, because a lock parks a
    // thread as WAITING while the latch above it parks as TIMED_WAITING. Which of those this
    // thread reaches first is not something to depend on.
    private static void waitUntilParked(final Thread thread) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("thread never parked: " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }
}
