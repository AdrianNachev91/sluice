package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLauncherPresenterTest {

    private static final SpendEstimate NOTHING = new SpendEstimate(0, 0, true, false);

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    // Comfortably past the 200ms a read is given before the screen says it is reading. The pair of
    // tests either side of it are a negative and its control, so this number is checked by them
    // rather than picked to feel safe.
    private static final long SLOWER_THAN_THE_WAIT = 400;

    private final Pipeline pipeline = mock(Pipeline.class);

    private final FxProgressPort progress = inlineProgress();

    private final RunLauncherPresenter presenter = new RunLauncherPresenter(this.pipeline, this.progress);

    // The handle behind whichever job a test left in flight, so a test about Cancel can ask what
    // reached it.
    private @Nullable JobHandle<Object> held;

    @BeforeEach
    void aStagedLibraryAndAnInboxWithSomethingInIt() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10), new MonthRow(7, 30, 0),
                        new MonthRow(11, 30, 0))),
                // 2018 shares June with 2019 so that a month marked on the wrong year's rows shows
                // up as a marked row rather than as nothing.
                new YearRow(2018, 50, 0, List.of(new MonthRow(1, 30, 0), new MonthRow(6, 20, 0))))));
        when(this.pipeline.estimateFor(anyInt())).thenReturn(NOTHING);
        // A spending provider is the fixture, so every test below is about the figure rather than
        // about whether there is one at all. The tests that turn it off say so themselves.
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        this.presenter.refreshCounts();
    }

    @Test
    void anEmptyScopeSortsWhicheverYearIsOldestInTheInbox() {
        this.chooseAndStart(RunMode.SORT, "");

        verify(this.pipeline).sort(new SortScope.OldestYear());
    }

    @Test
    void aSortTakesTheOldestYearWhateverTheFieldWasLeftHolding() {
        this.chooseAndStart(RunMode.SORT, "2019 6-8");

        verify(this.pipeline).sort(new SortScope.OldestYear());
    }

    @Test
    void aCurateTakesTheOldestYearTheSameWay() {
        this.chooseAndStart(RunMode.CURATE, "2019");

        verify(this.pipeline).curate(new SortScope.OldestYear());
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
    void everyMonthAYearHoldsIsTakenAsTheRunThatSpansThem() {
        this.chooseAndStart(RunMode.MOVE_TO_LIBRARY, "2019 6,7,11");

        verify(this.pipeline).commit(new CommitScope.Year(2019, new MonthRange(6, 11)));
    }

    @Test
    void aGapOverAMonthHoldingSomethingIsRefusedAndThatMonthIsNamed() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019 6,11");

        assertThat(this.presenter.view().scopeRefusal())
                .isEqualTo("Moving to library narrows to a run of months, not a list. Reading "
                        + "6,11 as 6-11 would take July too. Choose months that run together, like "
                        + "6-8, or none at all for the whole year.");
        this.presenter.start();
        verify(this.pipeline, never()).commit(any());
    }

    @Test
    void severalMonthsInTheGapAreNamedAsOnePhrase() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 40, 0, List.of(new MonthRow(1, 10, 0), new MonthRow(2, 10, 0),
                        new MonthRow(3, 10, 0), new MonthRow(4, 10, 0))))));
        this.presenter.refreshCounts();

        this.choose(RunMode.MOVE_TO_LIBRARY, "2019 1,4");

        assertThat(this.presenter.view().scopeRefusal())
                .contains("would take February and March too");
    }

    @Test
    void aGapIsRefusedWithoutNamingMonthsWhileTheCountsAreStillBeingRead() {
        final var counting = new RunLauncherPresenter(this.pipeline, inlineProgress());
        counting.setMode(RunMode.MOVE_TO_LIBRARY);

        counting.setScope("2019 6,11");

        assertThat(counting.view().scopeRefusal())
                .contains("would take months you did not ask for.");
    }

    @Test
    void aGappedMonthListIsTakenAsItIsForASift() {
        this.chooseAndStart(RunMode.SIFT, "2019 6,7,11");

        verify(this.pipeline).cull(new CullScope.Year(2019, List.of(6, 7, 11)));
    }

    @Test
    void aRunOfMonthsIsExpandedIntoEveryMonthItCoversForASift() {
        this.chooseAndStart(RunMode.SIFT, "2019 6-7");

        verify(this.pipeline).cull(new CullScope.Year(2019, List.of(6, 7)));
    }

    @Test
    void aSiftOverAWholeYearNamesNoMonthsAtAll() {
        this.chooseAndStart(RunMode.SIFT, "2019");

        verify(this.pipeline).cull(new CullScope.Year(2019, null));
    }

    @Test
    void anEmptyScopeCannotStartASiftAndLeavesTheAskingToTheHint() {
        this.choose(RunMode.SIFT, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
        assertThat(this.presenter.view().scopeHint()).contains("click one below");
    }

    @Test
    void choosingRescueRefusesNothing() {
        this.choose(RunMode.RESCUE, "2019");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeRefusal()).isNull();
    }

    @Test
    void anEmptyScopeMovesEverythingStagedToTheLibrary() {
        this.chooseAndStart(RunMode.MOVE_TO_LIBRARY, "");

        verify(this.pipeline).commit(new CommitScope.All());
    }

    @Test
    void aYearNarrowsAMoveToTheLibraryToThatYear() {
        this.chooseAndStart(RunMode.MOVE_TO_LIBRARY, "2019 6-7");

        verify(this.pipeline).commit(new CommitScope.Year(2019, new MonthRange(6, 7)));
    }

    @Test
    void aBareMoveToTheLibraryAsksFirstAndNamesEveryYearAndFileGoing() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "");

        final RunLauncherPresenter.Confirmation asked = this.presenter.confirmationNeeded();

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
    void aCurateAsksFirstBecauseItSpendsWithoutEverShowingAFigure() {
        this.choose(RunMode.CURATE, "");

        final RunLauncherPresenter.Confirmation asked = this.presenter.confirmationNeeded();

        assertThat(asked).isNotNull();
        assertThat(asked.question())
                .contains("the size of the sift cannot be estimated until sorting has finished");
        assertThat(this.presenter.view().cost()).isNull();
    }

    @Test
    void aCurateStillAsksWhereTheCountsHaveNotLanded() {
        final var opening = new RunLauncherPresenter(this.pipeline, inlineProgress());

        opening.setMode(RunMode.CURATE);

        assertThat(opening.confirmationNeeded()).isNotNull();
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
    void aCurateCarriesNoCostFigureAndSaysWhyInsteadOfGuessingOne() {
        this.choose(RunMode.CURATE, "2019");

        assertThat(this.presenter.view().cost()).isNull();
        assertThat(this.presenter.view().scopeHint()).contains("not known until the sorting is done");
        verify(this.pipeline, never()).estimateFor(anyInt());
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

        assertThat(freeOf(this.presenter).headline())
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
    void aCurateOnAProviderThatSpendsNothingIsNamedAsACurateRatherThanASift() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.CURATE, "");

        assertThat(freeOf(this.presenter).headline())
                .isEqualTo("Curating costs you nothing through Sluice.");
    }

    @Test
    void aModeThatReachesNoProviderSaysNothingAboutMoneyEitherWay() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.view().cost()).isNull();
    }

    @Test
    void aCurateDoesNotAskAboutMoneyWhereTheProviderSpendsNone() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);

        this.choose(RunMode.CURATE, "");

        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    @Test
    void theFigureIsRoundedRatherThanClaimingTheLastToken() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true));
        this.choose(RunMode.SIFT, "2019");

        assertThat(estimateOf(this.presenter).figure()).isEqualTo("About 150,000 tokens");
    }

    @Test
    void anInstallWithNoFinishedSiftBehindItSaysTheFigureIsAGuess() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, false));
        this.choose(RunMode.SIFT, "2019");

        assertThat(estimateOf(this.presenter).withoutHistory()).contains("starting guess");
    }

    @Test
    void anInstallWithRunsBehindItAddsNoSuchLine() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true));
        this.choose(RunMode.SIFT, "2019");

        assertThat(estimateOf(this.presenter).withoutHistory()).isNull();
    }

    @Test
    void theDisclaimerNamesTheSiftRatherThanTheRunWhereItMeansTheOneAboutToStart() {
        when(this.pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true));
        this.choose(RunMode.SIFT, "2019");

        assertThat(estimateOf(this.presenter).disclaimer())
                .contains("if the sift goes far past the estimate");
    }

    @Test
    void aRefusedStartIsReportedInTheWordsTheRefusalItselfUsed() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new JobInProgressException("Sluice is already running a job.")).when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text()).isEqualTo("Sluice is already running a job.");
        assertThat(messageOf(this.presenter).refused()).isTrue();
    }

    @Test
    void aStartRefusedBecauseAFolderRootWentBadReportsWhichFolderRatherThanFailing() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).refused()).isTrue();
        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void aRunThatFinishesSaysSoAndOffersTheButtonAgain() {
        this.choose(RunMode.SORT, "2019");
        this.pipelineStarts();

        this.presenter.start();

        assertThat(this.finishedView().heading()).isEqualTo("Sorting finished.");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.FINISHED);
        this.presenter.dismissResult();
        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFailureWithNoWordsOfItsOwnIsQuotedVerbatimSoItCanBeCopiedIntoABugReport() {
        this.choose(RunMode.SORT, "2019");
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("Malformed hash index line: 7")));
        when(this.pipeline.sort(any())).thenReturn(retyped(handle));

        this.presenter.start();

        assertThat(requireNonNull(this.finishedView().detail()))
                .contains("Report this as a bug in Sluice")
                .contains("Malformed hash index line: 7");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.FAILED);
    }

    @Test
    void aStartRefusedByAFolderRootNamesThatFolderTheWayTheRestOfTheAppNamesIt() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .contains(PathRoleLabels.of(PathRole.INBOX))
                .doesNotContain("sluice.paths");
    }

    @Test
    void theModeButtonsGoDeadWhileAJobIsInFlightAndComeBackAfterIt() {
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();

        this.presenter.start();
        assertThat(this.presenter.view().modes()).noneMatch(ModeChoice::pressable);

        sorting.complete(new Object());
        assertThat(this.presenter.view().modes()).allMatch(ModeChoice::pressable);
    }

    @Test
    void anOutcomeNamesTheModeItsRunWasStartedInRatherThanWhicheverIsChosenWhenItEnds() {
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();
        this.presenter.start();

        this.presenter.setMode(RunMode.SIFT);
        sorting.complete(new Object());

        assertThat(this.finishedView().heading()).isEqualTo("Sorting finished.");
    }

    @Test
    void aFinishedRunGoesBackToTheFoldersRatherThanTrustingTheCountsItStartedWith() {
        final var reread = new AtomicInteger();
        this.presenter.setRecount(reread::incrementAndGet);
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();
        this.presenter.start();

        assertThat(reread).hasValue(0);
        sorting.complete(new Object());

        assertThat(reread).hasValue(1);
    }

    @Test
    void aFinishedRunDrawsItselfOnWhicheverScreenIsUpWhenItEnds() {
        final List<String> drawn = new ArrayList<>();
        this.presenter.setRepaint(() -> drawn.add("the screen that pressed Start"));
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();
        this.presenter.start();

        this.presenter.setRepaint(() -> drawn.add("the screen that replaced it"));
        sorting.complete(new Object());

        assertThat(drawn).containsExactly("the screen that replaced it");
    }

    @Test
    void aStartRefusedBecauseSomethingElseIsRunningSaysSoRatherThanDoingNothing() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");
        when(this.pipeline.isBusy()).thenReturn(true);

        this.presenter.start();

        assertThat(messageOf(this.presenter).text()).contains("one thing at a time");
        assertThat(messageOf(this.presenter).refused()).isTrue();
        verify(this.pipeline, never()).commit(any());
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

    @Test
    void aPressLandingBeforeTheReadHasFinishedIsToldWhyNothingStarted() {
        final var counting = new RunLauncherPresenter(this.pipeline, inlineProgress());
        counting.setMode(RunMode.SORT);
        assertThat(counting.view().canStart()).isFalse();

        counting.start();

        assertThat(messageOf(counting).text()).contains("still reading your folders");
        verify(this.pipeline, never()).sort(any());
    }

    @Test
    void aReadThatFinishesAtOnceNeverPutsTheCountingStateOnTheScreen() throws Exception {
        final var repaints = new AtomicInteger();
        this.presenter.setRepaint(repaints::incrementAndGet);

        this.presenter.refreshCounts();

        Thread.sleep(SLOWER_THAN_THE_WAIT);
        assertThat(repaints.get()).isZero();
    }

    @Test
    void aReadStillGoingAfterTheWaitPutsItThereAndSaysSo() throws Exception {
        final var held = new CountDownLatch(1);
        final List<Boolean> liveWhenDrawn = new ArrayList<>();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            held.await();
            return new InboxTally(12, 4_300);
        });
        final var slow = new RunLauncherPresenter(this.pipeline, inlineProgress());
        slow.setMode(RunMode.SORT);
        slow.setRepaint(() -> liveWhenDrawn.add(slow.view().canStart()));

        final Thread walking = Thread.ofVirtual().start(slow::refreshCounts);
        Thread.sleep(SLOWER_THAN_THE_WAIT);

        // Drawn once, and the button was dead when it was. A live one would take a press and do
        // nothing for as long as the walk lasts.
        assertThat(liveWhenDrawn).containsExactly(false);
        held.countDown();
        walking.join();
    }

    @Test
    void aRepaintTheScreenRefusesLeavesTheNextReadAbleToRun() {
        // A closing window refuses the handover to the screen with this. A read in flight when it
        // lands holds the one thing a later read has to take.
        this.presenter.setRepaint(() -> {
            throw new IllegalStateException("Toolkit not running");
        });
        this.presenter.refreshCounts();

        this.presenter.setRepaint(() -> { });
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().years()).isNotEmpty();
    }

    @Test
    void aStartRefusedByTwoFolderRootsTellsTheUserToCheckBothOfThem() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(
                new PathViolation.Overlap(PathRole.INBOX, PathRole.LIBRARY_ROOT))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .contains(PathRoleLabels.of(PathRole.INBOX))
                .contains(PathRoleLabels.of(PathRole.LIBRARY_ROOT))
                .contains("Check them in Settings.");
    }

    // Three of the four ways a sift ends leave work behind, and none of them is a failure, so the
    // handle completes cleanly for all four. SHARDS_OUTSTANDING is the ordinary outcome on the
    // shipped provider: the user's own agent still has the judging to do.
    @Test
    void aSiftThatPausedForItsAgentDoesNotClaimToHaveFinished() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.start();

        assertThat(this.finishedView().heading()).isEqualTo("Sifting is waiting on your agent.");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.UNFINISHED);
    }

    @Test
    void aSiftStoppedByItsSpendCeilingSaysNothingMoreHasBeenSpent() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));

        this.presenter.start();

        assertThat(requireNonNull(this.finishedView().resume()).question())
                .contains("No more money has been spent");
    }

    @Test
    void aSiftThatAppliedItsDecisionsIsTheOneThatSaysItFinished() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(new CullJobOutcome.Applied(mock(CullReport.class),
                new ApplyReport(25, Map.of("Keep", 20), 0, 1, 3, List.of()), null));

        this.presenter.start();

        assertThat(this.finishedView().heading()).isEqualTo("Sifting finished.");
        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .contains(tuple("Photos looked at", "25"), tuple("Keep", "20"),
                        tuple("Copies set aside", "3"));
    }

    @Test
    void aRunThatMovedAPreviousRecordAsideSaysSoRatherThanLettingItLookLost() {
        final Path archived = Path.of("logs", "archives", "2019-2026-08-24_22-01-33");
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(new CullJobOutcome.Applied(mock(CullReport.class),
                new ApplyReport(25, Map.of(), 0, 0, 0, List.of()), archived));

        this.presenter.start();

        assertThat(requireNonNull(this.finishedView().archived()))
                .contains("moved that record to")
                .contains(archived.toString());
    }

    @Test
    void aSortWhoseDateFilesBarelyPairedWarnsThatItsDatesMayBeWrong() {
        this.choose(RunMode.SORT, "");
        this.sortEndsWith(sortSummaryWith(List.of("pairing-canary")));

        this.presenter.start();

        final RunResultView.Warning warned = requireNonNull(this.finishedView().warning());
        assertThat(warned.headline()).isEqualTo("The dates on these photos may be wrong.");
        assertThat(warned.detail()).contains("almost none of those matched a photo");
    }

    @Test
    void aSortWhoseDateFilesPairedCarriesNoWarningAtAll() {
        this.choose(RunMode.SORT, "");
        this.sortEndsWith(sortSummaryWith(List.of()));

        this.presenter.start();

        assertThat(this.finishedView().warning()).isNull();
    }

    @Test
    void aSortsCardCountsEveryBucketAnythingLandedIn() {
        this.choose(RunMode.SORT, "");
        this.sortEndsWith(new SortSummary(12, 1, 2, 5, 1, 2, 1, 0, List.of(), List.of(),
                Set.of(2019), List.of()));

        this.presenter.start();

        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .containsExactly(tuple("Photos sorted", "5"), tuple("Videos sorted", "1"),
                        tuple("Already in your library", "1"), tuple("Identical copies removed", "2"),
                        tuple("Set aside for review", "2"), tuple("Could not be dated", "1"));
    }

    @Test
    void aBucketNothingLandedInIsLeftOffTheCardRatherThanCountedAtZero() {
        this.choose(RunMode.SORT, "");
        this.sortEndsWith(sortSummaryWith(List.of()));

        this.presenter.start();

        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label)
                .containsExactly("Photos sorted", "Videos sorted");
    }

    @Test
    void aMoveToTheLibraryCountsEachPartOfItSeparatelyAndTogether() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");
        this.moveEndsWith(new CommitSummary(9, Map.of(LibraryBucket.PHOTOS, 6,
                LibraryBucket.VIDEOS, 2, LibraryBucket.FUNNY, 1)));

        this.presenter.start();

        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .containsExactly(tuple("Photos", "6"), tuple("Videos", "2"), tuple("Funny", "1"),
                        tuple("Moved to your library", "9"));
    }

    @Test
    void nothingStagedKillsStartForAMoveAndSaysNothingUnderTheField() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
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
                new YearRow(2020, 0, 12, List.of()))));
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
    void theButtonIsDeadForAModeWithNothingBehindItYet() {
        this.choose(RunMode.RESCUE, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.view().scopeHint()).contains("arrives with the Review screen");
    }

    @Test
    void theButtonIsDeadUntilTheCountsHaveLanded() {
        final var counting = new RunLauncherPresenter(this.pipeline, inlineProgress());

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
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
        this.presenter.refreshCounts();
        this.choose(RunMode.SIFT, "2019");

        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            assertThat(this.presenter.view().nothingStaged()).contains("Nothing is sorted yet");
            assertThat(this.presenter.view().cost()).isNull();
            return new InboxTally(1, 1L);
        });

        this.presenter.refreshCounts();
    }

    // The window is what makes this weak rather than flaky. On a slow enough machine the second
    // read might not have reached the tally even unserialised, and it would pass for the wrong
    // reason. It cannot fail against correct code.
    @Test
    void oneReadWaitsForAnotherRatherThanWalkingBesideIt() throws Exception {
        final var arrived = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var entries = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            entries.incrementAndGet();
            arrived.countDown();
            release.await();
            return new InboxTally(1, 1L);
        });

        final Thread first = Thread.ofVirtual().start(this.presenter::refreshCounts);
        arrived.await();
        final Thread second = Thread.ofVirtual().start(this.presenter::refreshCounts);
        assertThat(second.join(Duration.ofMillis(200))).isFalse();
        assertThat(entries).hasValue(1);

        release.countDown();
        first.join();
        second.join();
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
        final var opening = new RunLauncherPresenter(this.pipeline, inlineProgress());
        opening.setMode(RunMode.MOVE_TO_LIBRARY);

        assertThat(opening.confirmationNeeded()).isNull();

        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
        opening.refreshCounts();

        assertThat(opening.view().canStart()).isFalse();
    }

    @Test
    void aYearRowStaysMarkedEvenWhereTheModeCannotTakeTheYearItNames() {
        // A year of videos alone: the rows are live, and a sift over it still has nothing to look
        // at. An empty Inbox would refuse it too, in a mode whose rows do not mark at all.
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 0, 4, List.of(new MonthRow(6, 0, 4))))));
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
        for (final RunMode mode : RunMode.values()) {
            this.presenter.setMode(mode);
            said.add(this.presenter.view().modeHint());
        }

        assertThat(said).containsExactly(
                "Reads the dates on what is in your Inbox and moves it into Sorted, by year and "
                        + "month. Takes the oldest year in your Inbox.",
                "Sifts through your sorted photos and organises them into categories.",
                "Moves what is in Sorted into your library.",
                "Sorts, then sifts automatically. Takes the oldest year in your Inbox.",
                "Moves what is left in a Review folder into your library.");
    }

    @Test
    void theButtonNamesWhicheverModeItWouldRun() {
        final List<String> labels = new ArrayList<>();
        for (final RunMode mode : RunMode.values()) {
            this.presenter.setMode(mode);
            labels.add(this.presenter.view().startLabel());
        }

        assertThat(labels).containsExactly("Run Sort", "Run Sift", "Run Move to library",
                "Run Curate", "Run Rescue");
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
    void anEmptyInboxKillsStartForACurateToo() {
        this.anEmptyInbox();
        this.choose(RunMode.CURATE, "");

        assertThat(this.presenter.view().canStart()).isFalse();
    }

    // The confirm names the years and the file count, and a failed read knows neither. Offering the
    // press without its question is the one outcome ruled out, so the press goes instead.
    @Test
    void aBareMoveIsWithheldRatherThanOfferedWithoutItsConfirmWhenTheReadFailed() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.presenter.refreshCounts();
        this.choose(RunMode.MOVE_TO_LIBRARY, "");

        assertThat(this.presenter.view().canStart()).isFalse();
        assertThat(this.presenter.confirmationNeeded()).isNull();
        this.presenter.start();
        verify(this.pipeline, never()).commit(any());
    }

    @Test
    void aMoveNarrowedToAYearStillRunsAfterAFailedReadAndLeavesTheRefusalToTheFacade() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.presenter.refreshCounts();
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019");

        assertThat(this.presenter.view().canStart()).isTrue();
        assertThat(this.presenter.confirmationNeeded()).isNull();
    }

    @Test
    void anInboxThatCouldNotBeReadIsNotTakenForAnEmptyOne() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.presenter.refreshCounts();
        this.choose(RunMode.SORT, "");

        assertThat(this.presenter.view().canStart()).isTrue();
    }

    @Test
    void countsThatCannotBeReadAreReportedOnTheCardRatherThanThrown() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.presenter.refreshCounts();

        assertThat(this.presenter.view().inbox().headline()).contains("could not read");
        assertThat(this.presenter.view().years()).isEmpty();
    }

    @Test
    void nothingStagedIsSaidOnlyOnceTheCountsHaveLanded() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
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
                new YearRow(2020, 5, 0, List.of(new MonthRow(3, 5, 0), new MonthRow(4, 0, 0))))));
        this.presenter.refreshCounts();
        this.presenter.pressYear(2020);

        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::month).containsExactly(3);
    }

    @Test
    void aMonthHoldingOnlyVideoGetsARowSayingSo() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 5, 2, List.of(new MonthRow(3, 5, 0), new MonthRow(8, 0, 2))))));
        this.presenter.refreshCounts();
        this.presenter.pressYear(2020);

        assertThat(this.presenter.view().years().getFirst().months())
                .extracting(RunLauncherView.MonthChoice::month, RunLauncherView.MonthChoice::counts)
                .containsExactly(tuple(3, "5 photos"), tuple(8, "2 videos"));
    }

    @Test
    void aMonthRowCountsTheVideosAMoveWouldTakeAlongWithThePhotos() {
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2020, 5, 3, List.of(new MonthRow(3, 5, 3))))));
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
        assertThat(marked(this.presenter)).containsExactly(7, 11);

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
        assertThat(marked(this.presenter)).containsExactly(11);
    }

    @Test
    void takingTheLastMonthOutLeavesTheWholeYearRatherThanNothing() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 6);

        this.presenter.pressMonth(2019, 6);

        assertThat(this.presenter.view().scopeText()).isEqualTo("2019");
        assertThat(marked(this.presenter)).isEmpty();
        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
    }

    @Test
    void pressingTheYearAlreadyChosenFoldsItsMonthsAwayAndBringsThemBack() {
        this.aModeTheRowsScope();
        this.presenter.pressMonth(2019, 6);
        assertThat(marked(this.presenter)).containsExactly(6);

        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::monthsShown);
        assertThat(this.presenter.view().scopeText()).isEqualTo("2019 6");

        this.presenter.pressYear(2019);

        assertThat(this.presenter.view().years()).filteredOn(YearChoice::monthsShown)
                .extracting(YearChoice::year).containsExactly(2019);
        assertThat(marked(this.presenter)).containsExactly(6);
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
        assertThat(marked(this.presenter)).isEmpty();
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
        assertThat(this.viewOf(RunMode.CURATE).scopeNamesTheRun()).isFalse();
    }

    @Test
    void noRowIsMarkedWhileTheRunReadsTheInbox() {
        this.choose(RunMode.SORT, "2019 6");

        assertThat(this.presenter.view().years()).noneMatch(YearChoice::chosen);
        assertThat(this.presenter.view().years()).noneMatch(YearChoice::monthsShown);
        assertThat(marked(this.presenter)).isEmpty();
    }

    @Test
    void aMonthChosenBeforeAModeThatIgnoresTheRowsIsStillMarkedOnTheWayBack() {
        this.choose(RunMode.SIFT, "2019 6");
        this.presenter.setMode(RunMode.SORT);

        this.presenter.setMode(RunMode.SIFT);

        assertThat(marked(this.presenter)).containsExactly(6);
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
        this.presenter.setMode(RunMode.CURATE);

        assertThat(this.presenter.view().modes()).filteredOn(RunLauncherView.ModeChoice::chosen)
                .extracting(RunLauncherView.ModeChoice::mode).containsExactly(RunMode.CURATE);
    }

    @Test
    void everyModeGetsAButtonAndTheyReadInTheOrderTheyAreOffered() {
        assertThat(this.presenter.view().modes()).extracting(RunLauncherView.ModeChoice::label)
                .containsExactly("Sort", "Sift", "Move to library", "Curate", "Rescue");
    }

    @Test
    void nothingRunningAndNothingReportedLeavesTheLauncherUp() {
        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Setup.class);
    }

    @Test
    void aRunningJobPutsTheProgressAreaUpInPlaceOfTheLauncher() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Running.class);
    }

    @Test
    void theProgressAreaNamesTheWorkAndWhatItCovers() {
        this.choose(RunMode.SIFT, "2019 6-7");
        this.aSiftStillRunning();

        this.presenter.start();

        final RunProgressView showing = this.runningView();
        assertThat(showing.heading()).isEqualTo("Sift progress");
        assertThat(showing.scope()).isEqualTo("2019, June and July");
    }

    @Test
    void aSortNamesTheYearItWillPickRatherThanTheFieldItIgnored() {
        this.choose(RunMode.SORT, "2019");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.runningView().scope()).isEqualTo("the oldest year in your Inbox");
    }

    @Test
    void aPhaseThatHasReportedNoTotalDrawsWithoutAFraction() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();

        this.progress.phaseStarted("Sorting");

        assertThat(this.runningView().phases()).singleElement()
                .extracting(PhaseBar::label, PhaseBar::measured, PhaseBar::counts)
                .containsExactly("Sorting", false, null);
    }

    @Test
    void aPhaseWithCountsCarriesThemAndHowFarThroughItIs() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        this.progress.phaseStarted("Sorting");

        this.progress.tick("Sorting", 850, 1204);

        assertThat(this.runningView().phases()).singleElement()
                .extracting(PhaseBar::counts, PhaseBar::measured, PhaseBar::fraction)
                .containsExactly("850 of 1,204", true, 850d / 1204);
    }

    @Test
    void aRunWithNoPhaseYetSaysItIsStartingRatherThanShowingAnEmptyPage() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.runningView().phases()).isEmpty();
        assertThat(this.runningView().waiting()).isEqualTo("Starting...");
    }

    @Test
    void aPhaseLeftOverFromAnEarlierRunIsNotCountedAgainstThisOne() {
        this.choose(RunMode.SORT, "");
        this.progress.phaseStarted("Sorting");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.runningView().phases()).isEmpty();
    }

    @Test
    void cancelAsksTheRunningJobToStop() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        final JobHandle<Object> handle = requireNonNull(this.held);

        this.presenter.cancel();

        verify(handle).requestCancellation();
    }

    @Test
    void cancelGoesDeadOnceItHasBeenPressed() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        assertThat(this.runningView().cancelPressable()).isTrue();

        this.presenter.cancel();

        assertThat(this.runningView().cancelPressable()).isFalse();
    }

    @Test
    void aCancelledSiftWarnsAboutTheSheetAlreadyInFrontOfTheModel() {
        this.choose(RunMode.SIFT, "2019");
        this.aSiftStillRunning();
        this.presenter.start();

        this.presenter.cancel();

        assertThat(this.runningView().cancelling()).contains("up to about a minute");
    }

    @Test
    void aCancelledSortSaysWhatSurvivesRatherThanHowLongTheStopTakes() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();

        this.presenter.cancel();

        assertThat(this.runningView().cancelling())
                .isEqualTo("What has already been sorted stays where it is. "
                        + "Nothing further will be moved.");
    }

    @Test
    void theCancelButtonReportsTheStopRatherThanGoingDeadStillOffering() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        assertThat(this.runningView().cancelLabel()).isEqualTo("Cancel");

        this.presenter.cancel();

        assertThat(this.runningView().cancelLabel()).isEqualTo("Stopping...");
    }

    // Each of these was reaching the screen as "no plain words for why... report this as a bug",
    // because the presenter had no arm for its type. The refusal is deliberate and the app knows
    // exactly what is wrong, so the bug line is the one thing none of them may say.
    @Test
    void aTimelineAlreadyHoldingAnUnfinishedSiftIsRefusedInWordsRatherThanAsABug() {
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.ScopeOccupiedException(occupantOf2019())).when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .isEqualTo("You already have a sift of 2019 that has not finished. Sluice will not "
                        + "start another for the same timeline while that one is there.");
    }

    @Test
    void aCurateRefusedAfterItSortedSaysTheSortingStands() {
        this.choose(RunMode.CURATE, "");
        doThrow(new Pipeline.CurateConflictException(occupantOf2019(), sortSummaryWith(List.of())))
                .when(this.pipeline).curate(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .startsWith("Your photos were sorted, and then sifting stopped:")
                .endsWith("The sorting stands.");
    }

    @Test
    void aTimelineWhoseFolderCannotBeReadSaysSoRatherThanReportingABug() {
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.ScopeUnreadableException(Path.of("logs", "sift-prep", "2019"), new RuntimeException()))
                .when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .contains("could not read")
                .contains("whether a sift is already running");
    }

    @Test
    void aSiftOutsideTheFoldersInForceSaysWhereItIsAndWhatToDo() {
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.RunOutsideWorkingRootException(Path.of("D:", "old", "sift-prep", "2019")))
                .when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(messageOf(this.presenter).text())
                .contains("not inside the folders Sluice is set up with now")
                .contains("discard the sift");
    }

    @Test
    void cancelWithNothingRunningLeavesTheLauncherWhereItWas() {
        this.presenter.cancel();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Setup.class);
    }

    @Test
    void aFinishedRunPutsItsReportUpInPlaceOfTheLauncher() {
        this.chooseAndStart(RunMode.SORT, "");

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Finished.class);
    }

    @Test
    void dismissingTheReportBringsTheLauncherBack() {
        this.chooseAndStart(RunMode.SORT, "");

        this.presenter.dismissResult();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Setup.class);
    }

    @Test
    void aRunRefusedBeforeItStartedLeavesTheLauncherUpWithTheRefusalOnIt() {
        this.choose(RunMode.SORT, "");
        doThrow(new JobInProgressException("Something else is running.")).when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Setup.class);
        assertThat(messageOf(this.presenter).text()).isEqualTo("Something else is running.");
    }

    @Test
    void aReportAndARefusalAboutTheNextRunAreNeverOnScreenTogether() {
        this.chooseAndStart(RunMode.SIFT, "2019");
        // What a sift does to its own scope: applying the decisions empties the year the field
        // still names, so the launcher behind the report now refuses it.
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
        this.presenter.refreshCounts();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Finished.class);
        assertThat(this.presenter.view().scopeRefusal()).isNotNull();
    }

    @Test
    void aStoppedSpendingLimitOffersToContinueTheRun() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));

        this.presenter.start();

        assertThat(requireNonNull(this.finishedView().resume()).prepDir()).isEqualTo(PREP_DIR);
    }

    @Test
    void aSiftWaitingOnAnAgentOffersNoContinueBecauseNothingHasArrivedToActOn() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.start();

        assertThat(this.finishedView().resume()).isNull();
    }

    @Test
    void continuingResumesTheStoppedRunWithoutWaivingItsMissingSheets() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));
        this.presenter.start();
        final JobHandle<Object> resumed = finished();
        when(this.pipeline.resume(any(), anyBoolean())).thenReturn(retyped(resumed));

        this.presenter.continueRun(PREP_DIR);

        verify(this.pipeline).resume(PREP_DIR, false);
    }

    private RunProgressView runningView() {
        return ((RunStage.Running) this.presenter.stage()).progress();
    }

    private RunResultView finishedView() {
        return ((RunStage.Finished) this.presenter.stage()).result();
    }

    private static CullJobOutcome waitingBecause(final WaitingReason reason) {
        return new CullJobOutcome.Waiting(
                new WaitingCullJob("2019", PREP_DIR, new ShardTally(0, 0, 4), Instant.EPOCH),
                reason, CullReport.nothingSpent("anthropic", 4), null);
    }

    private static FxProgressPort inlineProgress() {
        return new FxProgressPort(Runnable::run);
    }

    private static RunLauncherView.Cost.Estimate estimateOf(final RunLauncherPresenter presenter) {
        return (RunLauncherView.Cost.Estimate) requireNonNull(presenter.view().cost());
    }

    private static RunLauncherView.Cost.Free freeOf(final RunLauncherPresenter presenter) {
        return (RunLauncherView.Cost.Free) requireNonNull(presenter.view().cost());
    }

    private static RunLauncherView.Message messageOf(final RunLauncherPresenter presenter) {
        return requireNonNull(presenter.view().message());
    }

    private static List<Integer> marked(final RunLauncherPresenter presenter) {
        return presenter.view().years().stream()
                .flatMap(year -> year.months().stream())
                .filter(RunLauncherView.MonthChoice::chosen)
                .map(RunLauncherView.MonthChoice::month)
                .toList();
    }

    private void anEmptyInbox() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(0, 0));
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

    private void chooseAndStart(final RunMode mode, final String scope) {
        this.choose(mode, scope);
        this.pipelineStarts();
        this.presenter.start();
    }

    // Each handle is built before the call that returns it is stubbed. Building one inside the
    // argument to when() would stub a second mock while the first stubbing is still open, which
    // Mockito reads as an unfinished one.
    private void siftEndsWith(final CullJobOutcome outcome) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(outcome));
        when(this.pipeline.cull(any())).thenReturn(retyped(handle));
    }

    private void sortEndsWith(final SortSummary summary) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(summary));
        when(this.pipeline.sort(any())).thenReturn(retyped(handle));
    }

    private void moveEndsWith(final CommitSummary summary) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(summary));
        when(this.pipeline.commit(any())).thenReturn(retyped(handle));
    }

    private static SortSummary sortSummaryWith(final List<String> warnings) {
        return new SortSummary(3, 0, 0, 2, 1, 0, 0, 0, List.of(), List.of(), Set.of(2019), warnings);
    }

    private void pipelineStarts() {
        final JobHandle<Object> sorted = finished();
        final JobHandle<Object> curated = finished();
        final JobHandle<Object> sifted = finished();
        final JobHandle<Object> moved = finished();
        when(this.pipeline.sort(any())).thenReturn(retyped(sorted));
        when(this.pipeline.curate(any())).thenReturn(retyped(curated));
        when(this.pipeline.cull(any())).thenReturn(retyped(sifted));
        when(this.pipeline.commit(any())).thenReturn(retyped(moved));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> aSortStillRunning() {
        final CompletableFuture<Object> running = new CompletableFuture<>();
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(running);
        when(this.pipeline.sort(any())).thenReturn(retyped(handle));
        this.held = handle;
        return running;
    }

    @SuppressWarnings("unchecked")
    private void aSiftStillRunning() {
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        when(this.pipeline.cull(any())).thenReturn(retyped(handle));
        this.held = handle;
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<Object> finished() {
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(null));
        return handle;
    }

    // What a handle claims to carry never matters here, since the presenter reads its result
    // through onComplete() alone. Each Pipeline method declares its own result type, and one mock
    // answers them all.
    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> retyped(final JobHandle<?> handle) {
        return (JobHandle<T>) handle;
    }

    private static CullRunSummary occupantOf2019() {
        return new CullRunSummary("2019", Path.of("logs", "sift-prep", "2019"),
                new PrepDirHealth(PrepDirHealth.State.WAITING, List.of()),
                new ShardTally(0, 0, 4), Instant.EPOCH);
    }
}
