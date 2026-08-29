package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.Kind;
import photos.sluice.adapter.ui.RunsView.RunCard;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PurgeReport;
import photos.sluice.domain.cull.PrepDirHealth.State;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunsPresenterTest {

    private static final Path ARCHIVES = Path.of("logs", "archives");

    @Test
    void everyUnfinishedRunGetsACardAndTheFinishedOnesAreHeldApart() {
        final RunsPresenter presenter = presenterOver(run("2019", State.WAITING),
                run("2018", State.COMPLETE));

        final RunsView view = presenter.view();

        assertThat(view.unfinished()).extracting(RunCard::scope).containsExactly("2019");
        assertThat(view.completed()).extracting(RunCard::scope).containsExactly("2018");
        assertThat(view.completedHeading()).isEqualTo("Finished runs (1)");
    }

    @Test
    void theCardsAreOrderedByTimelineWhateverStateEachRunIsIn() {
        final RunsPresenter presenter = presenterOver(run("2018", State.READY),
                run("2016", State.DAMAGED), run("2017", State.BLOCKED), run("2015", State.WAITING));

        assertThat(presenter.view().unfinished()).extracting(RunCard::scope)
                .containsExactly("2015", "2016", "2017", "2018");
    }

    @Test
    void aRunWhoseShardsAreAllInIsOfferedAWayToFinishIt() {
        final RunsPresenter presenter = presenterOver(run("2019", State.READY));

        final RunCard card = presenter.view().unfinished().getFirst();

        assertThat(card.headline()).isEqualTo("Ready to finish");
        assertThat(card.actions()).extracting(Action::label)
                .containsExactly("Finish this sift", "Discard");
    }

    // The press attempts the same thing whatever state the run is in, so one label covers all of
    // them. A waiting run that cannot get there says so on the card it lands on.
    @Test
    void carryingARunOnSaysTheSameThingWhateverStateItIsIn() {
        final RunsPresenter waiting = presenterOver(run("2019", State.WAITING));
        final RunsPresenter ready = presenterOver(run("2018", State.READY));

        assertThat(waiting.view().unfinished().getFirst().actions()).extracting(Action::label)
                .containsExactly("Finish this sift", "Discard");
        assertThat(ready.view().unfinished().getFirst().actions()).extracting(Action::label)
                .containsExactly("Finish this sift", "Discard");
    }

    // Which button is drawn to be reached for is the presenter's to say, not the screen's. A screen
    // deciding it from what a press runs would tie the two together for good.
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
                assertThat(card.actions()).extracting(Action::kind).containsExactly(Kind.DISCARD));
    }

    @Test
    void aFinishedRunIsOfferedNothingAtAll() {
        final RunsPresenter presenter = presenterOver(run("2019", State.COMPLETE));

        assertThat(presenter.view().completed().getFirst().actions()).isEmpty();
    }

    @Test
    void aBlockedRunNamesWhichKindOfFaultStoppedIt() {
        final var health = new PrepDirHealth(State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("a")), new Finding.CorruptIndex(Path.of("b"))));
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"), health,
                new ShardTally(2, 2, 2), Instant.now()));

        assertThat(presenter.view().unfinished().getFirst().detail())
                .isEqualTo("This sift's records could not be read, so none of the photos in it "
                        + "were moved.");
    }

    @Test
    void aBlockedRunSpanningTwoKindsOfFaultNamesNeitherRatherThanPickingOne() {
        final var health = new PrepDirHealth(State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("a")), new Finding.MissingReason("m", 0)));
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"), health,
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

    // Both come back empty from found(), and the screen has to say opposite things about them.
    @Test
    void aFolderThatCouldNotBeReadSaysSoRatherThanReportingNoRuns() {
        final Path root = Path.of("logs", "sift-prep");
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(root));
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(Path.of("p")));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.unfinishedRuns()).isZero();
    }

    @Test
    void discardingARunAsksFirstAndNamesWhatItSetsAsideAndWhereItGoes() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                Path.of("p"), new PrepDirHealth(State.WAITING, List.of()),
                new ShardTally(17, 17, 28), Instant.now()))));
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

        assertThat(discard.confirm()).isNotNull();
        assertThat(discard.confirm().heading()).isEqualTo("Discard the sift of 2019?");
        assertThat(discard.confirm().question())
                .contains("17 sheet decisions you have already paid for")
                .contains(ARCHIVES.toString())
                .doesNotContain("graveyard");
    }

    @Test
    void discardingARunJudgedByTheirOwnAgentCountsTheSheetsWithoutClaimingTheyPaid() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"),
                new PrepDirHealth(State.WAITING, List.of()), new ShardTally(17, 17, 28), Instant.now()));

        final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

        assertThat(requireNonNull(discard.confirm()).question())
                .contains("17 sheet decisions are set aside with it")
                .doesNotContain("paid");
    }

    @Test
    void discardingARunNobodyCouldCountNamesNoSheetsAtAll() {
        final RunsPresenter presenter = presenterOver(run("2019", State.DAMAGED));

        final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

        assertThat(discard.confirm()).isNotNull();
        assertThat(discard.confirm().question()).isEqualTo("Discarding this run's records will archive them. They will "
                + "stay on disk in " + ARCHIVES + " for 30 days.");
    }

    @Test
    void noConfirmPutsAPriceOnWhatIsBeingSetAside() {
        final RunsPresenter presenter = presenterOver(run("2019", State.WAITING));

        assertThat(requireNonNull(presenter.view().unfinished().getFirst().actions().getLast().confirm()).question())
                .doesNotContain("cent").doesNotContain("dollar").doesNotContain("$");
    }

    @Test
    void continuingARunHandsThatRunsOwnFolderToTheFacade() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                prepDir, new PrepDirHealth(State.READY, List.of()), new ShardTally(3, 3, 3), Instant.now()))));
        final JobHandle<CullJobOutcome> job = finished();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        verify(pipeline).resume(prepDir, false);
    }

    @Test
    void carryingARunOnPutsItsProgressOnTheDashboard() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
        final JobHandle<CullJobOutcome> job = neverFinishes();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final RunLauncherPresenter dashboard = dashboard(pipeline);
        final var presenter = new RunsPresenter(pipeline, dashboard);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(dashboard.stage()).isInstanceOfSatisfying(RunStage.Running.class,
                running -> assertThat(running.progress().scope()).isEqualTo("2019"));
    }

    @Test
    void carryingARunOnTakesTheReaderToWhereItReports() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
        final JobHandle<CullJobOutcome> job = neverFinishes();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final var presenter = runsPresenter(pipeline);
        final var opened = new AtomicInteger();
        presenter.setOpenDashboard(opened::incrementAndGet);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(opened).hasValue(1);
    }

    @Test
    void aRefusedCarryOnIsSaidOnTheDashboardTheReaderIsSentTo() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
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

    // A watcher applying an agent's last shard finishes a run unattended. So this is reachable
    // between the screen being drawn and the button being pressed, rather than only by misuse.
    @Test
    void discardingARunThatFinishedFirstIsSaidInWordsRatherThanAsABug() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.discard(any())).thenThrow(
                new Pipeline.RunAlreadyFinishedException(Path.of("logs", "sift-prep", "2019")));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

        assertThat(presenter.view().message()).isNotNull();
        assertThat(requireNonNull(presenter.view().message()).text())
                .startsWith("That sift finished before it could be discarded");
    }

    @Test
    void aFolderNobodyHasConfiguredYetIsReportedTheSameWayAnyOtherFailedReadIs() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenThrow(new PathsMisconfiguredException(List.of()));
        final var presenter = runsPresenter(pipeline);

        presenter.refresh();

        assertThat(presenter.view().message()).isNotNull();
        assertThat(presenter.view().unfinished()).isEmpty();
        assertThat(presenter.unfinishedRuns()).isZero();
    }

    // A refusal describes the run as it stood at the press. The next read can find a different one,
    // and leaving the screen and coming back is what takes that read.
    @Test
    void aReadClearsWhatAPressHadToReport() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
    void aReadThatWorksClearsWhatAFailedOneHadToReport() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns())
                .thenThrow(new IllegalStateException("nope"))
                .thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();
        assertThat(presenter.view().message()).isNotNull();

        presenter.refresh();

        assertThat(presenter.view().message()).isNull();
        assertThat(presenter.view().unfinished()).hasSize(1);
    }

    // The failure arrives on the job's own promise rather than out of the call that started it, so
    // it reaches a different arm than a refusal does.
    @Test
    void aJobThatFailsRatherThanBeingRefusedStillSaysSoAndFreesTheScreen() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final JobHandle<DiscardReport> job = failing(new JobInProgressException("Something else is running."));
        when(pipeline.discard(any())).thenReturn(job);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

        assertThat(presenter.working()).isFalse();
        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Something else is running.");
    }

    // Clearing is the header's own button, so it has to go dead by itself rather than by there
    // being no card to press.
    @Test
    void clearingIsOfferedOnlyWhileSomethingHasFinished() {
        assertThat(presenterOver(run("2019", State.WAITING)).view().canClearCompleted()).isFalse();
        assertThat(presenterOver(run("2019", State.COMPLETE)).view().canClearCompleted()).isTrue();
    }

    @Test
    void clearingTheFinishedRunsGoesThroughTheFacade() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.COMPLETE))));
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
                .isEqualTo("Cleared 1 finished run. 1 run has not finished, so nothing from it was "
                        + "touched.");
    }

    @Test
    void severalRunsLeftBehindAreSaidInThePlural() {
        assertThat(sweptSaying(new PurgeReport(List.of("2017", "2018"),
                Map.of("2019", State.WAITING), Map.of("2020", "held open"), null)))
                .isEqualTo("Cleared 2 finished runs. 2 runs have not finished, so nothing from them "
                        + "was touched.");
    }

    @Test
    void aSweepThatLeftNothingBehindSaysOnlyWhatItCleared() {
        assertThat(sweptSaying(new PurgeReport(List.of("2018"), Map.of(), Map.of(), null)))
                .isEqualTo("Cleared 1 finished run.");
    }

    @Test
    void aFolderSettingRefusalReachesTheScreenRatherThanEscaping() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenThrow(new IllegalStateException("nope"));
        final var presenter = runsPresenter(pipeline);

        presenter.refresh();

        assertThat(presenter.view().message()).isNotNull();
        assertThat(presenter.view().unfinished()).isEmpty();
    }

    @Test
    void aRunNobodyCouldStatIsAgedAsNotKnownRatherThanAsADateIn1970() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"),
                new PrepDirHealth(State.DAMAGED, List.of()), null, Instant.EPOCH));

        assertThat(presenter.view().unfinished().getFirst().age()).isEqualTo("Last activity: not known");
    }

    @Test
    void aRunLastActiveDaysAgoSaysHowManyDays() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"),
                new PrepDirHealth(State.WAITING, List.of()), new ShardTally(1, 1, 2),
                Instant.now().minus(Duration.ofDays(3))));

        assertThat(presenter.view().unfinished().getFirst().age()).isEqualTo("Last activity: 3 days ago");
    }

    @Test
    void aRunWhoseSheetsNobodyCountedShowsNoSheetLineRatherThanAZero() {
        final RunsPresenter presenter = presenterOver(run("2019", State.DAMAGED));

        assertThat(presenter.view().unfinished().getFirst().sheets()).isNull();
    }

    @Test
    void everyButtonGoesDeadWhileAJobThisScreenStartedIsStillRunning() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
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
    void everyButtonGoesDeadWhileAJobStartedAnywhereElseIsStillRunning() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
        when(pipeline.resume(any(), anyBoolean()))
                .thenThrow(new JobInProgressException("busy"));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(presenter.working()).isFalse();
        assertThat(presenter.view().unfinished().getFirst().actions()).isNotEmpty();
    }

    @Test
    void nothingIsAskedOfTheFacadeUntilTheScreenIsRead() {
        final Pipeline pipeline = pipeline();
        runsPresenter(pipeline);

        verify(pipeline, never()).cullRuns();
    }

    @Test
    void aWaitingRunOnAnAgentOffersTheFolderTheInstructionsAndTheToggle() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.configuredProviderSpends()).thenReturn(false);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunsView.Waiting waiting = requireNonNull(presenter.view().unfinished().getFirst().waiting());

        assertThat(waiting.folder()).isEqualTo(Path.of("logs", "sift-prep", "2019"));
        assertThat(waiting.copyPrompt()).isNotNull();
        assertThat(waiting.autoApply()).isNotNull();
    }

    @Test
    void aWaitingRunOnAProviderThatSpendsIsOfferedNoWatchToggleAndNoInstructions() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunsView.Waiting waiting = requireNonNull(presenter.view().unfinished().getFirst().waiting());

        assertThat(waiting.autoApply()).isNull();
        assertThat(waiting.copyPrompt()).isNull();
        assertThat(waiting.note()).contains("provider account balance");
    }

    @Test
    void onlyAWaitingRunCarriesAnyOfThat() {
        final RunsPresenter presenter = presenterOver(run("2019", State.READY),
                run("2018", State.BLOCKED), run("2017", State.DAMAGED), run("2016", State.COMPLETE));

        assertThat(presenter.view().unfinished()).extracting(RunCard::waiting).containsOnlyNulls();
        assertThat(presenter.view().completed()).extracting(RunCard::waiting).containsOnlyNulls();
    }

    @Test
    void theToggleSitsWhereTheEngineSaysTheWatchIsRatherThanWhereItWasLeft() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.isWatchActive(any())).thenReturn(true);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(requireNonNull(requireNonNull(
                presenter.view().unfinished().getFirst().waiting()).autoApply()).on()).isTrue();
    }

    @Test
    void turningTheToggleOnArmsThatRunsWatchAndTurningItOffRetiresIt() {
        final Pipeline pipeline = pipeline();
        final var presenter = runsPresenter(pipeline);
        final Path prepDir = Path.of("logs", "sift-prep", "2019");

        presenter.setAutoApply(prepDir, true);
        presenter.setAutoApply(prepDir, false);

        verify(pipeline).startWatching(prepDir);
        verify(pipeline).stopWatching(prepDir);
    }

    @Test
    void aWatchThatCouldNotBeChangedSaysWhyRatherThanFailingSilently() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        doThrow(new JobInProgressException("Something else is running."))
                .when(pipeline).startWatching(any());
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        presenter.setAutoApply(Path.of("logs", "sift-prep", "2019"), true);

        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Something else is running.");
    }

    @Test
    void goingOnWithoutTheMissingSheetsIsWhatTheFacadeIsAskedFor() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final JobHandle<CullJobOutcome> job = neverFinishes();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();
        presenter.setWaiveMissing(prepDir, true);

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        verify(pipeline).resume(prepDir, true);
    }

    @Test
    void theInstructionsAreNotWrittenUntilSomebodyAsksForThem() {
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.launchPromptFor(any()))
                .thenThrow(new MalformedPrepJsonException("index.json will not parse",
                        new IllegalStateException("unexpected end of input")));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.instructionsFor(Path.of("logs", "sift-prep", "2019"), false)).isNull();
        assertThat(requireNonNull(presenter.view().message()).text())
                .doesNotContain("MalformedPrepJsonException");
    }

    @Test
    void aRunMovingWithNobodyLookingRedrawsBothTheCountAndTheCards() throws Exception {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final var listener = new AtomicReference<@Nullable Runnable>(null);
        doAnswer(call -> {
            listener.set(call.getArgument(0));
            return null;
        }).when(pipeline).onRunsMoved(any());
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
    void aRunMovingReadsTheFolderOnceRatherThanOncePerThingItRedraws() throws Exception {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final var listener = new AtomicReference<@Nullable Runnable>(null);
        doAnswer(call -> {
            listener.set(call.getArgument(0));
            return null;
        }).when(pipeline).onRunsMoved(any());
        final var presenter = runsPresenter(pipeline);
        final var bothDrawn = new CountDownLatch(2);
        presenter.setRedrawCount(bothDrawn::countDown);
        presenter.setRedrawCards(bothDrawn::countDown);

        requireNonNull(listener.get()).run();

        assertThat(bothDrawn.await(5, TimeUnit.SECONDS)).isTrue();
        verify(pipeline).cullRuns();
    }

    @Test
    void aTallySeparatesTheSheetsThatCameBackWrongFromTheOnesStillMissing() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.WAITING, List.of()),
                new ShardTally(4, 2, 6), Instant.now()))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().sheets())
                .isEqualTo("2 out of 6 sheets are judged and healthy. 2 came back wrong "
                        + "and 2 are still missing.");
    }

    @Test
    void aTallyWithOneOfEachAgreesWithItselfOnSingularAndPlural() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2016",
                Path.of("logs", "sift-prep", "2016"), new PrepDirHealth(State.WAITING, List.of()),
                new ShardTally(9, 8, 10), Instant.now()))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().sheets())
                .isEqualTo("8 out of 10 sheets are judged and healthy. 1 came back wrong "
                        + "and 1 is still missing.");
    }

    @Test
    void aTallyWhoseSheetsAreAllInNamesOnlyWhatWasJudged() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.READY, List.of()),
                new ShardTally(4, 4, 4), Instant.now()))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().sheets())
                .isEqualTo("4 out of 4 sheets are judged and healthy.");
    }

    @Test
    void aTallyStillOwedSheetsSaysSoWithoutNamingAnythingWrong() {
        final RunsPresenter presenter = presenterOver(run("2019", State.WAITING));

        assertThat(presenter.view().unfinished().getFirst().sheets())
                .isEqualTo("2 out of 4 sheets are judged and healthy. 2 are still missing.");
    }

    // A blocked run has no waiting block to carry the follow-up, so the card holds it instead.
    @Test
    void onAnAgentRouteABlockedRunCarriesTheFollowUpOnTheCard() {
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
    void onAnAgentRouteAWaitingRunCarriesTheFollowUpNowhereButItsBlock() {
        final RunsPresenter presenter = presenterOver(runWithRejectedAnswers("2019", State.WAITING));

        final RunCard card = presenter.view().unfinished().getFirst();

        assertThat(card.redo()).isNull();
        assertThat(requireNonNull(card.waiting()).copyPrompt())
                .isEqualTo("Copy a follow-up for your agent");
        assertThat(requireNonNull(card.waiting()).promptCorrects()).isTrue();
    }

    @Test
    void aRunHeldUpOnlyByItsAnswersIsLedByTheWayBackAndNotByFinishing() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.WAITING))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunCard card = presenter.view().unfinished().getFirst();

        assertThat(requireNonNull(card.redo()).leading()).isTrue();
        assertThat(card.actions()).extracting(Action::label, Action::leading)
                .containsExactly(tuple("Finish this sift", false), tuple("Discard", false));
    }

    // Redoing the sheets cannot clear a photo that has gone from disk, so a press that spends is
    // filled only where it finishes the job.
    @Test
    void onAProviderThatSpendsARunAlsoHeldUpBySomethingElseOffersTheWayBackQuietly() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(
                run("2019", State.BLOCKED, List.of(
                        new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")),
                        new Finding.MissingSource(Path.of("a.jpg"), Path.of("moves.log")))))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).leading()).isFalse();
    }

    // Whatever the run's state, a card whose judging happens outside the app dresses no press as
    // the way on. The same run leads with it where the app does the judging and could clear it.
    @Test
    void onAnAgentRouteTheWayBackNeverLeads() {
        final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED, List.of(
                new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")),
                new Finding.MissingSource(Path.of("a.jpg"), Path.of("moves.log")))));

        assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).leading()).isFalse();
    }

    // The waiting block holds this offer on the state either side of a blocked one. A card putting
    // it in the button row would move it under the press that acts on it.
    @Test
    void onAnAgentRouteTheWayBackSitsWithTheCardsTextRatherThanInTheButtonRow() {
        final RunsPresenter presenter = presenterOver(runWithRejectedAnswers("2019", State.BLOCKED));

        assertThat(requireNonNull(presenter.view().unfinished().getFirst().redo()).drawnAt()).isNull();
    }

    @Test
    void theWayBackIsDrawnBetweenFinishingAndThrowingAway() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

        assertThat(redo.label()).isEqualTo("Judge the faulty sheets again");
        assertThat(redo.note()).contains("Any sheets still missing are judged too")
                .contains("spends from your provider account balance");
        assertThat(requireNonNull(redo.confirm()).question())
                .contains("spends from your provider account balance");
    }

    // The press dispatches every sheet without a usable answer, which on a waiting run is more
    // than the ones that came back wrong. Four sheets, three arrived, one of those blamed: the
    // blamed one and the sheet that never came, and not the two that are fine.
    @Test
    void theQuestionCountsTheSheetsStillMissingAlongsideTheOnesComingBackWrong() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"),
                new PrepDirHealth(State.WAITING,
                        List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")))),
                new ShardTally(3, 2, 4), Instant.now()))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunsView.Redo redo = requireNonNull(presenter.view().unfinished().getFirst().redo());

        assertThat(requireNonNull(redo.confirm()).question()).startsWith("2 sheets will be judged");
    }

    // The tally counts one shard at a time for display, so a fault spanning two of them leaves
    // both counted valid. The press dispatches them regardless, and the question says so.
    @Test
    void theQuestionCountsWhatThePressDispatchesRatherThanWhatTheTallyCallsInvalid() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
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

        assertThat(requireNonNull(redo.confirm()).question()).startsWith("2 sheets will be judged");
    }

    // Nothing a sheet can answer for means nothing to dispatch, and dispatching is what this press
    // pays for.
    @Test
    void onAProviderThatSpendsARunHeldUpBySomethingNoSheetCanAnswerForIsOfferedNoWayBack() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("index.json")))))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
    }

    // Writing a sheet again cannot mend a damaged index, so the press would be one an agent could
    // not answer. Both routes withhold it on the same ground.
    @Test
    void onAnAgentRouteARunNoSheetCanAnswerForIsOfferedNoFollowUp() {
        final RunsPresenter presenter = presenterOver(run("2019", State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("index.json")))));

        assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
    }

    @Test
    void onAProviderThatSpendsARunStillShortOfSheetsIsOfferedTheWayBackBesideItsWaitingBlock() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.WAITING))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunCard card = presenter.view().unfinished().getFirst();

        assertThat(card.waiting()).isNotNull();
        assertThat(card.redo()).isNotNull();
    }

    // An answer that came back and was refused is still an agent that started, and it is the one
    // thing the follow-up exists to discard. Counted on what arrived, never on what passed.
    @Test
    void aRunWhoseOnlyAnswerCameBackUnusableIsOfferedTheFollowUp() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"),
                new PrepDirHealth(State.WAITING,
                        List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")))),
                new ShardTally(2, 0, 4), Instant.now()));

        final RunsView.Waiting waiting = requireNonNull(
                presenter.view().unfinished().getFirst().waiting());

        assertThat(waiting.copyPrompt()).isEqualTo("Copy a follow-up for your agent");
        assertThat(waiting.promptCorrects()).isTrue();
    }

    // Nothing on a waiting card an agent drives is urgent, whether or not an answer came back
    // wrong. Nothing on it is dressed as the way on.
    @Test
    void onAnAgentRouteAWaitingCardDressesNoPressAsTheWayOn() {
        final RunsPresenter blamed = presenterOver(runWithRejectedAnswers("2019", State.WAITING));
        final RunsPresenter clean = presenterOver(run("2018", State.WAITING, List.of()));

        assertThat(blamed.view().unfinished().getFirst().actions())
                .extracting(Action::label, Action::leading)
                .containsExactly(tuple("Finish this sift", false), tuple("Discard", false));
        assertThat(clean.view().unfinished().getFirst().actions())
                .extracting(Action::label, Action::leading)
                .containsExactly(tuple("Finish this sift", false), tuple("Discard", false));
    }

    // The provider's own waiting card keeps its fill, nothing there waiting on anybody outside.
    @Test
    void onAProviderThatSpendsAWaitingCardWithNothingBlamedStillLeadsWithFinishing() {
        final Pipeline pipeline = pipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(run("2019", State.WAITING, List.of()))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().actions())
                .extracting(Action::label, Action::leading)
                .containsExactly(tuple("Finish this sift", true), tuple("Discard", false));
    }

    // Filed sheets make the reading behind the card stale, so the screen is told to take another.
    @Test
    void aFollowUpThatFreedSheetsHasTheScreenReadTheRunsAgain() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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

    // A press that freed nothing leaves the card describing the run correctly, so a second reading
    // would cost a folder walk to learn what is already held.
    @Test
    void aFollowUpOnAStalledRunHasTheScreenReadNothingAgain() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
    void theFollowUpGoesWhileAJobIsRunningLikeEveryOtherControl() {
        final Pipeline pipeline = pipeline();
        when(pipeline.isBusy()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.WAITING))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        final RunsView.Waiting waiting = requireNonNull(
                presenter.view().unfinished().getFirst().waiting());

        assertThat(waiting.copyPrompt()).isEqualTo("Copy instructions for your agent");
        assertThat(waiting.promptCorrects()).isFalse();
    }

    // Not a follow-up until an agent has answered something. Nothing judged and sheets still owed
    // is an agent that has not started, and the reader's move then is to start it again.
    @Test
    void aRunAnAgentHasNotAnsweredYetIsOfferedTheInstructionsRatherThanAFollowUp() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.WAITING, List.of()),
                new ShardTally(0, 0, 4), Instant.now()));

        final RunsView.Waiting waiting = requireNonNull(
                presenter.view().unfinished().getFirst().waiting());

        assertThat(waiting.copyPrompt()).isEqualTo("Copy instructions for your agent");
        assertThat(waiting.promptCorrects()).isFalse();
    }

    @Test
    void theWayBackGoesWhileAJobIsRunningLikeEveryOtherButton() {
        final Pipeline pipeline = pipeline();
        when(pipeline.isBusy()).thenReturn(true);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.view().unfinished().getFirst().redo()).isNull();
    }

    @Test
    void askingForTheAnswersAgainHandsBackWhatTheFacadeWrote() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.redoRejectedAnswers(any())).thenThrow(new Pipeline.NothingToRedoException(prepDir));
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();
        assertThat(requireNonNull(presenter.view().message()).text())
                .contains("nothing to judge again").contains("Nothing was discarded");
    }

    // Nothing to redo is not a refusal where an agent does the judging. The run has stalled rather
    // than gone wrong, and asking afresh is what its reader needs.
    @Test
    void onAnAgentRouteARunWithNothingToRedoIsAskedAfreshRatherThanRefused() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                List.of(runWithRejectedAnswers("2019", State.BLOCKED))));
        when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
        final JobHandle<CullJobOutcome> job = finished();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final var presenter = runsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.judgeAgain(prepDir, "2019")).isNull();

        verify(pipeline).redoRejectedAnswers(prepDir);
        verify(pipeline).resume(prepDir, false);
    }

    // Nothing is dispatched at the app's expense on a route where an agent outside it does the
    // judging, however the press that frees the sheets is worded.
    @Test
    void onAnAgentRouteTheSamePressStartsNothing() {
        final Pipeline pipeline = pipeline();
        final Path prepDir = Path.of("logs", "sift-prep", "2019");
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
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

    private static RunsPresenter presenterOver(final CullRunSummary... runs) {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(runs)));
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

    private static CullRunSummary run(final String scope, final State state) {
        return run(scope, state, List.of());
    }

    private static CullRunSummary run(final String scope, final State state, final List<Finding> findings) {
        return new CullRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(state, findings),
                state == State.DAMAGED || state == State.COMPLETE ? null : new ShardTally(2, 2, 4),
                Instant.now());
    }

    private static CullRunSummary runWithRejectedAnswers(final String scope, final State state) {
        return run(scope, state, List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg"))));
    }

    // Drives the real clear path rather than calling the wording directly, so the message the
    // screen ends up holding is what gets asserted.
    @SuppressWarnings("unchecked")
    private static String sweptSaying(final PurgeReport report) {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2018", State.COMPLETE))));
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
