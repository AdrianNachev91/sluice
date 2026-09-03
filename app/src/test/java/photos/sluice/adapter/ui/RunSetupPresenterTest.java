package photos.sluice.adapter.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.adapter.ui.RunLauncherView.StartAction;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunSetupPresenterTest {

    private static final SpendEstimate NOTHING = new SpendEstimate(0, 0, true, false, false);

    private final Pipeline pipeline = mock(Pipeline.class);

    private final AtomicBoolean jobIsRunning = new AtomicBoolean();

    private final RunSetupPresenter presenter = this.launcher();

    @BeforeEach
    void aStagedLibraryAndAnInboxWithSomethingInIt() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10), new MonthRow(7, 30, 0),
                        new MonthRow(11, 30, 0))),
                // 2018 shares June with 2019 so that a month marked on the wrong year's rows shows
                // up as a marked row rather than as nothing.
                new YearRow(2018, 50, 0, List.of(new MonthRow(1, 30, 0), new MonthRow(6, 20, 0)))), 0));
        when(this.pipeline.estimateFor(anyInt())).thenReturn(NOTHING);
        // A spending provider is the fixture, so every test below is about the figure rather than
        // about whether there is one at all. The tests that turn it off say so themselves.
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));
        this.presenter.refreshCounts();
    }

    @Test
    void aFieldThatNamesNothingForThisModeIsSaidToBeIdle() {
        this.choose(RunMode.SIFT, "2019");
        assertThat(this.presenter.view().scopeNamesTheRun()).isTrue();

        this.presenter.setMode(RunMode.SORT);

        assertThat(this.presenter.view().scopeNamesTheRun()).isFalse();
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019");
    }

    @Test
    void severalMonthsInTheGapAreNamedAsOnePhrase() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 40, 0, List.of(new MonthRow(1, 10, 0), new MonthRow(2, 10, 0),
                        new MonthRow(3, 10, 0), new MonthRow(4, 10, 0)))), 0));
        this.presenter.refreshCounts();

        this.choose(RunMode.MOVE_TO_LIBRARY, "2019 1,4");

        assertThat(this.presenter.view().scopeRefusal())
                .contains("would take February and March too");
    }

    @Test
    void aGapIsRefusedWithoutNamingMonthsWhileTheCountsAreStillBeingRead() {
        final RunSetupPresenter counting = this.launcher();
        counting.setMode(RunMode.MOVE_TO_LIBRARY);

        counting.setScope("2019 6,11");

        assertThat(counting.view().scopeRefusal())
                .contains("would take months you did not ask for.");
    }

    @Test
    void anEmptyScopeCannotStartASiftAndLeavesTheAskingToTheHint() {
        this.choose(RunMode.SIFT, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().scopeHint()).contains("click one below");
    }

    @Test
    void aBareMoveToTheLibraryAsksFirstAndNamesEveryYearAndFileGoing() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "");

        final RunSetupPresenter.Confirmation asked = this.presenter.confirmationNeeded();

        assertThat(asked).isNotNull();
        assertThat(asked.question()).contains("160 files").contains("2019 and 2018");
    }

    @Test
    void aMoveNarrowedToAYearNeedsNoQuestionAsked() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    @Test
    void everyOtherModeNeedsNoQuestionAskedEither() {
        this.choose(RunMode.SORT, "");

        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    @Test
    void siftingFromAFinishedSortsCardAsksFirstAndNamesTheFigure() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true, false));

        final RunSetupPresenter.Confirmation asked = this.askedBeforeSifting(2019);

        assertThat(asked.heading()).isEqualTo("Sift 2019?");
        assertThat(asked.question())
                .isEqualTo("This looks at 100 photos sorted for 2019: 6 from this run and 94 "
                        + "sorted earlier. That is about 150,000 tokens, and sifting spends from "
                        + "your provider account balance. Sluice will stop and ask if it goes "
                        + "far past that.");
        assertThat(asked.goAhead()).isEqualTo("Sift 2019");
    }

    @Test
    void theQuestionSplitsTheTimeframeIntoThisRunAndWhatWasThereBefore() {
        assertThat(this.askedBeforeSifting(2019).question())
                .contains("100 photos sorted for 2019: 6 from this run and 94 sorted earlier");
    }

    @Test
    void aTimeframeHoldingOnlyWhatThisRunSortedSaysSo() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 6, 0, List.of(new MonthRow(6, 6, 0)))), 0));
        this.presenter.refreshCounts();

        assertThat(this.askedBeforeSifting(2019).question())
                .contains("This looks at 6 photos sorted for 2019, all of them from this run.");
    }

    @Test
    void siftingFromACardStillSaysItSpendsWhereNoFigureCanBeGiven() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(0, 0, true, false, false));

        assertThat(this.askedBeforeSifting(2019).question())
                .endsWith("Sifting spends from your provider account balance.")
                .doesNotContain("tokens");
    }

    @Test
    void siftingFromACardStillAsksWhereTheProviderSpendsNothing() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        assertThat(this.askedBeforeSifting(2019).question())
                .contains("6 from this run and 94 sorted earlier", "costs you nothing through Sluice");
    }

    @Test
    void siftingFromACardStartsNothingBeforeTheCountsHaveLanded() {
        assertThat(this.launcher().siftNowNeeds(2019, 6))
                .isInstanceOf(RunSetupPresenter.SiftNow.Refuse.class);
    }

    @Test
    void siftingATimeframeHoldingOnlyVideosIsRefusedInTheLaunchersOwnWords() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2021, 0, 0, List.of(new MonthRow(6, 0, 0)))), 0));
        this.presenter.refreshCounts();

        assertThat(this.presenter.siftNowNeeds(2021, 0))
                .isEqualTo(new RunSetupPresenter.SiftNow.Refuse(new RunLauncherView.Message(
                        "No photos are sorted for 2021, so there is nothing to sift.", true)));
        this.choose(RunMode.SIFT, "2021");
        assertThat(this.presenter.view().scopeRefusal())
                .isEqualTo("No photos are sorted for 2021, so there is nothing to sift.");
    }

    @Test
    void somethingThatDoesNotStartWithAYearIsRefused() {
        this.choose(RunMode.SIFT, "last summer");

        assertThat(this.presenter.view().scopeRefusal()).contains("four-digit year");
    }

    @Test
    void aMonthOutsideTheCalendarIsRefused() {
        this.choose(RunMode.SIFT, "2019 13");

        assertThat(this.presenter.view().scopeRefusal()).contains("1 to 12");
    }

    @Test
    void aRunOfMonthsWrittenBackwardsIsRefused() {
        this.choose(RunMode.SIFT, "2019 8-6");

        assertThat(this.presenter.view().scopeRefusal()).contains("earlier one to the later");
    }

    @Test
    void aSiftIsRefusedForAYearHoldingNothing() {
        this.choose(RunMode.SIFT, "1998");

        assertThat(this.presenter.view().scopeRefusal()).contains("Nothing is sorted for 1998");
    }

    @Test
    void aSortRefusesNothingTypedBecauseItDoesNotReadTheField() {
        this.choose(RunMode.SORT, "1998");

        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aSiftIsSizedOnThePhotosStagedUnderTheYearItNames() {
        this.choose(RunMode.SIFT, "2019");

        this.presenter.view();

        verify(this.pipeline).estimateFor(100);
    }

    @Test
    void aSiftNarrowedToMonthsIsSizedOnThoseMonthsAlone() {
        this.choose(RunMode.SIFT, "2019 6-7");

        this.presenter.view();

        verify(this.pipeline).estimateFor(70);
    }

    @Test
    void aSortIsNeverSizedForCostBecauseItCallsNoModel() {
        this.choose(RunMode.SORT, "2019");

        assertThat(this.presenter.view().cost()).isNull();
        verify(this.pipeline, never()).estimateFor(anyInt());
    }

    @Test
    void aProviderThatSpendsNothingSaysSoRatherThanLeavingTheCostSpaceEmpty() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.SIFT, "2019");

        assertThat(this.freeCost().headline())
                .isEqualTo("Sifting costs you nothing through Sluice.");
        verify(this.pipeline, never()).estimateFor(anyInt());
    }

    @Test
    void aProviderThatSpendsNothingSaysSoBeforeAnyScopeIsChosen() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.SIFT, "");

        assertThat(this.presenter.view().cost()).isInstanceOf(RunLauncherView.Cost.Free.class);
    }

    @Test
    void aModeThatReachesNoProviderSaysNothingAboutMoneyEitherWay() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.view().cost()).isNull();
    }

    @Test
    void theFigureIsRoundedRatherThanClaimingTheLastToken() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().figure()).isEqualTo("About 150,000 tokens");
    }

    @Test
    void anInstallWithNoFinishedSiftBehindItSaysTheFigureIsAGuess() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, false, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().disclaimer()).contains("starting guess");
    }

    // Compared against what the from-history state actually renders, rather than against a phrase
    // copied out of it. A reword there cannot quietly disarm this.
    @Test
    void aGuessedFigureIsNotAlsoCalledAnAverageOfYourOwnSifts() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true, false));
        this.choose(RunMode.SIFT, "2018");
        final String fromHistory = this.estimatedCost().disclaimer();
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, false, true));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().disclaimer())
                .isNotEqualTo(fromHistory)
                .contains("stop and ask whether to continue");
        assertThat(requireNonNull(this.estimatedCost().warning()).problem())
                .contains("The record of what your past sifts cost is broken")
                .contains("a starting guess rather than an average of your own sifts");
    }

    @Test
    void onlyAnUnreadableRecordIsOfferedAWayToStartAFreshOne() {
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(148_231, 6_402, false, false, true));
        this.choose(RunMode.SIFT, "2019");

        assertThat(requireNonNull(this.estimatedCost().warning()).repair().label())
                .isEqualTo("Start a fresh record");
    }

    @Test
    void anInstallWithNothingWrongWithItsRecordIsOfferedNothingToPutRight() {
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(148_231, 6_402, false, false, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().warning()).isNull();
    }

    @Test
    void theQuestionBeforeStartingAFreshRecordNamesWhereTheOldOneGoes() {
        final Path archives = Path.of("logs", "archives");
        when(this.pipeline.archivesFolder()).thenReturn(archives);
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(148_231, 6_402, false, false, true));
        this.choose(RunMode.SIFT, "2019");

        final Confirmation asked = requireNonNull(this.estimatedCost().warning()).repair().confirm();

        assertThat(asked.question()).contains(archives.toString())
                .contains("does not come back");
        assertThat(asked.goAheadLeads()).isFalse();
    }

    @Test
    void startingAFreshRecordFilesTheOldOneAwayAndSaysSo() {
        when(this.pipeline.setAsideUnreadableSpendLedger()).thenReturn(Path.of("logs", "archives", "old.csv"));

        this.presenter.startAFreshSpendRecord();

        verify(this.pipeline).setAsideUnreadableSpendLedger();
        assertThat(requireNonNull(this.presenter.view().message()).text())
                .isEqualTo("The old record is discarded. Your next finished sift starts the new one.");
    }

    @Test
    void aRecordThatReadsAfterAllIsLeftWhereItIs() {
        when(this.pipeline.setAsideUnreadableSpendLedger()).thenReturn(null);

        this.presenter.startAFreshSpendRecord();

        assertThat(requireNonNull(this.presenter.view().message()).text())
                .isEqualTo("The record is now healthy, so it has been left where it is.");
    }

    @Test
    void aRefusedRepairSaysWhyRatherThanFailingSilently() {
        when(this.pipeline.setAsideUnreadableSpendLedger())
                .thenThrow(new JobInProgressException("Something else is running."));

        this.presenter.startAFreshSpendRecord();

        final RunLauncherView.Message said = requireNonNull(this.presenter.view().message());
        assertThat(said.text()).isEqualTo("Something else is running.");
        assertThat(said.refused()).isTrue();
    }

    @Test
    void theFigureIsWorkedOutAgainOnceTheRecordHasBeenFiledAway() {
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(148_231, 6_402, false, false, true));
        this.choose(RunMode.SIFT, "2019");
        assertThat(this.estimatedCost().warning()).isNotNull();
        when(this.pipeline.setAsideUnreadableSpendLedger()).thenReturn(Path.of("logs", "archives", "old.csv"));
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(148_231, 6_402, false, false, false));

        this.presenter.startAFreshSpendRecord();

        assertThat(this.estimatedCost().warning()).isNull();
    }

    @Test
    void aFigureRestingOnFinishedSiftsSaysItIsAnAverageOfThem() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().disclaimer())
                .contains("average of what sifts like this one have cost")
                .contains("stop and ask whether to continue");
    }

    // The second half compares against what the no-history state actually renders rather than
    // against a phrase copied out of it. Reword that line and this still holds; delete the branch
    // and it fails, which is the point.
    @Test
    void aBrokenRecordOfPastSiftsSaysSoRatherThanClaimingThereIsNone() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, false, true));
        this.choose(RunMode.SIFT, "2019");
        final String broken = requireNonNull(this.estimatedCost().warning()).problem();
        final String brokenDisclaimer = this.estimatedCost().disclaimer();
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, false, false));
        this.choose(RunMode.SIFT, "2018");

        assertThat(broken).contains("The record of what your past sifts cost is broken");
        assertThat(brokenDisclaimer).isNotEqualTo(this.estimatedCost().disclaimer());
    }

    @Test
    void theDisclaimerNamesTheSiftRatherThanTheRunWhereItMeansTheOneAboutToStart() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.estimatedCost().disclaimer())
                .contains("if the sift goes far past the estimate");
    }

    @Test
    void oneScopeIsSizedOnceHoweverOftenTheScreenAsksWhatItShows() {
        this.choose(RunMode.SIFT, "2019");

        this.presenter.view();
        this.presenter.view();

        verify(this.pipeline, times(1)).estimateFor(100);
    }

    @Test
    void readingTheFoldersAgainRetiresTheSizeHeldForThatScope() {
        this.choose(RunMode.SIFT, "2019");
        this.presenter.view();

        this.presenter.refreshCounts();
        this.presenter.view();

        verify(this.pipeline, times(2)).estimateFor(100);
    }

    // A read that skipped the retire would hand back a figure averaged from the old ledger.
    @Test
    void aReadNobodyAskedForRetiresTheHeldSizeToo() {
        this.choose(RunMode.SIFT, "2019");
        this.presenter.view();

        this.presenter.refreshCountsUnprompted();
        this.presenter.view();

        verify(this.pipeline, times(2)).estimateFor(100);
    }

    @Test
    void aSizeHeldFromBeforeAReadIsRetiredAsThatReadStartsRatherThanAsItLands() throws Exception {
        this.choose(RunMode.SIFT, "2019");
        this.presenter.view();
        final var walking = new CountDownLatch(1);
        final var letItFinish = new CountDownLatch(1);
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            walking.countDown();
            assertThat(letItFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return new InboxTally(300, 1_000_000L);
        });

        final Thread read = Thread.ofVirtual().start(this.presenter::refreshCounts);
        assertThat(walking.await(5, TimeUnit.SECONDS)).isTrue();
        this.presenter.view();

        verify(this.pipeline, times(2)).estimateFor(100);
        letItFinish.countDown();
        read.join(5_000);
    }

    @Test
    void nothingStagedKillsStartForAMoveAndSaysNothingUnderTheField() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        this.presenter.refreshCounts();
        this.choose(RunMode.MOVE_TO_LIBRARY, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().nothingStaged()).contains("Nothing is sorted yet");
        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    // A year holding videos alone passes the staged check, and there is still nothing for a vision
    // provider to look at. Left to the cost line, it would render the same as a free provider.
    @Test
    void aSiftIsRefusedForAYearHoldingNoPhotos() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 0, 12, List.of())), 0));
        this.presenter.refreshCounts();
        this.choose(RunMode.SIFT, "2020");

        assertThat(this.presenter.view().scopeRefusal()).contains("No photos are sorted for 2020");
    }

    @Test
    void aSiftIsRefusedForMonthsHoldingNoPhotos() {
        this.choose(RunMode.SIFT, "2019 1-2");

        assertThat(this.presenter.view().scopeRefusal())
                .contains("No photos are sorted for the chosen months of 2019");
    }

    // The screen is rebuilt whenever somebody comes back to it, over a presenter that outlives it.
    // A field the view could not fill would open blank over a scope still in force, and Start would
    // run against something nobody could see.
    @Test
    void theScopeInForceIsCarriedToWhateverScreenIsDrawnNext() {
        this.choose(RunMode.SIFT, "2019 6-7");

        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 6-7");
    }

    @Test
    void theButtonIsDeadWhileAnotherJobIsAlreadyRunning() {
        when(this.pipeline.isBusy()).thenReturn(true);
        this.choose(RunMode.SORT, "2019");

        assertThat(this.presenter.view().canStart()).isFalse();
    }

    @Test
    void theButtonIsDeadUntilTheCountsHaveLanded() {
        final RunSetupPresenter counting = this.launcher();

        assertThat(counting.view().canStart()).isFalse();
        assertThat(counting.view().inbox().headline()).isEqualTo("Counting what is waiting...");
    }

    // Asserted from inside the read, which is the only moment the state under test exists. An
    // AssertionError is an Error, so refreshCounts's catch of RuntimeException lets it out.
    @Test
    void aReadInFlightOverCountsAlreadyOnTheCardsLeavesThemShowingRatherThanBlankingThem() {
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(this.presenter.view().inbox().headline()).isEqualTo("300 photos and videos");
            return new InboxTally(7, 20L);
        });

        this.presenter.refreshCounts();

        assertThat(this.presenter.view().inbox().headline()).isEqualTo("7 photos and videos");
    }

    @Test
    void aReadInFlightLeavesTheScreenSayingWhatItLastKnew() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        this.presenter.refreshCounts();
        this.choose(RunMode.SIFT, "2019");

        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(this.presenter.view().nothingStaged()).contains("Nothing is sorted yet");
            assertThat(this.presenter.view().cost()).isNull();
            return new InboxTally(1, 1L);
        });

        this.presenter.refreshCounts();
    }

    @Test
    void nothingCanBeStartedAgainstCountsAReadIsStillReplacing() {
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(this.presenter.view().canStart()).isFalse();
            return new InboxTally(300, 1_000_000L);
        });
        this.choose(RunMode.SORT, "2019");

        this.presenter.refreshCounts();

        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aMoveIsOfferedUntilTheReadThatWouldRefuseItHasLanded() {
        final RunSetupPresenter opening = this.launcher();
        opening.setMode(RunMode.MOVE_TO_LIBRARY);

        assertThat(opening.confirmationNeeded()).isNull();

        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        opening.refreshCounts();

        assertThat(opening.view().canStart()).isFalse();
    }

    @Test
    void aYearRowStaysMarkedEvenWhereTheModeCannotTakeTheYearItNames() {
        // A year of videos alone: the rows are live, and a sift over it still has nothing to look
        // at. An empty Inbox would refuse it too, in a mode whose rows do not mark at all.
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 0, 4, List.of(new MonthRow(6, 0, 4)))), 0));
        this.presenter.refreshCounts();
        this.aModeTheRowsScope();

        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().years()).filteredOn(YearChoice::chosen)
                .extracting(YearChoice::year).containsExactly(2019);
    }

    @Test
    void everyModeSaysWhatItDoesRatherThanOnlyNamingItself() {
        final List<String> said = new ArrayList<>();
        for (final RunMode mode : this.modesInTheRow()) {
            this.presenter.setMode(mode);
            said.add(this.presenter.view().modeHint());
        }

        assertThat(said).containsExactly(
                "Reads the dates on what is in your Inbox and moves it into Sorted, by year and "
                        + "month. Takes the oldest year in your Inbox.",
                "Looks at your sorted photos and moves anything it does not keep out of Sorted.",
                "Moves what is in Sorted into your library. That is the photos a sift left alone, "
                        + "plus any it has not seen.");
    }

    @Test
    void theRowOffersTheThreeModesStartedFromAScope() {
        assertThat(this.modesInTheRow()).containsExactly(RunMode.SORT, RunMode.SIFT,
                RunMode.MOVE_TO_LIBRARY);
    }

    @Test
    void theInboxCardOffersToBringPhotosIn() {
        assertThat(this.presenter.view().inbox().importLabel()).isEqualTo("Import a folder...");
        assertThat(this.presenter.view().inbox().importHint())
                .isEqualTo("Or drop folders and files anywhere on this screen.");
        assertThat(this.presenter.view().inbox().canImport()).isTrue();
    }

    @Test
    void nothingCanBeBroughtInWhileAJobIsRunning() {
        this.jobIsRunning.set(true);

        assertThat(this.presenter.view().inbox().canImport()).isFalse();
    }

    @Test
    void anInboxThatCouldNotBeReadStillOffersToBringPhotosIn() {
        this.anUnreadableInbox();

        assertThat(this.presenter.view().inbox().canImport()).isTrue();
    }

    @Test
    void oneChosenFolderIsNamedInTheQuestionAskedAboutIt() {
        assertThat(this.presenter.importQuestion(List.of(Path.of("cards", "DCIM"))).heading())
                .isEqualTo("Import DCIM?");
    }

    @Test
    void severalChosenAtOnceAreNotNamedOneByOne() {
        assertThat(this.presenter.importQuestion(
                List.of(Path.of("DCIM"), Path.of("phone"))).heading())
                .isEqualTo("Import these?");
    }

    @Test
    void theQuestionAsksWhichOfTheTwoWaysIn() {
        final RunSetupPresenter.ImportQuestion asked =
                this.presenter.importQuestion(List.of(Path.of("DCIM")));

        assertThat(asked.question()).isEqualTo("Would you like to copy or move your files?");
    }

    @Test
    void theButtonNamesWhicheverModeItWouldRun() {
        final List<String> labels = new ArrayList<>();
        for (final RunMode mode : this.modesInTheRow()) {
            this.presenter.setMode(mode);
            labels.add(this.presenter.view().startLabel());
        }

        assertThat(labels).containsExactly("Run Sort", "Run Sift", "Run Move to library");
    }

    @Test
    void theInboxCardSaysHowMuchIsWaitingAndWhatItComesTo() {
        final RunLauncherView.InboxCard card = this.presenter.view().inbox();

        assertThat(card.headline()).isEqualTo("300 photos and videos");
        assertThat(card.detail()).isEqualTo("976.6 KB");
    }

    @Test
    void anEmptyInboxSaysSoRatherThanShowingAZero() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(0, 0));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().inbox().headline()).isEqualTo("Nothing to sort.");
    }

    // The Inbox card is what says why, in the tone a healthy screen keeps. A line under the field
    // would say it a second time, in the one a screen keeps for something being wrong.
    @Test
    void anEmptyInboxKillsStartForASortAndSaysNothingUnderTheField() {
        this.anEmptyInbox();
        this.choose(RunMode.SORT, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
    }

    @Test
    void namingAYearDoesNotGetRoundAnEmptyInbox() {
        this.anEmptyInbox();
        this.choose(RunMode.SORT, "2019");

        assertThat(this.presenter.view().canStart()).isFalse();
    }

    @Test
    void aMoveNarrowedToAYearStillRunsAfterAFailedReadAndLeavesTheRefusalToTheFacade() {
        this.anUnreadableInbox();
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.view().canStart()).isTrue();
        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    @Test
    void anInboxThatCouldNotBeReadIsNotTakenForAnEmptyOne() {
        this.anUnreadableInbox();
        this.choose(RunMode.SORT, "");

        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void countsThatCannotBeReadAreReportedOnTheCardRatherThanThrown() {
        this.anUnreadableInbox();

        assertThat(this.presenter.view().inbox().headline()).contains("could not read");
        assertThat(this.presenter.view().years()).isEmpty();
    }

    @Test
    void nothingStagedIsSaidOnlyOnceTheCountsHaveLanded() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().nothingStaged()).contains("Sort your Inbox first");
    }

    @Test
    void aStagedYearSaysWhatItHoldsWithPhotosAndVideosApart() {
        assertThat(this.presenter.view().years())
                .extracting(YearChoice::label, YearChoice::counts)
                .containsExactly(tuple("2019", "100 photos and 10 videos"), tuple("2018", "50 photos"));
    }

    @Test
    void clickingAYearRowTypesThatYearIntoTheScopeField() {
        this.presenter.setMode(RunMode.SIFT);

        this.presenter.pressYear(2018);

        assertThat(this.presenter.view().scopeText()).isEqualTo("2018");
        assertThat(this.presenter.view().years()).filteredOn(YearChoice::chosen)
                .extracting(YearChoice::year).containsExactly(2018);
    }

    @Test
    void onlyTheChosenYearListsItsMonths() {
        this.aModeTheRowsScope();
        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::label)
                .containsExactly("June", "July", "November");
    }

    @Test
    void aMonthHoldingNothingAtAllGetsNoRowToClick() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 5, 0, List.of(new MonthRow(3, 5, 0), new MonthRow(4, 0, 0)))), 0));
        this.presenter.refreshCounts();
        this.presenter.pressYear(2020);

        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::month).containsExactly(3);
    }

    @Test
    void aMonthHoldingOnlyVideoGetsARowSayingSo() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 5, 2, List.of(new MonthRow(3, 5, 0), new MonthRow(8, 0, 2)))), 0));
        this.presenter.refreshCounts();
        this.presenter.pressYear(2020);

        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::month, RunLauncherView.MonthChoice::counts)
                .containsExactly(tuple(3, "5 photos"), tuple(8, "2 videos"));
    }

    @Test
    void aMonthRowCountsTheVideosAMoveWouldTakeAlongWithThePhotos() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 5, 3, List.of(new MonthRow(3, 5, 3)))), 0));
        this.presenter.refreshCounts();
        this.presenter.pressYear(2020);

        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::counts)
                .containsExactly("5 photos and 3 videos");
    }

    @Test
    void monthsAddUpRatherThanReplacingOneAnother() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 7);
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 7");

        this.presenter.pressMonth(2019, 11);
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 7,11");
        assertThat(this.marked()).containsExactly(7, 11);

        this.presenter.pressMonth(2019, 6);
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 6,7,11");
    }

    @Test
    void pressingAMonthAlreadyCoveredTakesItBackOut() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 7);
        this.presenter.pressMonth(2019, 11);

        this.presenter.pressMonth(2019, 7);

        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 11");
        assertThat(this.marked()).containsExactly(11);
    }

    @Test
    void takingTheLastMonthOutLeavesTheWholeYearRatherThanNothing() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 6);

        this.presenter.pressMonth(2019, 6);

        assertThat(this.presenter.view().scopeText()).isEqualTo("2019");
        assertThat(this.marked()).isEmpty();
        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
    }

    @Test
    void pressingTheYearAlreadyChosenFoldsItsMonthsAwayAndBringsThemBack() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 6);
        assertThat(this.marked()).containsExactly(6);

        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::monthsShown);
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 6");

        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
        assertThat(this.marked()).containsExactly(6);
    }

    @Test
    void typingBringsBackMonthsAYearPressHadFoldedAway() {
        this.aModeTheRowsScope();
        this.presenter.pressYear(2019);
        this.presenter.pressYear(2019);
        assertThat(this.presenter.view().years()).noneMatch(YearChoice::monthsShown);

        this.presenter.setScope("2019 7");

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
    }

    @Test
    void choosingAnotherYearLeavesNoMonthMarkedAnywhere() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 6);

        this.presenter.pressYear(2018);

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2018);
        assertThat(this.marked()).isEmpty();
    }

    @Test
    void aMonthIsMarkedOnlyUnderTheYearTheScopeNames() {
        this.choose(RunMode.SIFT, "2019 6");

        assertThat(this.presenter.view().years()).filteredOn(year -> year.year() == 2018)
                .flatExtracting(YearChoice::months)
                .noneMatch(RunLauncherView.MonthChoice::chosen);
    }

    @Test
    void theFieldAndTheRowsScopeTheRunOnlyForTheModesThatReadSorted() {
        assertThat(this.viewOf(RunMode.SIFT).scopeNamesTheRun()).isTrue();
        assertThat(this.viewOf(RunMode.MOVE_TO_LIBRARY).scopeNamesTheRun()).isTrue();
        assertThat(this.viewOf(RunMode.SORT).scopeNamesTheRun()).isFalse();
        assertThat(this.viewOf(RunMode.RESCUE).scopeNamesTheRun()).isFalse();
    }

    @Test
    void noRowIsMarkedWhileTheRunReadsTheInbox() {
        this.choose(RunMode.SORT, "2019 6");

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::chosen);
        assertThat(this.presenter.view().years()).noneMatch(YearChoice::monthsShown);
        assertThat(this.marked()).isEmpty();
    }

    @Test
    void aMonthChosenBeforeAModeThatIgnoresTheRowsIsStillMarkedOnTheWayBack() {
        this.choose(RunMode.SIFT, "2019 6");
        this.presenter.setMode(RunMode.SORT);

        this.presenter.setMode(RunMode.SIFT);

        assertThat(this.marked()).containsExactly(6);
    }

    @Test
    void noRowIsMarkedWhileTheScopeNamesNoYear() {
        this.choose(RunMode.SIFT, "2019");
        assertThat(this.presenter.view().years()).anyMatch(YearChoice::chosen);

        this.presenter.setScope("");

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::chosen);
    }

    @Test
    void theModeNowChosenIsTheOnlyOneMarked() {
        this.presenter.setMode(RunMode.MOVE_TO_LIBRARY);

        assertThat(this.presenter.view().modes()).filteredOn(RunLauncherView.ModeChoice::chosen)
                .extracting(RunLauncherView.ModeChoice::mode).containsExactly(RunMode.MOVE_TO_LIBRARY);
    }

    @Test
    void everyModeGetsAButtonAndTheyReadInTheOrderTheyAreOffered() {
        assertThat(this.presenter.view().modes()).extracting(RunLauncherView.ModeChoice::label)
                .containsExactly("Sort", "Sift", "Move to library");
    }

    @Test
    void theRowsOneLinkLeadsToReviewAndFollowsSifting() {
        assertThat(this.presenter.view().rowLink().label()).isEqualTo("Review");
        assertThat(this.presenter.view().rowLink().after()).isEqualTo(RunMode.SIFT);
    }

    @Test
    void withNothingWaitingWithoutADateTheRowForItIsAbsentRatherThanReadingZero() {
        assertThat(this.presenter.view().undated()).isNull();
    }

    @Test
    void whatIsWaitingWithoutADateGetsItsOwnRowBesideTheYears() {
        this.somethingUndated(7);

        assertThat(requireNonNull(this.presenter.view().undated()).label()).isEqualTo("Unsorted");
        assertThat(requireNonNull(this.presenter.view().undated()).counts()).isEqualTo("7 photos and videos");
        assertThat(this.presenter.view().years()).extracting(YearChoice::year).containsExactly(2019, 2018);
    }

    @Test
    void onlyAMoveToTheLibraryCanPressTheRowForWhatHasNoDate() {
        this.somethingUndated(7);

        this.presenter.setMode(RunMode.MOVE_TO_LIBRARY);
        assertThat(requireNonNull(this.presenter.view().undated()).pressable()).isTrue();

        this.presenter.setMode(RunMode.SIFT);
        assertThat(requireNonNull(this.presenter.view().undated()).pressable()).isFalse();
    }

    @Test
    void pressingThatRowScopesTheRunToItAndPressingItAgainClearsTheField() {
        this.somethingUndated(7);
        this.presenter.setMode(RunMode.MOVE_TO_LIBRARY);

        this.presenter.pressUndated();
        assertThat(this.presenter.view().scopeText()).isEqualTo("Unsorted");
        assertThat(requireNonNull(this.presenter.view().undated()).chosen()).isTrue();

        this.presenter.pressUndated();
        assertThat(this.presenter.view().scopeText()).isEmpty();
    }

    @Test
    void aMoveNarrowedToWhatHasNoDateAsksTheEngineForThatFolderAlone() {
        this.somethingUndated(7);

        this.choose(RunMode.MOVE_TO_LIBRARY, "unsorted");

        assertThat(this.presenter.view().canStart()).isTrue();
        assertThat(RunScope.asCommit(this.presenter.scope())).isEqualTo(new CommitScope.Undated());
    }

    @Test
    void theWordForWhatHasNoDateIsTakenWhateverItsCase() {
        this.somethingUndated(7);

        this.choose(RunMode.MOVE_TO_LIBRARY, "UNSORTED");

        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aSiftIsRefusedThatWordAndToldWhatASiftLooksAt() {
        this.somethingUndated(7);

        this.choose(RunMode.SIFT, "unsorted");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal())
                .isEqualTo("A sift looks at photos filed under a year, and these have no date.");
    }

    // The undated row comes from a tree the year rows never see, so the card can hold one while
    // holding no years at all.
    @Test
    void aCardHoldingOnlyTheUndatedRowDoesNotSayNothingIsSorted() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 7));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().undated()).isNotNull();
        assertThat(this.presenter.view().nothingStaged()).isNull();
    }

    @Test
    void aCardHoldingNeitherSaysNothingIsSorted() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().nothingStaged())
                .isEqualTo("Nothing is sorted yet. Sort your Inbox first, and the years will show up here.");
    }

    @Test
    void aScopeNothingIsLeftInIsDroppedRatherThanDrawnWithItsOwnRefusal() {
        this.choose(RunMode.SIFT, "1998");

        this.presenter.forgetAScopeNothingIsLeftIn();

        assertThat(this.presenter.view().scopeText()).isEmpty();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
    }

    @Test
    void aScopeStillWorthRunningIsKept() {
        this.choose(RunMode.SIFT, "2019");

        this.presenter.forgetAScopeNothingIsLeftIn();

        assertThat(this.presenter.view().scopeText()).isEqualTo("2019");
    }

    @Test
    void theWordIsRefusedWhereNothingIsWaitingWithoutADate() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "unsorted");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isEqualTo("Nothing is waiting without a date.");
    }

    @Test
    void aTimeframeHoldingAnUnfinishedSiftIsMarkedAndTheOthersAreNot() {
        this.anUnfinishedSiftOf("2019", State.WAITING);

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::unfinishedSift)
                .extracting(YearChoice::year).containsExactly(2019);
    }

    @Test
    void onlyTheMonthsAnUnfinishedSiftCoversAreMarked() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.aModeTheRowsScope();

        assertThat(this.monthsMarkedAsSifted(2019)).containsExactly(6);
    }

    @Test
    void aSiftOfAWholeYearMarksEveryMonthUnderIt() {
        this.anUnfinishedSiftOf("2019", State.WAITING);
        this.aModeTheRowsScope();

        assertThat(this.monthsMarkedAsSifted(2019)).containsExactly(6, 7, 11);
    }

    @Test
    void aSiftOfOneYearLeavesTheSameMonthOfAnotherYearUnmarked() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.aModeTheRowsScope();

        assertThat(this.monthsMarkedAsSifted(2018)).isEmpty();
    }

    @Test
    void theLegendAppearsOnlyWhileSomeTimeframeIsMarked() {
        assertThat(this.presenter.view().scopeLegend()).isNull();

        this.anUnfinishedSiftOf("2019", State.WAITING);

        assertThat(this.presenter.view().scopeLegend()).isNotNull();
    }

    @Test
    void aSiftOfATimeframeThatCanCarryOnTurnsTheButtonIntoContinue() {
        this.anUnfinishedSiftOf("2019", State.READY);
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.presenter.view().startLabel()).isEqualTo("Continue sifting");
        assertThat(this.presenter.view().startAction())
                .isEqualTo(new StartAction.ContinueRun(Path.of("logs", "sift-prep", "2019")));
        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aSiftOfATimeframeThatCannotCarryOnSendsTheReaderToTheRunsScreen() {
        this.anUnfinishedSiftOf("2019", State.BLOCKED);
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.presenter.view().startLabel()).isEqualTo("Open in Runs");
        assertThat(this.presenter.view().startAction()).isEqualTo(new StartAction.OpenRuns());
    }

    @Test
    void aTimeframeRunningAcrossAnUnfinishedSiftIsRefusedAndTheButtonGoesDead() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.presenter.view().scopeRefusal())
                .isEqualTo("2019 overlaps June 2019, which is a sift you have not finished. "
                        + "Finish or discard it in Runs, then you can sift this.");
        assertThat(this.presenter.view().canStart()).isFalse();
    }

    // The screen greys Start off a snapshot and the facade refuses off a fresh read. Two checks,
    // so a reader who gets past the first meets the same sentence at the second.
    @Test
    void theScreenAndTheFacadeWordTheSameOverlapIdentically() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.choose(RunMode.SIFT, "2019");

        final String refused = RunRefusals.plainly(new Pipeline.ScopeOverlapsException(
                new CullScope.Year(2019, null), List.of(aRun("2019-06", State.WAITING))));

        assertThat(refused).isEqualTo(this.presenter.view().scopeRefusal());
    }

    @Test
    void aMonthInsideAnUnfinishedWholeYearIsRefusedNamingTheMonth() {
        this.anUnfinishedSiftOf("2019", State.WAITING);
        this.choose(RunMode.SIFT, "2019 6");

        assertThat(this.presenter.view().scopeRefusal())
                .isEqualTo("June 2019 overlaps 2019, which is a sift you have not finished. "
                        + "Finish or discard it in Runs, then you can sift this.");
        assertThat(this.presenter.view().canStart()).isFalse();
    }

    @Test
    void aTimeframeAcrossSeveralUnfinishedSiftsNamesAllOfThem() {
        this.unfinishedSiftsOf("2019-06", "2019-08");
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.presenter.view().scopeRefusal())
                .contains("June 2019 and August 2019").contains("are sifts").contains("discard them");
    }

    @Test
    void aTimeframeNamingTheSameMonthsAsTheUnfinishedSiftIsContinuedRatherThanCalledAnOverlap() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.choose(RunMode.SIFT, "2019 6");

        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().startLabel()).isEqualTo("Continue sifting");
    }

    @Test
    void aTimeframeSharingNoMonthWithAnUnfinishedSiftStartsFresh() {
        this.anUnfinishedSiftOf("2019-06", State.WAITING);
        this.choose(RunMode.SIFT, "2019 7");

        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().startAction()).isEqualTo(new StartAction.StartFresh());
    }

    @Test
    void aRunScopedToACountOfFilesMarksNoTimeframeAndBlocksNothing() {
        this.anUnfinishedSiftOf("oldest-25", State.WAITING);
        this.choose(RunMode.SIFT, "2019");

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::unfinishedSift);
        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aRunsFolderThatCouldNotBeReadMarksNothing() {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(Path.of("p")));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::unfinishedSift);
        assertThat(this.presenter.view().scopeLegend()).isNull();
    }

    @Test
    void aModeOtherThanSiftIsNeverStoppedByAnUnfinishedSift() {
        this.anUnfinishedSiftOf("2019", State.WAITING);
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().startAction()).isEqualTo(new StartAction.StartFresh());
    }

    private RunSetupPresenter launcher() {
        return new RunSetupPresenter(this.pipeline, this.jobIsRunning::get, () -> { });
    }

    private RunSetupPresenter.Confirmation askedBeforeSifting(final int year) {
        assertThat(this.presenter.siftNowNeeds(year, 6))
                .isInstanceOf(RunSetupPresenter.SiftNow.Ask.class);
        return ((RunSetupPresenter.SiftNow.Ask) this.presenter.siftNowNeeds(year, 6)).question();
    }

    private RunLauncherView.Cost.Estimate estimatedCost() {
        return (RunLauncherView.Cost.Estimate) requireNonNull(this.presenter.view().cost());
    }

    private void anUnfinishedSiftOf(final String scope, final State state) {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(aRun(scope, state))));
        this.presenter.refreshCounts();
    }

    private void unfinishedSiftsOf(final String... scopes) {
        when(this.pipeline.cullRuns()).thenReturn(new CullRuns.Listed(
                Arrays.stream(scopes).map(scope -> aRun(scope, State.WAITING)).toList()));
        this.presenter.refreshCounts();
    }

    private static CullRunSummary aRun(final String scope, final State state) {
        return new CullRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(state, List.of()), null, Instant.now());
    }

    private List<Integer> monthsMarkedAsSifted(final int year) {
        return this.presenter.view().years().stream()
                .filter(row -> row.year() == year)
                .flatMap(row -> row.months().stream())
                .filter(MonthChoice::unfinishedSift)
                .map(MonthChoice::month)
                .toList();
    }

    private RunLauncherView.Cost.Free freeCost() {
        return (RunLauncherView.Cost.Free) requireNonNull(this.presenter.view().cost());
    }

    private List<Integer> marked() {
        return this.presenter.view().years().stream()
                .flatMap(year -> year.months().stream())
                .filter(RunLauncherView.MonthChoice::chosen)
                .map(RunLauncherView.MonthChoice::month)
                .toList();
    }

    private void anEmptyInbox() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(0, 0));
        this.presenter.refreshCounts();
    }

    private void anUnreadableInbox() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(
                        List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.presenter.refreshCounts();
    }

    private void somethingUndated(final int held) {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10))),
                new YearRow(2018, 50, 0, List.of(new MonthRow(1, 50, 0)))), held));
        this.presenter.refreshCounts();
    }

    private void choose(final RunMode mode, final String scope) {
        this.presenter.setMode(mode);
        this.presenter.setScope(scope);
    }

    private void aModeTheRowsScope() {
        this.presenter.setMode(RunMode.SIFT);
    }

    private RunLauncherView viewOf(final RunMode mode) {
        this.presenter.setMode(mode);
        return this.presenter.view();
    }

    // RunMode.values() would sweep in one the row draws no button for.
    private List<RunMode> modesInTheRow() {
        return this.presenter.view().modes().stream().map(RunLauncherView.ModeChoice::mode).toList();
    }
}
