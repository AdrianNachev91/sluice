package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.out.ProviderType;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static photos.sluice.application.service.PipelineTestSupport.assertHoldsFor;
import static photos.sluice.application.service.PipelineTestSupport.waitUntil;

class SiftWatchersTest {

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    private static final Duration TICK = Duration.ofMillis(20);

    private final AtomicInteger announcements = new AtomicInteger();

    private final AtomicInteger polls = new AtomicInteger();

    private final AtomicReference<ShardTallyCalculator.Reading> reading =
            new AtomicReference<>(new ShardTallyCalculator.Reading(false, new ShardTally(1, 1, 3)));

    private final AtomicReference<JobHandle<SiftJobOutcome>> resumed =
            new AtomicReference<>(neverFinishes());

    private final AtomicBoolean busy = new AtomicBoolean();

    private final AutoResumedSifts autoResumedSifts = new AutoResumedSifts();

    private @Nullable SiftWatchers watchers;

    // Every test here arms a real watcher on a real thread. Left running, its next poll lands after
    // the test that started it has finished.
    @AfterEach
    void retireEverything() {
        final SiftWatchers armed = this.watchers;
        if (armed != null) {
            armed.disarmAll();
        }
    }

    // The tally is changed only once a poll has read the one before it. A change made before that
    // first poll is seeded as the starting value, and is then never a change at all.
    @Test
    void aSheetArrivingIsAnnouncedWithoutTheRunHavingToFinish() {
        this.arm();
        final int atArming = this.announcements.get();
        waitUntil(Duration.ofSeconds(2), () -> this.polls.get() >= 1);

        this.reading.set(new ShardTallyCalculator.Reading(false, new ShardTally(2, 2, 3)));

        waitUntil(Duration.ofSeconds(2), () -> this.announcements.get() > atArming);
        assertThat(requireNonNull(this.watchers).isWatchActive(PREP_DIR)).isTrue();
    }

    // A poll that announced whatever it read would fire on every tick. No test asserting that a
    // sheet arrived could tell that apart from the real thing.
    @Test
    void aFolderThatHasNotMovedIsAnnouncedOnNoTickAtAll() {
        this.arm();
        final int atArming = this.announcements.get();

        assertThat(requireNonNull(this.watchers).isWatchActive(PREP_DIR)).isTrue();
        assertHoldsFor(Duration.ofMillis(200), () -> this.announcements.get() == atArming);
    }

    // The wait is what makes this the readable-to-unreadable transition. Without it the null is
    // already in place when polling starts, and the guard under test is never reached.
    @Test
    void aFolderThatCouldNotBeReadIsAnnouncedAsNothing() {
        this.arm();
        final int atArming = this.announcements.get();
        waitUntil(Duration.ofSeconds(2), () -> this.polls.get() >= 1);

        this.reading.set(new ShardTallyCalculator.Reading(false, null));

        assertHoldsFor(Duration.ofMillis(200), () -> this.announcements.get() == atArming);
    }

    @Test
    void aWatchArmedOverARetiredOnesTallyStillAnnouncesNothingOnItsFirstPoll() {
        this.arm();
        waitUntil(Duration.ofSeconds(2), () -> this.polls.get() >= 1);
        requireNonNull(this.watchers).disarmWatch(PREP_DIR);
        // The folder moved while nothing was watching it, which is what the stale tally would be
        // compared against.
        this.reading.set(new ShardTallyCalculator.Reading(false, new ShardTally(2, 2, 3)));

        this.watchers.armWatch(PREP_DIR);
        final int atRearming = this.announcements.get();
        final int polledAtRearming = this.polls.get();

        waitUntil(Duration.ofSeconds(2), () -> this.polls.get() > polledAtRearming);
        assertHoldsFor(Duration.ofMillis(200), () -> this.announcements.get() == atRearming);
    }

    @Test
    void armingAWatchAnnouncesThatTheRunMoved() {
        this.arm();

        assertThat(this.announcements.get()).isEqualTo(1);
    }

    @Test
    void armingAWatchThatIsAlreadyPollingAnnouncesNothing() {
        this.arm();
        final int atArming = this.announcements.get();

        requireNonNull(this.watchers).armWatch(PREP_DIR);

        assertThat(this.watchers.isWatchActive(PREP_DIR)).isTrue();
        assertHoldsFor(Duration.ofMillis(200), () -> this.announcements.get() == atArming);
    }

    @Test
    void retiringOneWatchAnnouncesItWhileRetiringThemAllDoesNot() {
        this.arm();
        final int atArming = this.announcements.get();

        requireNonNull(this.watchers).disarmWatch(PREP_DIR);
        assertThat(this.announcements.get()).isEqualTo(atArming + 1);

        this.watchers.armWatch(PREP_DIR);
        final int atRearming = this.announcements.get();
        this.watchers.disarmAll();

        assertThat(this.watchers.isWatchActive(PREP_DIR)).isFalse();
        assertThat(this.announcements.get()).isEqualTo(atRearming);
    }

    @Test
    void aResumeThatGoesInHandsTheJobAndTheRunsNameToWhoeverIsListening() {
        final var told = new AtomicReference<@Nullable String>();
        final var job = new AtomicReference<@Nullable JobHandle<SiftJobOutcome>>();
        this.autoResumedSifts.onResumed((scope, resumedJob) -> {
            told.set(scope);
            job.set(resumedJob);
        });
        this.reading.set(new ShardTallyCalculator.Reading(true, new ShardTally(3, 3, 3)));
        this.arm();

        waitUntil(Duration.ofSeconds(2), () -> told.get() != null);
        assertThat(told.get()).isEqualTo("2019");
        assertThat(job.get()).isSameAs(this.resumed.get());
    }

    @Test
    void aResumeRefusedByABusyRunnerAnnouncesNothing() {
        final var told = new AtomicInteger();
        this.autoResumedSifts.onResumed((_, _) -> told.incrementAndGet());
        this.reading.set(new ShardTallyCalculator.Reading(true, new ShardTally(3, 3, 3)));
        this.busy.set(true);
        this.arm();

        waitUntil(Duration.ofSeconds(2), () -> this.polls.get() >= 2);
        assertThat(told.get()).isZero();
    }

    private void arm() {
        final ShardTallyCalculator calculator = mock(ShardTallyCalculator.class);
        when(calculator.poll(any())).thenAnswer(_ -> {
            this.polls.incrementAndGet();
            return this.reading.get();
        });
        this.watchers = new SiftWatchers(ProviderType.MANUAL::equals, calculator, TICK,
                _ -> this.resumeOrRefuse(), runChanges(this.announcements), this.autoResumedSifts);
        this.watchers.armWatch(PREP_DIR);
    }

    private JobHandle<SiftJobOutcome> resumeOrRefuse() {
        if (this.busy.get()) {
            throw new JobInProgressException("Something else is running.");
        }
        return this.resumed.get();
    }

    private static RunChanges runChanges(final AtomicInteger announcements) {
        final var changes = new RunChanges();
        changes.onMoved(announcements::incrementAndGet);
        return changes;
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<SiftJobOutcome> neverFinishes() {
        final JobHandle<SiftJobOutcome> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        return handle;
    }
}
