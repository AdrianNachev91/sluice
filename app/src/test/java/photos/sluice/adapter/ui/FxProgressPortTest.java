package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FxProgressPortTest {

    private final List<Runnable> handedToTheToolkit = new ArrayList<>();

    private final FxProgressPort port = new FxProgressPort(this.handedToTheToolkit::add);

    @Test
    void aSecondWatcherIsRunAlongsideTheScreenThatOwnsTheArea() throws Exception {
        final var screen = new AtomicInteger();
        final var alongside = new AtomicInteger();
        this.port.setRepaint(screen::incrementAndGet);
        try (AutoCloseable _ = this.port.alsoRedraw(alongside::incrementAndGet)) {
            this.port.phaseStarted("Copying...");
            this.runWhatTheToolkitWasHanded();
        }

        assertThat(screen.get()).isEqualTo(1);
        assertThat(alongside.get()).isEqualTo(1);
    }

    @Test
    void aWatcherThatHasClosedIsNotRunAgain() throws Exception {
        final var alongside = new AtomicInteger();
        this.port.alsoRedraw(alongside::incrementAndGet).close();

        this.port.phaseStarted("Copying...");
        this.runWhatTheToolkitWasHanded();

        assertThat(alongside.get()).isZero();
    }

    @Test
    void everyPhaseAJobPlansIsListedBeforeAnyOfThemStarts() {
        this.port.phasesPlanned(List.of("Reading photos...", "Sifting...", "Applying decisions..."));

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Reading photos...", 0, 0, false, false, 0),
                new ProgressPhase("Sifting...", 0, 0, false, false, 0),
                new ProgressPhase("Applying decisions...", 0, 0, false, false, 0));
    }

    @Test
    void aPlannedPhaseStartingIsMarkedBegunWhereItAlreadyStands() {
        this.port.phasesPlanned(List.of("Reading photos...", "Sifting..."));

        this.port.phaseStarted("Sifting...");

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Reading photos...", 0, 0, false, false, 0),
                new ProgressPhase("Sifting...", 0, 0, true, false, 0));
    }

    @Test
    void aFreshPlanReplacesWhateverTheJobBeforeItLeft() {
        this.port.phasesPlanned(List.of("Sorting..."));
        this.port.phaseStarted("Sorting...");
        this.port.tick("Sorting...", 40, 100);

        this.port.phasesPlanned(List.of("Moving to library..."));

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Moving to library...", 0, 0, false, false, 0));
    }

    @Test
    void aStartedPhaseIsListedWithNothingDoneYet() {
        this.port.phaseStarted("Sorting...");

        assertThat(this.port.phases()).containsExactly(new ProgressPhase("Sorting...", 0, 0, true, false, 0));
    }

    @Test
    void aTickCountsAgainstItsOwnPhaseAndLeavesTheOthersWhereTheyWere() {
        this.port.phaseStarted("Sorting...");
        this.port.phaseStarted("Sifting...");

        this.port.tick("Sorting...", 850, 1204);

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Sorting...", 850, 1204, true, false, 0),
                new ProgressPhase("Sifting...", 0, 0, true, false, 0));
    }

    @Test
    void aPhaseRunTwiceCountsTheSecondRunApartFromTheFirst() {
        this.port.phaseStarted("Applying decisions...");
        this.port.tick("Applying decisions...", 3, 28);
        this.port.phaseFinished("Applying decisions...");
        this.port.phaseStarted("Applying decisions...");

        this.port.tick("Applying decisions...", 12, 28);

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Applying decisions...", 3, 28, true, true, 0),
                new ProgressPhase("Applying decisions...", 12, 28, true, false, 0));
    }

    @Test
    void aPartialReadingCountsAgainstTheUnitAfterTheOnesAlreadyDone() {
        this.port.phaseStarted("Moving to library...");

        this.port.tickWithin("Moving to library...", 3, 8, 0.45);

        assertThat(this.port.phases())
                .containsExactly(new ProgressPhase("Moving to library...", 3, 8, true, false, 0.45));
    }

    @Test
    void thePartialReadingGoesBackToNothingOnTheNextWholeUnit() {
        this.port.phaseStarted("Moving to library...");
        this.port.tickWithin("Moving to library...", 3, 8, 0.45);

        this.port.tick("Moving to library...", 4, 8);

        assertThat(this.port.phases())
                .containsExactly(new ProgressPhase("Moving to library...", 4, 8, true, false, 0));
    }

    @Test
    void aPhaseEndingPartWayThroughAFileKeepsOnlyItsWholeUnits() {
        this.port.phaseStarted("Moving to library...");
        this.port.tickWithin("Moving to library...", 3, 8, 0.45);

        this.port.phaseFinished("Moving to library...");

        assertThat(this.port.phases())
                .containsExactly(new ProgressPhase("Moving to library...", 3, 8, true, true, 0));
    }

    @Test
    void aTickForAPhaseNobodyStartedIsDroppedWhileOneForAStartedPhaseStillCounts() {
        this.port.phaseStarted("Sorting...");

        this.port.tick("Rescuing...", 1, 2);
        this.port.tick("Sorting...", 1, 2);

        assertThat(this.port.phases()).containsExactly(new ProgressPhase("Sorting...", 1, 2, true, false, 0));
    }

    @Test
    void anEndForAPhaseNobodyStartedIsDroppedWhileOneForAStartedPhaseStillEndsIt() {
        this.port.phaseStarted("Sorting...");
        this.port.tick("Sorting...", 1, 2);

        this.port.phaseFinished("Rescuing...");
        assertThat(this.port.phases()).containsExactly(new ProgressPhase("Sorting...", 1, 2, true, false, 0));

        this.port.phaseFinished("Sorting...");

        assertThat(this.port.phases()).containsExactly(new ProgressPhase("Sorting...", 1, 2, true, true, 0));
    }

    @Test
    void aPhaseThatEndsWithoutEverTickingReadsAsFinishedRatherThanAsUnstarted() {
        this.port.phaseStarted("Sorting...");

        this.port.phaseFinished("Sorting...");

        assertThat(this.port.phases()).containsExactly(new ProgressPhase("Sorting...", 0, 0, true, true, 0));
    }

    @Test
    void nothingIsRedrawnUntilTheToolkitRunsWhatItWasHanded() {
        final var redraws = new AtomicInteger();
        this.port.setRepaint(redraws::incrementAndGet);

        this.port.phaseStarted("Sorting...");
        assertThat(redraws).hasValue(0);

        this.runWhatTheToolkitWasHanded();

        assertThat(redraws).hasValue(1);
    }

    // Runs the redraw where the event arrives rather than collecting it. A dispatcher that collects
    // cannot tell the two orders apart, since the snapshot is updated either way by the time
    // anything drains it.
    @Test
    void aRedrawFindsThePhaseThatCausedItRatherThanTheListAsItWasBefore() {
        final List<Integer> phasesSeen = new ArrayList<>();
        final var redrawnWhereTheEventArrives = new FxProgressPort(Runnable::run);
        redrawnWhereTheEventArrives.setRepaint(
                () -> phasesSeen.add(redrawnWhereTheEventArrives.phases().size()));

        redrawnWhereTheEventArrives.phaseStarted("Sorting...");

        assertThat(phasesSeen).containsExactly(1);
    }

    @Test
    void buildingOneAsksTheToolkitForNothingUntilAnEventArrives() {
        assertThat(this.handedToTheToolkit).isEmpty();

        this.port.phaseStarted("Sorting...");

        assertThat(this.handedToTheToolkit).hasSize(1);
    }

    // A plan may name one label twice, and the second entry is still waiting while the first runs.
    // A tick matched from the end without asking which had started would land on the waiting one.
    @Test
    void aTickOnALabelPlannedTwiceCountsAgainstTheRunOfItThatStarted() {
        this.port.phasesPlanned(List.of("Sorting...", "Sorting..."));
        this.port.phaseStarted("Sorting...");

        this.port.tick("Sorting...", 40, 100);

        assertThat(this.port.phases()).containsExactly(
                new ProgressPhase("Sorting...", 40, 100, true, false, 0),
                new ProgressPhase("Sorting...", 0, 0, false, false, 0));
    }

    @Test
    void forgottenPhasesLeaveNoneOfThePreviousJobBehind() {
        this.port.phaseStarted("Sorting...");
        assertThat(this.port.phases()).isNotEmpty();

        this.port.forgetPhases();

        assertThat(this.port.phases()).isEmpty();
    }

    @Test
    void forgettingRedrawsSoTheBarsGoRatherThanStayingOnScreen() {
        final var redraws = new AtomicInteger();
        this.port.setRepaint(redraws::incrementAndGet);

        this.port.forgetPhases();
        this.runWhatTheToolkitWasHanded();

        assertThat(redraws).hasValue(1);
    }

    @Test
    void aPlanLandingRedrawsSoTheWholeRunIsOnScreenBeforeItStarts() {
        final var redraws = new AtomicInteger();
        this.port.setRepaint(redraws::incrementAndGet);

        this.port.phasesPlanned(List.of("Reading photos...", "Sifting..."));
        this.runWhatTheToolkitWasHanded();

        assertThat(redraws).hasValue(1);
    }

    @Test
    void aBurstOfEventsCostsOneRedrawRatherThanOneEach() {
        this.port.phaseStarted("Sorting...");
        this.port.tick("Sorting...", 1, 3);
        this.port.tick("Sorting...", 2, 3);
        this.port.tick("Sorting...", 3, 3);

        assertThat(this.handedToTheToolkit).hasSize(1);
    }

    @Test
    void theOneRedrawOfABurstStillFindsTheLastEventInIt() {
        final List<ProgressPhase> drawn = new ArrayList<>();
        this.port.setRepaint(() -> drawn.addAll(this.port.phases()));

        this.port.phaseStarted("Sorting...");
        this.port.tick("Sorting...", 3, 3);
        this.runWhatTheToolkitWasHanded();

        assertThat(drawn).containsExactly(new ProgressPhase("Sorting...", 3, 3, true, false, 0));
    }

    @Test
    void anEventAfterARedrawHasRunAsksForAnother() {
        this.port.phaseStarted("Sorting...");
        this.runWhatTheToolkitWasHanded();

        this.port.tick("Sorting...", 1, 3);

        assertThat(this.handedToTheToolkit).hasSize(1);
    }

    // The event is raised from inside the redraw, which is where a screen would raise one: the job
    // keeps reporting while the application thread is busy drawing.
    @Test
    void anEventRaisedWhileTheScreenIsDrawingAsksForAnotherRedraw() {
        final var drawnOnce = new AtomicBoolean();
        this.port.setRepaint(() -> {
            if (drawnOnce.compareAndSet(false, true)) {
                this.port.tick("Sorting...", 1, 3);
            }
        });
        this.port.phaseStarted("Sorting...");

        this.runWhatTheToolkitWasHanded();

        assertThat(this.handedToTheToolkit).hasSize(1);
    }

    @Test
    void aRedrawIsDrawnByWhicheverScreenIsRegisteredWhenItRuns() {
        final var screenThatLeft = new AtomicInteger();
        final var screenNowUp = new AtomicInteger();
        this.port.setRepaint(screenThatLeft::incrementAndGet);

        this.port.phaseStarted("Sorting...");
        this.port.setRepaint(screenNowUp::incrementAndGet);
        this.runWhatTheToolkitWasHanded();

        assertThat(screenThatLeft).hasValue(0);
        assertThat(screenNowUp).hasValue(1);
    }

    // The engine reporting the event is moving files, and the window it describes may be gone.
    @Test
    void aJobIsNotFailedByThereBeingNoToolkitToDrawOn() {
        final var noToolkit = new FxProgressPort(_ -> {
            throw new IllegalStateException("Toolkit not initialized");
        });

        assertThatCode(() -> noToolkit.phaseStarted("Sorting...")).doesNotThrowAnyException();

        assertThat(noToolkit.phases()).containsExactly(new ProgressPhase("Sorting...", 0, 0, true, false, 0));
    }

    @Test
    void aSecondEventStillAsksForARedrawAfterAHandOverWasRefused() {
        final List<Runnable> handedOver = new ArrayList<>();
        final var refusesOnce = new AtomicInteger();
        final var port = new FxProgressPort(redraw -> {
            if (refusesOnce.getAndIncrement() == 0) {
                throw new IllegalStateException("Toolkit not initialized");
            }
            handedOver.add(redraw);
        });

        port.phaseStarted("Sorting...");
        assertThat(handedOver).isEmpty();

        port.phaseStarted("Sifting...");

        assertThat(handedOver).hasSize(1);
    }

    // Proves the swap is atomic rather than a read then a write. A read-modify-write loses entries
    // under this much contention; nothing in a single-threaded test can tell the two apart.
    @Test
    void everyPhaseSurvivesWhenManyThreadsReportAtOnce() throws InterruptedException {
        final int reporters = 200;
        final var port = new FxProgressPort(_ -> { });
        final var startTogether = new CountDownLatch(1);
        final var allDone = new CountDownLatch(reporters);
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < reporters; i++) {
            final String label = "phase-" + i;
            threads.add(Thread.startVirtualThread(() -> {
                awaitQuietly(startTogether);
                port.phaseStarted(label);
                allDone.countDown();
            }));
        }

        startTogether.countDown();
        assertThat(allDone.await(30, TimeUnit.SECONDS)).isTrue();
        for (final Thread thread : threads) {
            thread.join();
        }

        assertThat(port.phases()).hasSize(reporters);
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWhatTheToolkitWasHanded() {
        final List<Runnable> pending = List.copyOf(this.handedToTheToolkit);
        this.handedToTheToolkit.clear();
        pending.forEach(Runnable::run);
    }
}
