package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.Kind;
import photos.sluice.adapter.ui.RunsView.RunCard;
import photos.sluice.application.port.in.SiftJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.sift.SiftRunSummary;
import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.DiscardReport;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.PrepDirHealth;
import photos.sluice.domain.sift.PurgeReport;
import photos.sluice.domain.sift.PrepDirHealth.State;
import photos.sluice.domain.job.ShardTally;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunsPresenterTest {

    private static final Path ARCHIVES = Path.of("logs", "archives");

    @Nested
    class TheCards {

        @Test
        void everyUnfinishedRunGetsOneAndTheFinishedOnesAreHeldApart() {
            final RunsPresenter presenter = presenterOver(run("2019", State.WAITING),
                    run("2018", State.COMPLETE));

            final RunsView view = presenter.view();

            assertThat(view.unfinished()).extracting(RunCard::scope).containsExactly("2019");
            assertThat(view.completed()).extracting(RunCard::scope).containsExactly("2018");
            assertThat(view.completedHeading()).isEqualTo("Finished sifts (1)");
        }

        @Test
        void theyAreOrderedByTimeframeWhateverStateEachRunIsIn() {
            final RunsPresenter presenter = presenterOver(run("2018", State.READY),
                    run("2016", State.DAMAGED), run("2017", State.BLOCKED), run("2015", State.WAITING));

            assertThat(presenter.view().unfinished()).extracting(RunCard::scope)
                    .containsExactly("2015", "2016", "2017", "2018");
        }

        @Test
        void aBlockedRunNamesWhichKindOfFaultStoppedIt() {
            final var health = new PrepDirHealth(State.BLOCKED,
                    List.of(new Finding.CorruptIndex(Path.of("a")), new Finding.CorruptIndex(Path.of("b"))));
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019", Path.of("p"), health,
                    new ShardTally(2, 2, 2), Instant.now()));

            assertThat(presenter.view().unfinished().getFirst().detail())
                    .isEqualTo("This sift's records could not be read, so none of the photos in it "
                            + "were moved.");
        }

        @Test
        void aBlockedRunSpanningTwoKindsOfFaultNamesNeitherRatherThanPickingOne() {
            final var health = new PrepDirHealth(State.BLOCKED,
                    List.of(new Finding.CorruptIndex(Path.of("a")), new Finding.MissingReason("m", 0)));
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019", Path.of("p"), health,
                    new ShardTally(2, 2, 2), Instant.now()));

            assertThat(presenter.view().unfinished().getFirst().detail())
                    .isEqualTo("There were problems with the sift, so none of the photos in it were "
                            + "moved.");
        }

        @Test
        void aRunFoldedShutStaysOffTheScreenUntilTheSectionIsOpened() {
            final RunsPresenter presenter = presenterOver(run("2019", State.COMPLETE));
            assertThat(presenter.view().completedShown()).isFalse();

            presenter.toggleCompleted();

            assertThat(presenter.view().completedShown()).isTrue();
        }

        @Test
        void aRunNobodyCouldStatIsAgedAsNotKnownRatherThanAsADateIn1970() {
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019", Path.of("p"),
                    new PrepDirHealth(State.DAMAGED, List.of()), null, Instant.EPOCH));

            assertThat(presenter.view().unfinished().getFirst().age()).isEqualTo("Last activity: not known");
        }

        @Test
        void aRunLastActiveDaysAgoSaysHowManyDays() {
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019", Path.of("p"),
                    new PrepDirHealth(State.WAITING, List.of()), new ShardTally(1, 1, 2),
                    Instant.now().minus(Duration.ofDays(3))));

            assertThat(presenter.view().unfinished().getFirst().age()).isEqualTo("Last activity: 3 days ago");
        }
    }

    @Nested
    class TheButtonsOnACard {

        @Test
        void aRunWhoseShardsAreAllInIsOfferedAWayToFinishIt() {
            final RunsPresenter presenter = presenterOver(run("2019", State.READY));

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(card.headline()).isEqualTo("Ready to finish");
            assertThat(card.actions()).extracting(Action::label)
                    .containsExactly("Finish this sift", "Discard");
        }

        @Test
        void aWaitingRunAnAgentJudgesSaysThePressLooksAgainBeforeItFinishes() {
            final RunsPresenter waiting = presenterOver(run("2019", State.WAITING));
            final RunsPresenter ready = presenterOver(run("2018", State.READY));

            assertThat(waiting.view().unfinished().getFirst().actions()).extracting(Action::label)
                    .containsExactly("Check and finish", "Finish without the missing sheets", "Discard");
            assertThat(ready.view().unfinished().getFirst().actions()).extracting(Action::label)
                    .containsExactly("Finish this sift", "Discard");
        }

        @Test
        void theWayOnLeadsAndThrowingAwayDoesNot() {
            final RunsPresenter presenter = presenterOver(run("2019", State.READY));

            assertThat(presenter.view().unfinished().getFirst().actions())
                    .extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Finish this sift", true), tuple("Discard", false));
        }

        @Test
        void aBlockedOrDamagedRunIsOfferedNoWayToCarryOn() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED),
                    run("2018", State.DAMAGED));

            assertThat(presenter.view().unfinished()).allSatisfy(card ->
                    assertThat(card.actions()).extracting(Action::kind)
                            .doesNotContain(Kind.CONTINUE, Kind.CONTINUE_WITHOUT_MISSING_SHEETS));
        }

        @Test
        void onlyABlockedRunIsOfferedTheWayIntoWhatIsWrongWithIt() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED),
                    run("2018", State.DAMAGED));

            assertThat(cardFor(presenter, "2019").actions()).extracting(Action::kind)
                    .containsExactly(Kind.TROUBLESHOOT, Kind.DISCARD);
            assertThat(cardFor(presenter, "2018").actions()).extracting(Action::kind)
                    .containsExactly(Kind.DISCARD);
        }

        @Test
        void theWayIntoWhatIsWrongLeadsAndThrowingAwayDoesNot() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED));

            assertThat(presenter.view().unfinished().getFirst().actions())
                    .extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Troubleshoot", true), tuple("Discard", false));
        }

        @Test
        void pressingItOpensThatScreenForTheRunItNamed() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED));
            final var opened = new AtomicReference<@Nullable Path>();
            final var named = new AtomicReference<@Nullable String>();
            presenter.setOpenTroubleshoot((prepDir, scope) -> {
                opened.set(prepDir);
                named.set(scope);
            });

            presenter.press(onlyActionOfKind(presenter, Kind.TROUBLESHOOT));

            assertThat(opened.get()).isEqualTo(Path.of("logs", "sift-prep", "2019"));
            assertThat(named.get()).isEqualTo("2019");
        }

        @Test
        void aFinishedRunIsOfferedNothingAtAll() {
            final RunsPresenter presenter = presenterOver(run("2019", State.COMPLETE));

            assertThat(presenter.view().completed().getFirst().actions()).isEmpty();
        }

        @Test
        void theyAllGoDeadWhileAJobThisScreenStartedIsStillRunning() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.READY))));
            final JobHandle<DiscardReport> job = neverFinishes();
            when(pipeline.discard(any())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

            assertThat(presenter.working()).isTrue();
            assertThat(presenter.view().unfinished().getFirst().actions()).isEmpty();
        }

        // Stubbed rather than pressed. A sift carried on from here runs on the dashboard, so this
        // screen holds no handle to the very job its own press caused.
        @Test
        void theyAllGoDeadWhileAJobStartedAnywhereElseIsStillRunning() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                    run("2019", State.READY), run("2018", State.COMPLETE))));
            when(pipeline.isBusy()).thenReturn(true);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().actions()).isEmpty();
            assertThat(presenter.view().canClearCompleted()).isFalse();
        }

        @Test
        void aRefusedPressLeavesTheScreenAbleToTryAgain() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.READY))));
            when(pipeline.resume(any(), anyBoolean()))
                    .thenThrow(new JobInProgressException("busy"));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

            assertThat(presenter.working()).isFalse();
            assertThat(presenter.view().unfinished().getFirst().actions()).isNotEmpty();
        }
    }

    @Nested
    class ReadingTheFolder {

        // Both come back empty from found(), and the screen has to say opposite things about them.
        @Test
        void aFailureSaysSoRatherThanReportingNoRuns() {
            final Path root = Path.of("logs", "sift-prep");
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Unlistable(root));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unreadable()).contains(root.toString());
            assertThat(presenter.view().nothingYet()).isNull();
        }

        @Test
        void aFolderHoldingNoRunsSaysThereAreNoneRatherThanThatItCouldNotBeRead() {
            final RunsPresenter presenter = presenterOver();

            assertThat(presenter.view().nothingYet()).isNotNull();
            assertThat(presenter.view().unreadable()).isNull();
        }

        @Test
        void theSidebarCountsEveryRunThatHasNotFinished() {
            final RunsPresenter presenter = presenterOver(run("2019", State.WAITING),
                    run("2018", State.BLOCKED), run("2017", State.COMPLETE));

            assertThat(presenter.unfinishedRuns()).isEqualTo(2);
        }

        @Test
        void theSidebarCountsNoneWhereTheFolderCouldNotBeRead() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Unlistable(Path.of("p")));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.unfinishedRuns()).isZero();
        }

        @Test
        void aFolderNobodyHasConfiguredYetIsReportedTheSameWayAnyOtherFailureIs() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenThrow(new PathsMisconfiguredException(List.of()));
            final var presenter = runsPresenter(pipeline);

            presenter.refresh();

            assertThat(presenter.view().message()).isNotNull();
            assertThat(presenter.view().unfinished()).isEmpty();
            assertThat(presenter.unfinishedRuns()).isZero();
        }

        // A refusal describes the run as it stood at the press. The next read can find a different one,
        // and leaving the screen and coming back is what takes that read.
        @Test
        void oneClearsWhatAPressHadToReport() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any()))
                    .thenThrow(new Pipeline.NothingToRedoException(prepDir));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();
            presenter.judgeAgain(prepDir, "2019");
            assertThat(presenter.view().message()).isNotNull();

            presenter.refresh();

            assertThat(presenter.view().message()).isNull();
        }

        @Test
        void oneThatWorksClearsWhatAFailedOneHadToReport() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns())
                    .thenThrow(new IllegalStateException("nope"))
                    .thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();
            assertThat(presenter.view().message()).isNotNull();

            presenter.refresh();

            assertThat(presenter.view().message()).isNull();
            assertThat(presenter.view().unfinished()).hasSize(1);
        }

        @Test
        void aFolderSettingRefusalReachesTheScreenRatherThanEscaping() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenThrow(new IllegalStateException("nope"));
            final var presenter = runsPresenter(pipeline);

            presenter.refresh();

            assertThat(presenter.view().message()).isNotNull();
            assertThat(presenter.view().unfinished()).isEmpty();
        }

        @Test
        void nothingIsAskedOfTheFacadeUntilTheScreenIsRead() {
            final Pipeline pipeline = pipeline();
            runsPresenter(pipeline);

            verify(pipeline, never()).siftRuns();
        }

        @Test
        void aRunMovingWithNobodyLookingRedrawsBothTheCountAndTheCards() throws Exception {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final var listener = new AtomicReference<@Nullable Runnable>(null);
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                return null;
            }).when(pipeline).onRunsChanged(any());
            final var presenter = runsPresenter(pipeline);
            final var countDrawn = new CountDownLatch(1);
            final var cardsDrawn = new CountDownLatch(1);
            presenter.setRedrawCount(countDrawn::countDown);
            presenter.setRedrawCards(cardsDrawn::countDown);

            requireNonNull(listener.get()).run();

            assertThat(countDrawn.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cardsDrawn.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        void aRunMovingTakesOneRatherThanOnePerThingItRedraws() throws Exception {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final var listener = new AtomicReference<@Nullable Runnable>(null);
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                return null;
            }).when(pipeline).onRunsChanged(any());
            final var presenter = runsPresenter(pipeline);
            final var bothDrawn = new CountDownLatch(2);
            presenter.setRedrawCount(bothDrawn::countDown);
            presenter.setRedrawCards(bothDrawn::countDown);

            requireNonNull(listener.get()).run();

            assertThat(bothDrawn.await(5, TimeUnit.SECONDS)).isTrue();
            verify(pipeline).siftRuns();
        }
    }

    @Nested
    class Discarding {

        @Test
        void asksFirstAndNamesWhatItSetsAsideAndWhereItGoes() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("p"), new PrepDirHealth(State.WAITING, List.of()),
                    new ShardTally(17, 17, 28), Instant.now()))));
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

            assertThat(discard.confirm()).isNotNull();
            assertThat(discard.confirm().heading()).isEqualTo("Discard the sift of 2019?");
            assertThat(discard.confirm().detail())
                    .contains("17 sheet decisions you have already paid for")
                    .contains(ARCHIVES.toString())
                    .doesNotContain("graveyard");
        }

        @Test
        void aRunJudgedByTheirOwnAgentCountsTheSheetsWithoutClaimingTheyPaid() {
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019", Path.of("p"),
                    new PrepDirHealth(State.WAITING, List.of()), new ShardTally(17, 17, 28), Instant.now()));

            final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

            assertThat(requireNonNull(discard.confirm()).detail())
                    .contains("17 sheet decisions are set aside with it")
                    .doesNotContain("paid");
        }

        @Test
        void aRunNobodyCouldCountNamesNoSheetsAtAll() {
            final RunsPresenter presenter = presenterOver(run("2019", State.DAMAGED));

            final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

            assertThat(discard.confirm()).isNotNull();
            assertThat(discard.confirm().detail()).isEqualTo("Discarding this sift's records will archive them. They will "
                    + "stay on disk in " + ARCHIVES + " for 30 days.");
        }

        @Test
        void theQuestionPutsNoPriceOnWhatIsBeingSetAside() {
            final RunsPresenter presenter = presenterOver(run("2019", State.WAITING));

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().actions().getLast().confirm()).detail())
                    .doesNotContain("cent").doesNotContain("dollar").doesNotContain("$");
        }

        // A watcher applying an agent's last shard finishes a run unattended. So this is reachable
        // between the screen being drawn and the button being pressed, rather than only by misuse.
        @Test
        void aRunThatFinishedFirstIsSaidInWordsRatherThanAsABug() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            when(pipeline.discard(any())).thenThrow(
                    new Pipeline.RunAlreadyFinishedException(Path.of("logs", "sift-prep", "2019")));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

            assertThat(presenter.view().message()).isNotNull();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .startsWith("This sift finished before it could be discarded");
        }

        // The failure arrives on the job's own promise rather than out of the call that started it,
        // reaching a different arm than a refusal does.
        @Test
        void aJobThatFailsRatherThanBeingRefusedStillSaysSoAndFreesTheScreen() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final JobHandle<DiscardReport> job = failing(new JobInProgressException("Something else is running."));
            when(pipeline.discard(any())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

            assertThat(presenter.working()).isFalse();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .isEqualTo("Something else is running.");
        }

        @Test
        void aRunMovingInTheBackgroundLeavesThePressesOwnMessageStanding() throws Exception {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final JobHandle<DiscardReport> job = failing(new JobInProgressException("Something else is running."));
            when(pipeline.discard(any())).thenReturn(job);
            final var listener = new AtomicReference<@Nullable Runnable>(null);
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                return null;
            }).when(pipeline).onRunsChanged(any());
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();
            presenter.press(presenter.view().unfinished().getFirst().actions().getLast());
            final var redrawn = new CountDownLatch(1);
            presenter.setRedrawCards(redrawn::countDown);

            requireNonNull(listener.get()).run();
            assertThat(redrawn.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(requireNonNull(presenter.view().message()).text())
                    .isEqualTo("Something else is running.");
        }
    }

    @Nested
    class CarryingARunOn {

        @Test
        void handsThatRunsOwnFolderToTheFacade() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    prepDir, new PrepDirHealth(State.READY, List.of()), new ShardTally(3, 3, 3), Instant.now()))));
            final JobHandle<SiftJobOutcome> job = finished();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

            verify(pipeline).resume(prepDir, false);
        }

        @Test
        void putsItsProgressOnTheDashboard() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.READY))));
            final JobHandle<SiftJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final RunLauncherPresenter dashboard = dashboard(pipeline);
            final var presenter = new RunsPresenter(pipeline, dashboard);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

            assertThat(dashboard.stage()).isInstanceOfSatisfying(RunStage.Running.class,
                    running -> assertThat(running.progress().scope()).isEqualTo("2019"));
        }

        @Test
        void takesTheReaderToWhereItReports() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.READY))));
            final JobHandle<SiftJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            final var opened = new AtomicInteger();
            presenter.setOpenDashboard(opened::incrementAndGet);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

            assertThat(opened).hasValue(1);
        }

        @Test
        void aRefusedPressIsSaidOnTheDashboardTheReaderIsSentTo() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.READY))));
            when(pipeline.resume(any(), anyBoolean()))
                    .thenThrow(new JobInProgressException("Something else is running."));
            final RunLauncherPresenter dashboard = dashboard(pipeline);
            final var presenter = new RunsPresenter(pipeline, dashboard);
            presenter.refresh();

            presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

            assertThat(requireNonNull(dashboard.setup().view().message()).text())
                    .isEqualTo("Something else is running.");
            assertThat(presenter.view().message()).isNull();
            assertThat(presenter.view().unfinished()).hasSize(1);
        }

        @Test
        void goingOnWithoutTheMissingSheetsIsWhatTheFacadeIsAskedFor() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final JobHandle<SiftJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(onlyActionOfKind(presenter, Kind.CONTINUE_WITHOUT_MISSING_SHEETS));

            verify(pipeline).resume(prepDir, true);
        }

        @Test
        void finishingWaitsForEverySheetUnlessTheOtherButtonIsPressed() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final JobHandle<SiftJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.press(onlyActionOfKind(presenter, Kind.CONTINUE));

            verify(pipeline).resume(prepDir, false);
        }

        @Test
        void aPressThatCouldNotStartAnythingSaysSoRatherThanOpeningTheDashboard() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            final JobHandle<SiftJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final RunLauncherPresenter dashboard = dashboard(pipeline);
            final var presenter = new RunsPresenter(pipeline, dashboard);
            final var opened = new AtomicInteger();
            presenter.setOpenDashboard(opened::incrementAndGet);
            presenter.refresh();
            // The first press takes the slot and is the run the second one would be shown instead of.
            presenter.press(onlyActionOfKind(presenter, Kind.CONTINUE));
            presenter.refresh();

            presenter.press(onlyActionOfKind(presenter, Kind.CONTINUE));

            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("only one job runs at a time")
                    .contains("was not continued");
            assertThat(opened).hasValue(1);
            verify(pipeline, times(1)).resume(any(), anyBoolean());
        }

        // A run with every sheet in can still be waiting, and the press would then do what Finish does
        // under a name saying otherwise.
        @Test
        void thereIsNoWayPastTheMissingSheetsOnceNoneAreMissing() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                    new SiftRunSummary("2019", Path.of("logs", "sift-prep", "2019"),
                            new PrepDirHealth(State.WAITING, List.of()), new ShardTally(4, 4, 4),
                            Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().actions()).extracting(Action::kind)
                    .doesNotContain(Kind.CONTINUE_WITHOUT_MISSING_SHEETS);
        }
    }

    @Nested
    class ClearingTheFinishedRuns {

        // Clearing is the header's own button, so it has to go dead by itself rather than by there
        // being no card to press.
        @Test
        void isOfferedOnlyWhileSomethingHasFinished() {
            assertThat(presenterOver(run("2019", State.WAITING)).view().canClearCompleted()).isFalse();
            assertThat(presenterOver(run("2019", State.COMPLETE)).view().canClearCompleted()).isTrue();
        }

        @Test
        void asksFirstAndNamesEveryRunItWouldTake() {
            final RunsView view = presenterOver(run("2019", State.COMPLETE),
                    run("2018", State.COMPLETE), run("2020", State.WAITING)).view();

            final Confirmation asked = requireNonNull(view.clearConfirm());
            assertThat(asked.heading()).isEqualTo("Clear the records of 2 finished sifts?");
            assertThat(asked.detail()).contains("2019").contains("2018").doesNotContain("2020");
            assertThat(asked.goAhead()).isEqualTo("Clear them");
            assertThat(asked.cancel()).isEqualTo("Keep them");
        }

        @Test
        void keepingIsTheChoiceTheQuestionLeadsWith() {
            final RunsView view = presenterOver(run("2019", State.COMPLETE)).view();

            assertThat(requireNonNull(view.clearConfirm()).goAheadLeads()).isFalse();
        }

        @Test
        void theQuestionSaysWhatIsNotTouched() {
            final RunsView view = presenterOver(run("2019", State.COMPLETE)).view();

            assertThat(requireNonNull(view.clearConfirm()).detail())
                    .contains("Your photos are not touched");
        }

        @Test
        void thereIsNothingToAskAboutWhereNothingHasFinished() {
            assertThat(presenterOver(run("2019", State.WAITING)).view().clearConfirm()).isNull();
        }

        @Test
        void goesThroughTheFacade() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.COMPLETE))));
            final JobHandle<PurgeReport> job = finished();
            when(pipeline.purgeCompleted()).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.clearCompleted();

            verify(pipeline).purgeCompleted();
        }

        @Test
        void oneRunLeftBehindIsSaidInTheSingular() {
            assertThat(sweptSaying(new PurgeReport(List.of("2018"),
                    Map.of("2019", State.WAITING), Map.of(), null)))
                    .isEqualTo("Cleared 1 finished sift. 1 sift has not finished, so nothing from it was "
                            + "touched.");
        }

        @Test
        void severalRunsLeftBehindAreSaidInThePlural() {
            assertThat(sweptSaying(new PurgeReport(List.of("2017", "2018"),
                    Map.of("2019", State.WAITING), Map.of("2020", "held open"), null)))
                    .isEqualTo("Cleared 2 finished sifts. 2 sifts have not finished, so nothing from them "
                            + "was touched.");
        }

        @Test
        void aSweepThatLeftNothingBehindSaysOnlyWhatItCleared() {
            assertThat(sweptSaying(new PurgeReport(List.of("2018"), Map.of(), Map.of(), null)))
                    .isEqualTo("Cleared 1 finished sift.");
        }
    }

    @Nested
    class TheWaitingBlock {

        @Test
        void onAnAgentItOffersTheFolderAndTheInstructions() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            when(pipeline.configuredProviderSpends()).thenReturn(false);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunsView.Waiting waiting = requireNonNull(presenter.view().unfinished().getFirst().waiting());

            assertThat(waiting.folder()).isEqualTo(Path.of("logs", "sift-prep", "2019"));
            assertThat(waiting.copyPrompt()).isNotNull();
        }

        @Test
        void onAProviderThatSpendsItOffersNoInstructionsAndNoWayPastTheMissingSheets() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(requireNonNull(card.waiting()).copyPrompt()).isNull();
            assertThat(requireNonNull(card.waiting()).note()).contains("provider account balance");
            assertThat(card.actions()).extracting(Action::kind)
                    .doesNotContain(Kind.CONTINUE_WITHOUT_MISSING_SHEETS);
        }

        @Test
        void onlyAWaitingRunCarriesOne() {
            final RunsPresenter presenter = presenterOver(run("2019", State.READY),
                    run("2018", State.BLOCKED), run("2017", State.DAMAGED), run("2016", State.COMPLETE));

            assertThat(presenter.view().unfinished()).extracting(RunCard::waiting).containsOnlyNulls();
            assertThat(presenter.view().completed()).extracting(RunCard::waiting).containsOnlyNulls();
        }

        @Test
        void theInstructionsAreNotWrittenUntilSomebodyAsksForThem() {
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            when(pipeline.launchPromptFor(any())).thenReturn("Sift the photo sheets in ...");
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            presenter.view();
            verify(pipeline, never()).launchPromptFor(any());

            // The control, and what makes the assertion above about the drawing rather than about a
            // facade nothing would have called either way.
            presenter.instructionsFor(prepDir, false);
            verify(pipeline).launchPromptFor(prepDir);
        }

        @Test
        void instructionsThatCouldNotBeWrittenSayWhyAndCopyNothing() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.WAITING))));
            when(pipeline.launchPromptFor(any()))
                    .thenThrow(new MalformedPrepJsonException("index.json will not parse",
                            new IllegalStateException("unexpected end of input")));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.instructionsFor(Path.of("logs", "sift-prep", "2019"), false)).isNull();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .isEqualTo("That sift's own records could not be read, because what is in "
                            + "them is damaged.")
                    .doesNotContain(MalformedPrepJsonException.class.getName());
        }

        // An answer that came back and was refused is still an agent that started, and it is the one
        // thing the follow-up exists to discard. Counted on what arrived, never on what passed.
        @Test
        void aRunWhoseOnlyAnswerCameBackUnusableIsOfferedTheFollowUp() {
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"),
                    new PrepDirHealth(State.WAITING,
                            List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")))),
                    new ShardTally(2, 0, 4), Instant.now()));

            final RunsView.Waiting waiting = requireNonNull(
                    presenter.view().unfinished().getFirst().waiting());

            assertThat(waiting.copyPrompt()).isEqualTo("Copy a follow-up for your agent");
            assertThat(waiting.promptCorrects()).isTrue();
        }

        // Not a follow-up until an agent has answered something. Nothing judged and sheets still owed
        // is an agent that has not started, and the reader's move then is to start it again.
        @Test
        void aRunAnAgentHasNotAnsweredYetIsOfferedTheInstructionsRatherThanAFollowUp() {
            final RunsPresenter presenter = presenterOver(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.WAITING, List.of()),
                    new ShardTally(0, 0, 4), Instant.now()));

            final RunsView.Waiting waiting = requireNonNull(
                    presenter.view().unfinished().getFirst().waiting());

            assertThat(waiting.copyPrompt()).isEqualTo("Copy instructions for your agent");
            assertThat(waiting.promptCorrects()).isFalse();
        }
    }

    @Nested
    class TheSheetTally {

        @Test
        void aRunWhoseSheetsNobodyCountedShowsNoLineRatherThanAZero() {
            final RunsPresenter presenter = presenterOver(run("2019", State.DAMAGED));

            assertThat(presenter.view().unfinished().getFirst().sheets()).isNull();
        }

        @Test
        void separatesTheSheetsThatCameBackWrongFromTheOnesStillMissing() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.WAITING, List.of()),
                    new ShardTally(4, 2, 6), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().sheets())
                    .isEqualTo("2 out of 6 sheets are judged and healthy. 2 came back wrong "
                            + "and 2 are still missing.");
        }

        @Test
        void withOneOfEachItAgreesWithItselfOnSingularAndPlural() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2016",
                    Path.of("logs", "sift-prep", "2016"), new PrepDirHealth(State.WAITING, List.of()),
                    new ShardTally(9, 8, 10), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().sheets())
                    .isEqualTo("8 out of 10 sheets are judged and healthy. 1 came back wrong "
                            + "and 1 is still missing.");
        }

        @Test
        void whereEverySheetIsInItNamesOnlyWhatWasJudged() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.READY, List.of()),
                    new ShardTally(4, 4, 4), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().sheets())
                    .isEqualTo("4 out of 4 sheets are judged and healthy.");
        }

        @Test
        void aBlockedRunWhoseSheetsAreAllInAndAllSoundSaysNothingAboutThem() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"),
                    new PrepDirHealth(State.BLOCKED, List.of(new Finding.MissingSource(
                            Path.of("Sorted", "Photos", "2019", "06", "gone.jpg"),
                            Path.of("logs", "sift-prep", "2019", "moves.csv")))),
                    new ShardTally(1, 1, 1), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().sheets()).isNull();
        }

        @Test
        void stillOwedSheetsItSaysSoWithoutNamingAnythingWrong() {
            final RunsPresenter presenter = presenterOver(run("2019", State.WAITING));

            assertThat(presenter.view().unfinished().getFirst().sheets())
                    .isEqualTo("2 out of 4 sheets are judged and healthy. 2 are still missing.");
        }
    }

    @Nested
    class JudgingTheSheetsAgain {

        // A blocked run has no waiting block to carry the follow-up, so the card holds it instead.
        @Test
        void onAnAgentRouteABlockedRunCarriesItOnTheCard() {
            final RunsPresenter presenter = presenterOver(runWithRejectedAnswers("2019", State.BLOCKED));

            final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

            assertThat(redo.label()).isEqualTo("Copy a follow-up for your agent");
            assertThat(redo.note()).contains("every sheet still outstanding");
            assertThat(redo.confirm()).isNull();
            assertThat(redo.leading()).isFalse();
        }

        // Waiting draws a block, and the block carries the follow-up, so a second control on the card
        // would offer the same press twice.
        @Test
        void onAnAgentRouteAWaitingRunCarriesItNowhereButItsBlock() {
            final RunsPresenter presenter = presenterOver(runWithRejectedAnswers("2019", State.WAITING));

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(card.redo()).isNull();
            assertThat(requireNonNull(card.waiting()).copyPrompt())
                    .isEqualTo("Copy a follow-up for your agent");
            assertThat(requireNonNull(card.waiting()).promptCorrects()).isTrue();
        }

        @Test
        void aRunHeldUpOnlyByItsAnswersIsLedByItAndNotByFinishing() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(requireNonNull(card.redo()).leading()).isTrue();
            assertThat(card.actions()).extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Finish this sift", false), tuple("Discard", false));
        }

        // A CorruptShard finding gives FindingWords no answer, so Troubleshoot would open onto a
        // screen with nothing to press. The redo control clears the run outright and is the way on.
        @Test
        void onAProviderThatSpendsARunASheetAloneBlamesLeadsWithItNotTroubleshoot() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                    run("2019", State.BLOCKED,
                            List.of(new Finding.CorruptShard("montage-002", "decisions-002.json"))))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(requireNonNull(card.redo()).leading()).isTrue();
            assertThat(card.actions()).extracting(Action::kind, Action::leading)
                    .contains(tuple(Kind.TROUBLESHOOT, false));
        }

        // Redoing the sheets cannot clear a photo that has gone from disk, so a press that spends is
        // filled only where it finishes the job.
        @Test
        void onAProviderThatSpendsARunAlsoHeldUpBySomethingElseOffersItQuietly() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                    run("2019", State.BLOCKED, List.of(
                            new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")),
                            new Finding.MissingSource(Path.of("a.jpg"), Path.of("moves.log")))))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).leading()).isFalse();
        }

        // The same run leads with the way back where the app does the judging and could clear it.
        @Test
        void onAnAgentRouteItNeverLeads() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED, List.of(
                    new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")),
                    new Finding.MissingSource(Path.of("a.jpg"), Path.of("moves.log")))));

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).leading()).isFalse();
        }

        // The waiting block holds this offer on the state either side of a blocked one. A card putting
        // it in the button row would move it under the press that acts on it.
        @Test
        void onAnAgentRouteItSitsWithTheCardsTextRatherThanInTheButtonRow() {
            final RunsPresenter presenter = presenterOver(runWithRejectedAnswers("2019", State.BLOCKED));

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).drawnAt()).isNull();
        }

        @Test
        void itIsDrawnBetweenFinishingAndThrowingAway() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(
                    runWithRejectedAnswers("2019", State.WAITING),
                    runWithRejectedAnswers("2018", State.BLOCKED))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final List<RunCard> cards = presenter.view().unfinished();

            assertThat(requireNonNull(cards.get(1).redo()).drawnAt()).isEqualTo(1);
            assertThat(requireNonNull(cards.getFirst().redo()).drawnAt()).isZero();
        }

        @Test
        void onAProviderThatSpendsTheSamePressNamesTheMoneyAndAsksFirst() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

            assertThat(redo.label()).isEqualTo("Judge the faulty sheets again");
            assertThat(redo.note()).contains("Any sheets still missing are judged too")
                    .contains("spends from your provider account balance");
            assertThat(requireNonNull(redo.confirm()).detail())
                    .contains("spends from your provider account balance");
        }

        // The press dispatches every sheet without a usable answer, which on a waiting run is more
        // than the ones that came back wrong. Four sheets, three arrived, one of those blamed: the
        // blamed one and the sheet that never came, and not the two that are fine.
        @Test
        void theQuestionCountsTheSheetsStillMissingAlongsideTheOnesComingBackWrong() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"),
                    new PrepDirHealth(State.WAITING,
                            List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")))),
                    new ShardTally(3, 2, 4), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

            assertThat(requireNonNull(redo.confirm()).detail()).startsWith("2 sheets will be judged");
        }

        // The tally counts one shard at a time for display, so a fault spanning two of them leaves
        // both counted valid. The press dispatches them regardless, and the question says so.
        @Test
        void theQuestionCountsWhatThePressDispatchesRatherThanWhatTheTallyCallsInvalid() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(new SiftRunSummary("2019",
                    Path.of("logs", "sift-prep", "2019"),
                    new PrepDirHealth(State.BLOCKED, List.of(
                            new Finding.GroupSpansMultipleMontages("harbour",
                                    List.of("montage-001", "montage-002")),
                            new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")),
                            new Finding.PhotosNotJudged("montage-002", List.of("IMG_2.jpg")))),
                    new ShardTally(4, 4, 4), Instant.now()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

            assertThat(requireNonNull(redo.confirm()).detail()).startsWith("2 sheets will be judged");
        }

        // Dispatching is what this press pays for, and there is nothing here to dispatch.
        @Test
        void onAProviderThatSpendsARunHeldUpBySomethingNoSheetCanAnswerForIsOfferedNone() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2019", State.BLOCKED,
                    List.of(new Finding.CorruptIndex(Path.of("index.json")))))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
        }

        // Writing a sheet again cannot mend a damaged index, so the press would be one an agent could
        // not answer. Both routes withhold it on the same ground.
        @Test
        void onAnAgentRouteARunNoSheetCanAnswerForIsOfferedNone() {
            final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED,
                    List.of(new Finding.CorruptIndex(Path.of("index.json")))));

            assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
        }

        @Test
        void onAProviderThatSpendsARunStillShortOfSheetsIsOfferedItBesideItsWaitingBlock() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunCard card = presenter.view().unfinished().getFirst();

            assertThat(card.waiting()).isNotNull();
            assertThat(card.redo()).isNotNull();
        }

        @Test
        void onAnAgentRouteAWaitingCardDressesNoPressAsTheWayOn() {
            final RunsPresenter blamed = presenterOver(runWithRejectedAnswers("2019", State.WAITING));
            final RunsPresenter clean = presenterOver(run("2018", State.WAITING, List.of()));

            assertThat(blamed.view().unfinished().getFirst().actions())
                    .extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Check and finish", false),
                            tuple("Finish without the missing sheets", false), tuple("Discard", false));
            assertThat(clean.view().unfinished().getFirst().actions())
                    .extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Check and finish", false),
                            tuple("Finish without the missing sheets", false), tuple("Discard", false));
        }

        @Test
        void onAProviderThatSpendsAWaitingCardWithNothingBlamedStillLeadsWithFinishing() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(run("2019", State.WAITING, List.of()))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().actions())
                    .extracting(Action::label, Action::leading)
                    .containsExactly(tuple("Finish this sift", true), tuple("Discard", false));
        }

        // Filed sheets make the reading behind the card stale, so the screen is told to take another.
        @Test
        void aPressThatFreedSheetsHasTheScreenReadTheRunsAgain() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var repainted = new AtomicInteger();
            final var presenter = runsPresenter(pipeline);
            presenter.setRepaint(repainted::incrementAndGet);
            presenter.refresh();

            assertThat(presenter.instructionsFor(prepDir, true)).isEqualTo("write them again");

            assertThat(repainted.get()).isEqualTo(1);
        }

        // The press files sheets away, so the read it sets off is the one that builds the card the
        // reader is looking at. A read that cleared what the press had just set would leave it blank.
        @Test
        void theCopyControlReadsCopiedAfterTheReadItsOwnPressSetOff() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var presenter = runsPresenter(pipeline);
            presenter.setRepaint(presenter::refresh);
            presenter.refresh();

            presenter.instructionsFor(prepDir, true);

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().waiting()).copyPrompt())
                    .isEqualTo("Copied");
        }

        @Test
        void theReadAfterThatPutsTheCopyControlBackToItsOrdinaryLabel() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var presenter = runsPresenter(pipeline);
            presenter.setRepaint(presenter::refresh);
            presenter.refresh();
            presenter.instructionsFor(prepDir, true);

            presenter.refresh();

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().waiting()).copyPrompt())
                    .isEqualTo("Copy a follow-up for your agent");
        }

        @Test
        void aRunMovingInTheBackgroundLeavesTheCopiedLabelStanding() throws Exception {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var listener = new AtomicReference<@Nullable Runnable>(null);
            doAnswer(call -> {
                listener.set(call.getArgument(0));
                return null;
            }).when(pipeline).onRunsChanged(any());
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();
            presenter.instructionsFor(prepDir, true);
            final var redrawn = new CountDownLatch(1);
            presenter.setRedrawCards(redrawn::countDown);

            requireNonNull(listener.get()).run();
            assertThat(redrawn.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(requireNonNull(presenter.view().unfinished().getFirst().waiting()).copyPrompt())
                    .isEqualTo("Copied");
        }

        // A press that freed nothing leaves the card describing the run correctly, so a second reading
        // would cost a folder walk to learn what is already held.
        @Test
        void aPressOnAStalledRunHasTheScreenReadNothingAgain() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            when(pipeline.redoRejectedAnswers(any()))
                    .thenThrow(new Pipeline.NothingToRedoException(prepDir));
            when(pipeline.launchPromptFor(any())).thenReturn("Sift the photo sheets in ...");
            final var repainted = new AtomicInteger();
            final var presenter = runsPresenter(pipeline);
            presenter.setRepaint(repainted::incrementAndGet);
            presenter.refresh();

            assertThat(presenter.instructionsFor(prepDir, true)).isEqualTo("Sift the photo sheets in ...");

            assertThat(repainted.get()).isZero();
        }

        // It files sheets into the drawer, so a resume already applying them would have work taken out
        // from under it.
        @Test
        void itGoesFromTheWaitingBlockWhileAJobIsRunning() {
            final Pipeline pipeline = pipeline();
            when(pipeline.isBusy()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.WAITING))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            final RunsView.Waiting waiting = requireNonNull(
                    presenter.view().unfinished().getFirst().waiting());

            assertThat(waiting.copyPrompt()).isEqualTo("Copy instructions for your agent");
            assertThat(waiting.promptCorrects()).isFalse();
        }

        @Test
        void itGoesFromTheCardWhileAJobIsRunning() {
            final Pipeline pipeline = pipeline();
            when(pipeline.isBusy()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
        }

        @Test
        void askingForTheAnswersAgainHandsBackWhatTheFacadeWrote() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(Path.of("logs", "sift-prep", "2019"), "2019"))
                    .isEqualTo("write them again");
            assertThat(presenter.view().message()).isNull();
        }

        @Test
        void onAProviderThatSpendsARefusedPressReportsOnTheScreenAndHandsBackNothingToCopy() {
            final Pipeline pipeline = pipeline();
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.redoRejectedAnswers(any())).thenThrow(new Pipeline.NothingToRedoException(prepDir));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("waiting to be judged again").contains("already been handled");
        }

        // The run has stalled rather than gone wrong, so asking afresh is what its reader needs.
        @Test
        void onAnAgentRouteARunWithNothingToRedoIsAskedAfreshRatherThanRefused() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any())).thenThrow(new Pipeline.NothingToRedoException(prepDir));
            when(pipeline.launchPromptFor(any())).thenReturn("Sift the photo sheets in ...");
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isEqualTo("Sift the photo sheets in ...");
            assertThat(presenter.view().message()).isNull();
        }

        // One press, or the reader is left holding a run whose paid-for answers are gone and whose
        // sheets nobody has been asked to judge.
        @Test
        void onAProviderThatSpendsOnePressFreesTheSheetsAndStartsJudgingThem() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final JobHandle<SiftJobOutcome> job = finished();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();

            verify(pipeline).redoRejectedAnswers(prepDir);
            verify(pipeline).resume(prepDir, false);
        }

        // An agent outside the app does the judging on this route. However the press that frees the
        // sheets is worded, nothing is dispatched at the app's expense.
        @Test
        void onAnAgentRouteTheSamePressStartsNothing() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isEqualTo("write them again");

            verify(pipeline, never()).resume(any(), anyBoolean());
        }

        // A refused freeing must not go on to spend. The sheets it would have judged still hold the
        // answers the press was going to discard.
        @Test
        void aRefusedFreeingSpendsNothingOnAProviderThatWould() {
            final Pipeline pipeline = pipeline();
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.configuredProviderSpends()).thenReturn(true);
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            when(pipeline.redoRejectedAnswers(any()))
                    .thenThrow(new Pipeline.NothingToRedoException(prepDir));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();

            verify(pipeline, never()).resume(any(), anyBoolean());
        }

        @Test
        void aPressBlockedByALockedFileReportsItWithTheTechnicalTextToQuote() {
            final Pipeline pipeline = pipeline();
            when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(
                    List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
            final Path prepDir = Path.of("logs", "sift-prep", "2019");
            when(pipeline.redoRejectedAnswers(any()))
                    .thenThrow(new UncheckedIOException(new IOException("montage-003.json")));
            final var presenter = runsPresenter(pipeline);
            presenter.refresh();

            assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("A file could not be reached")
                    .contains("montage-003.json");
        }
    }

    private static RunsPresenter presenterOver(final SiftRunSummary... runs) {
        final Pipeline pipeline = pipeline();
        when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(runs)));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();
        return presenter;
    }

    private static Pipeline pipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.archivesFolder()).thenReturn(ARCHIVES);
        return pipeline;
    }

    // A real dashboard rather than a double, so a test asking where a press reported can read the
    // face it actually put up.
    private static RunsPresenter runsPresenter(final Pipeline pipeline) {
        return new RunsPresenter(pipeline, dashboard(pipeline));
    }

    private static RunLauncherPresenter dashboard(final Pipeline pipeline) {
        return new RunLauncherPresenter(pipeline, new FxProgressPort(Runnable::run));
    }

    // By scope rather than by position. The cards are sorted by timeframe, so which one is first
    // turns on what the other runs in the fixture are called.
    private static RunCard cardFor(final RunsPresenter presenter, final String scope) {
        return presenter.view().unfinished().stream()
                .filter(card -> card.scope().equals(scope))
                .findFirst()
                .orElseThrow();
    }

    // By kind rather than by position, so a button added beside it does not silently re-point the
    // press at a different one.
    private static Action onlyActionOfKind(final RunsPresenter presenter, final Kind kind) {
        final List<Action> matching = presenter.view().unfinished().getFirst().actions().stream()
                .filter(action -> action.kind() == kind)
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static SiftRunSummary run(final String scope, final State state) {
        return run(scope, state, List.of());
    }

    private static SiftRunSummary run(final String scope, final State state, final List<Finding> findings) {
        return new SiftRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(state, findings),
                state == State.DAMAGED || state == State.COMPLETE ? null : new ShardTally(2, 2, 4),
                Instant.now());
    }

    private static SiftRunSummary runWithRejectedAnswers(final String scope, final State state) {
        return run(scope, state, List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg"))));
    }

    // Drives the real clear path rather than calling the wording directly, so the message the
    // screen ends up holding is what gets asserted.
    @SuppressWarnings("unchecked")
    private static String sweptSaying(final PurgeReport report) {
        final Pipeline pipeline = pipeline();
        when(pipeline.siftRuns()).thenReturn(new SiftRuns.Listed(List.of(run("2018", State.COMPLETE))));
        final JobHandle<PurgeReport> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(report));
        when(pipeline.purgeCompleted()).thenReturn(handle);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.clearCompleted();

        return requireNonNull(presenter.view().message()).text();
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> finished() {
        final JobHandle<T> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(null));
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> failing(final RuntimeException failure) {
        final JobHandle<T> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.failedFuture(failure));
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> neverFinishes() {
        final JobHandle<T> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        return handle;
    }
}
