package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.WaitingReason;
import photos.sluice.application.port.out.CullReport;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.job.WaitingCullJob;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.port.out.MissingCredentialException;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.model.SortSummary.Guessed;
import photos.sluice.domain.commit.LibraryBucket;
import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.model.MonthRange;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

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

    private static final SpendEstimate NOTHING = new SpendEstimate(0, 0, true, false, false);

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    private static final List<Path> CARD = List.of(Path.of("DCIM"));

    private static final long SLOWER_THAN_THE_WAIT = 400;

    // A reader who says yes to whatever they were asked, for the tests that are about what happens
    // afterwards rather than about the asking.
    private static final Predicate<RunSetupPresenter.Confirmation> AGREED = _ -> true;

    private final Pipeline pipeline = mock(Pipeline.class);

    private final FxProgressPort progress = new FxProgressPort(Runnable::run);

    private final RunLauncherPresenter presenter = new RunLauncherPresenter(this.pipeline, this.progress);

    private final RunSetupPresenter setup = this.presenter.setup();

    // The handle behind whichever job a test left in flight, so a test about Cancel can ask what
    // reached it.
    private @Nullable JobHandle<Object> held;

    @BeforeEach
    void aStagedLibraryAndAnInboxWithSomethingInIt() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10), new MonthRow(7, 30, 0),
                        new MonthRow(11, 30, 0))),
                new YearRow(2018, 50, 0, List.of(new MonthRow(1, 30, 0), new MonthRow(6, 20, 0)))), 0));
        when(this.pipeline.estimateFor(anyInt())).thenReturn(NOTHING);
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        this.setup.refreshCounts();
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
    void everyMonthAYearHoldsIsTakenAsTheRunThatSpansThem() {
        this.chooseAndStart(RunMode.MOVE_TO_LIBRARY, "2019 6,7,11");

        verify(this.pipeline).commit(new CommitScope.Year(2019, new MonthRange(6, 11)));
    }

    @Test
    void aGapOverAMonthHoldingSomethingIsRefusedAndThatMonthIsNamed() {
        this.choose(RunMode.MOVE_TO_LIBRARY, "2019 6,11");

        assertThat(this.setup.view().scopeRefusal())
                .isEqualTo("Moving to library narrows to a span of months, not a list. Reading "
                        + "6,11 as 6-11 would take July too. Choose a span of months, like "
                        + "6-8, or none at all for the whole year.");
        this.presenter.start();
        verify(this.pipeline, never()).commit(any());
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
    void theSidebarIsMarkedForNothingWhileTheDashboardIsIdle() {
        assertThat(this.presenter.dashboardMark()).isEqualTo(DashboardMark.NONE);
    }

    @Test
    void theSidebarIsMarkedForACardNobodyHasClosed() {
        this.chooseAndStart(RunMode.SORT, "");

        assertThat(this.presenter.dashboardMark()).isEqualTo(DashboardMark.FINISHED);
    }

    @Test
    void theMarkGoesWithThePressThatClosesTheCard() {
        this.chooseAndStart(RunMode.SORT, "");

        this.presenter.dismissResult();

        assertThat(this.presenter.dashboardMark()).isEqualTo(DashboardMark.NONE);
    }

    @Test
    void theSidebarIsMarkedWhileAJobIsStillGoing() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.presenter.dashboardMark()).isEqualTo(DashboardMark.RUNNING);
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
    void aRefusedStartIsReportedInTheWordsTheRefusalItselfUsed() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new JobInProgressException("Sluice is already running a job.")).when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(this.reported().text()).isEqualTo("Sluice is already running a job.");
        assertThat(this.reported().refused()).isTrue();
    }

    @Test
    void aStartRefusedBecauseAFolderRootWentBadReportsWhichFolderRatherThanFailing() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(this.reported().refused()).isTrue();
        assertThat(this.setup.view().canStart()).isTrue();
    }

    @Test
    void aRunThatFinishesSaysSoAndOffersTheButtonAgain() {
        this.choose(RunMode.SORT, "2019");
        this.pipelineStarts();

        this.presenter.start();

        assertThat(this.finishedView().heading()).isEqualTo("Sorting finished.");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.FINISHED);
        this.presenter.dismissResult();
        assertThat(this.setup.view().canStart()).isTrue();
    }

    @Test
    void whichAnswerTheQuestionTookDecidesWhetherTheOriginalsAreRemoved() {
        this.importEndsWith(new ImportSummary(2, 2, 0, 0, 0, 0, false));

        this.presenter.startImportCopying(CARD);

        verify(this.pipeline).importFrom(CARD, ImportKind.COPY);

        this.presenter.dismissResult();
        this.presenter.startImportMoving(CARD);

        verify(this.pipeline).importFrom(CARD, ImportKind.MOVE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRunningImportNamesTheFoldersItWasGiven() {
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        when(this.pipeline.importFrom(any(), any())).thenReturn(retyped(handle));

        this.presenter.startImportCopying(List.of(Path.of("cards", "DCIM"), Path.of("phone")));

        assertThat(((RunStage.Running) this.presenter.stage()).progress().scope())
                .isEqualTo("DCIM and phone");
    }

    @Test
    void aFinishedImportCountsWhatArrivedAndWhatWasAlreadyThere() {
        this.importEndsWith(new ImportSummary(1204, 1180, 24, 0, 0, 0, false));

        this.presenter.startImportCopying(CARD);

        assertThat(this.finishedView().heading()).isEqualTo("Importing finished.");
        assertThat(this.finishedView().counts())
                .extracting(RunResultView.Count::label, RunResultView.Count::value)
                .containsExactly(tuple("Imported", "1,180"),
                        tuple("Skipped: already in your Inbox", "24"));
    }

    @Test
    void aStoppedImportIsUnfinishedRatherThanFailedAndSaysHowToPickItUp() {
        this.importEndsWith(new ImportSummary(1204, 312, 0, 0, 0, 0, true));

        this.presenter.startImportCopying(CARD);

        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.UNFINISHED);
        assertThat(this.finishedView().heading()).isEqualTo("Importing stopped.");
        assertThat(this.finishedView().detail()).contains("Run the import again");
    }

// Every import refusal is something the person choosing did. Left to the fallback arm they reach
    // the reader as a class name and an invitation to file a bug against Sluice.
    @Test
    void aRefusedImportIsReportedInTheWordsTheRefusalItselfUsed() {
        doThrow(new ImportSourceException(
                "Sluice cannot import from D:\\Photos, because your Inbox is inside it."))
                .when(this.pipeline).importFrom(any(), any());

        this.presenter.startImportCopying(List.of(Path.of("D:\\Photos")));

        assertThat(this.reported().text())
                .isEqualTo("Sluice cannot import from D:\\Photos, because your Inbox is inside it.");
        assertThat(this.reported().refused()).isTrue();
    }

    // An import kind left set would have a later sort's cancellation tell the reader their
    // originals were gone, about photos nothing had touched.
    @Test
    void aSortStartedAfterAMoveImportClaimsNothingAboutAnyOriginals() {
        this.importEndsWith(new ImportSummary(1, 1, 0, 0, 0, 0, false));
        this.presenter.startImportMoving(CARD);
        this.presenter.dismissResult();

        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        this.presenter.cancel();

        assertThat(((RunStage.Running) this.presenter.stage()).progress().cancelling())
                .startsWith("What was sorted stays where it is.");
    }

    @Test
    void nothingIsBroughtInWhileAnotherJobHasTheSlot() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();

        this.presenter.startImportCopying(CARD);

        verify(this.pipeline, never()).importFrom(any(), any());
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
                .contains("Report this as a bug")
                .contains("Malformed hash index line: 7");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.FAILED);
    }

    // A rescue stops on a note it cannot read as text. The out-of-reach sentence offers two
    // reasons, and both are untrue of that: the file is there, and nothing else is holding it.
    @SuppressWarnings("unchecked")
    @Test
    void aFileThatTurnedOutNotToHoldTextIsNotReportedAsOneNothingCouldReach() {
        this.choose(RunMode.SORT, "2019");
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.failedFuture(
                new UncheckedIOException(new MalformedInputException(1))));
        when(this.pipeline.sort(any())).thenReturn(retyped(handle));

        this.presenter.start();

        assertThat(requireNonNull(this.finishedView().detail()))
                .contains("holds something other than text")
                .doesNotContain("could not be reached")
                .doesNotContain("not there anymore");
        assertThat(this.finishedView().tone()).isEqualTo(RunResultView.Tone.FAILED);
    }

    @Test
    void aStartRefusedByAFolderRootNamesThatFolderTheWayTheRestOfTheAppNamesIt() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(this.reported().text())
                .contains(PathRoleLabels.of(PathRole.INBOX))
                .doesNotContain("sluice.paths");
    }

    // Started from a folder's own row on another screen, so nothing on the launcher was pressed and
    // nothing there could have refused it first. Every refusal the facade raises has to arrive here
    // in words, on the face the reader is sent to.
    @Test
    void aRescueStartedFromTheReviewScreenReportsItsRefusalInPlainWords() {
        doThrow(new PathsMisconfiguredException(
                List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("gone")))))
                .when(this.pipeline).rescue(RescueRoot.REVIEW, "Food");

        this.presenter.rescueFromReview(RescueRoot.REVIEW, "Food", "Food");

        assertThat(this.reported().text())
                .contains(PathRoleLabels.of(PathRole.LIBRARY_ROOT))
                .doesNotContain("sluice.paths");
    }

    // The reader crossed from one screen to another, so the run has to still be named by the folder
    // whose button they pressed.
    @SuppressWarnings("unchecked")
    @Test
    void aRescueStartedFromTheReviewScreenIsNamedByThatFolderWhileItRuns() {
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        when(this.pipeline.rescue(RescueRoot.REVIEW, "Food")).thenReturn(retyped(handle));

        this.presenter.rescueFromReview(RescueRoot.REVIEW, "Food", "Food");

        assertThat(((RunStage.Running) this.presenter.stage()).progress().scope()).isEqualTo("Food");
    }

    @Test
    void theModeButtonsGoDeadWhileAJobIsInFlightAndComeBackAfterIt() {
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();

        this.presenter.start();
        assertThat(this.setup.view().modes()).noneMatch(ModeChoice::pressable);

        sorting.complete(new Object());
        assertThat(this.setup.view().modes()).allMatch(ModeChoice::pressable);
    }

    @Test
    void theStartButtonIsDeadWhileAJobIsInFlightAndComesBackAfterIt() {
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();

        this.presenter.start();
        assertThat(this.setup.view().canStart()).isFalse();

        sorting.complete(new Object());
        assertThat(this.setup.view().canStart()).isTrue();
    }

    // What this proves is the wiring, not the face. A face holding a test's own redraw reports the
    // same thing whatever the dashboard handed the real one, so only a real slow read reaches it.
    @Test
    void aSlowReadDrawsTheScreenTheDashboardWasGiven() throws Exception {
        final var held = new CountDownLatch(1);
        final var drawn = new AtomicInteger();
        when(this.pipeline.inboxTally()).thenAnswer(_ -> {
            held.await();
            return new InboxTally(12, 4_300);
        });
        // Its own dashboard, because a read counts the draws it causes. The one in the fixture has
        // already read once, and that read's own wake-up would land inside this one and draw again.
        final var dashboard = new RunLauncherPresenter(this.pipeline, new FxProgressPort(Runnable::run));
        dashboard.setRepaint(drawn::incrementAndGet);

        final Thread walking = Thread.ofVirtual().start(dashboard.setup()::refreshCounts);
        Thread.sleep(SLOWER_THAN_THE_WAIT);

        assertThat(drawn).hasValue(1);
        held.countDown();
        walking.join();
    }

    @Test
    void anOutcomeNamesTheModeItsRunWasStartedInRatherThanWhicheverIsChosenWhenItEnds() {
        this.choose(RunMode.SORT, "2019");
        final CompletableFuture<Object> sorting = this.aSortStillRunning();
        this.presenter.start();

        this.setup.setMode(RunMode.SIFT);
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

        assertThat(this.reported().text()).contains("one job runs at a time");
        assertThat(this.reported().refused()).isTrue();
        verify(this.pipeline, never()).commit(any());
    }

    @Test
    void aPressLandingBeforeTheReadHasFinishedIsToldWhyNothingStarted() {
        final var counting = new RunLauncherPresenter(this.pipeline, new FxProgressPort(Runnable::run));
        counting.setup().setMode(RunMode.SORT);
        assertThat(counting.setup().view().canStart()).isFalse();

        counting.start();

        assertThat(requireNonNull(counting.setup().view().message()).text())
                .contains("Still reading your folders");
        verify(this.pipeline, never()).sort(any());
    }

    @Test
    void aStartRefusedByTwoFolderRootsTellsTheUserToCheckBothOfThem() {
        this.choose(RunMode.SORT, "2019");
        doThrow(new PathsMisconfiguredException(List.of(
                new PathViolation.Overlap(PathRole.INBOX, PathRole.LIBRARY_ROOT))))
                .when(this.pipeline).sort(any());

        this.presenter.start();

        assertThat(this.reported().text())
                .contains(PathRoleLabels.of(PathRole.INBOX))
                .contains(PathRoleLabels.of(PathRole.LIBRARY_ROOT));
        assertThat(this.reported().location()).isEqualTo(Location.SETTINGS);
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
    void theOneSiftEndingWithNoButtonOffersTheWayToPickItUp() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.start();

        assertThat(this.finishedView().action()).isNull();
        assertThat(this.finishedView().location()).isEqualTo(Location.RUNS);
    }

    // The other two pauses put Continue on the card. A way out to another screen would compete with
    // the button that resumes the sift where the reader already is.
    @Test
    void aPauseThatOffersContinueSendsTheReaderNowhereElse() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));

        this.presenter.start();

        assertThat(this.finishedView().action()).isNotNull();
        assertThat(this.finishedView().location()).isNull();
    }

    @Test
    void aSiftStoppedByItsSpendCeilingSaysNothingMoreHasBeenSpent() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));

        this.presenter.start();

        assertThat(((CardAction.ContinueRun) requireNonNull(this.finishedView().action())).note())
                .contains("Nothing more has been spent from your provider account balance");
    }

    @Test
    void aSiftThatAppliedItsDecisionsIsTheOneThatSaysItFinished() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(new CullJobOutcome.Applied(CullReport.nothingSpent("anthropic", 0),
                new ApplyReport(25, Map.of("Keep", 20), 0, 1, 3, List.of()), null, null));

        this.presenter.start();

        assertThat(this.finishedView().heading()).isEqualTo("Sifting finished.");
        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .contains(tuple("Photos looked at", "25"), tuple("Keep", "20"),
                        tuple("Copies moved to Duplicates", "3"));
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
        this.sortEndsWith(new SortSummary(12, 1, 2, 5, 1, 2, 1, 0, List.of(), Guessed.NONE,
                List.of(), Set.of(2019), List.of(), false, 0));

        this.presenter.start();

        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .containsExactly(tuple("Photos sorted", "5"), tuple("Videos sorted", "1"),
                        tuple("Already in your Library", "1"), tuple("Identical copies removed", "2"),
                        tuple("Moved to Review", "2"), tuple("Could not be dated", "1"));
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
        this.moveEndsWith(new CommitSummary(9, 0, Map.of(LibraryBucket.PHOTOS, 6,
                LibraryBucket.VIDEOS, 2, LibraryBucket.FUNNY, 1), false));

        this.presenter.start();

        assertThat(this.finishedView().counts()).extracting(RunResultView.Count::label, RunResultView.Count::value)
                .containsExactly(tuple("Photos", "6"), tuple("Videos", "2"), tuple("Funny", "1"),
                        tuple("Moved to your Library", "9"));
    }

    // The confirm names the years and the file count, and a failed read knows neither. Offering the
    // press without its question is the one outcome ruled out, so the press goes instead.
    @Test
    void aBareMoveIsWithheldRatherThanOfferedWithoutItsConfirmWhenTheReadFailed() {
        when(this.pipeline.inboxTally())
                .thenThrow(new PathsMisconfiguredException(List.of(new NotADirectory(PathRole.INBOX, Path.of("gone")))));
        this.setup.refreshCounts();
        this.choose(RunMode.MOVE_TO_LIBRARY, "");

        assertThat(this.setup.view().canStart()).isFalse();
        assertThat(this.setup.confirmationNeeded()).isNull();
        this.presenter.start();
        verify(this.pipeline, never()).commit(any());
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
    void aPhaseLeftOverFromAnEarlierRunIsNotCountedAgainstThisOne() {
        this.choose(RunMode.SORT, "");
        this.progress.phaseStarted("Sorting");
        this.aSortStillRunning();

        this.presenter.start();

        assertThat(this.runningView().phases()).isEmpty();
    }

    // The stub announces from inside the submit, which is where a real job announces from: its own
    // first statement, on its own thread.
    @SuppressWarnings("unchecked")
    @Test
    void aRunsOwnPlanSurvivesTheClearingOfTheRunBeforeIt() {
        this.choose(RunMode.SORT, "");
        this.progress.phaseStarted("Sorting");
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        when(this.pipeline.sort(any())).thenAnswer(_ -> {
            this.progress.phasesPlanned(List.of("Finding dates...", "Sorting..."));
            return handle;
        });

        this.presenter.start();

        assertThat(this.runningView().phases()).extracting(PhaseBar::label)
                .containsExactly("Finding dates...", "Sorting...");
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
    void aSecondPressGivesUpOnTheFileTheRunIsWriting() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        final JobHandle<Object> handle = requireNonNull(this.held);
        this.presenter.cancel();
        assertThat(this.runningView().cancelPressable()).isTrue();

        this.presenter.cancel();

        verify(handle).requestAbandon();
        assertThat(this.runningView().cancelPressable()).isFalse();
    }

    @Test
    void pressingAgainAfterGivingUpOnTheFileChangesNothing() {
        this.choose(RunMode.SORT, "");
        this.aSortStillRunning();
        this.presenter.start();
        final JobHandle<Object> handle = requireNonNull(this.held);
        this.presenter.cancel();
        this.presenter.cancel();

        this.presenter.cancel();

        verify(handle, times(2)).requestAbandon();
        assertThat(this.runningView().cancelLabel()).isEqualTo("Stopping now...");
    }

    @Test
    void aStoppedSiftGoesDeadOnTheFirstPress() {
        this.choose(RunMode.SIFT, "2019");
        this.aSiftStillRunning();
        this.presenter.start();
        assertThat(this.runningView().cancelPressable()).isTrue();

        this.presenter.cancel();

        assertThat(this.runningView().cancelPressable()).isFalse();
    }

    // Each of these was reaching the screen as "no plain words for why... report this as a bug",
    // because the presenter had no arm for its type. The refusal is deliberate and the app knows
    // exactly what is wrong, so the bug line is the one thing none of them may say.
    @Test
    void aTimeframeAlreadyHoldingAnUnfinishedSiftIsRefusedInWordsRatherThanAsABug() {
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.ScopeOccupiedException(occupantOf2019())).when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(this.reported().text())
                .isEqualTo("You already have a sift of 2019 that has not finished. Another cannot "
                        + "be started for the same timeframe while that one is there. "
                        + "Continue it or discard it first.");
        assertThat(this.reported().location()).isEqualTo(Location.RUNS);
    }

    // The engine refuses a keyless provider before it starts a job. So this is the narrower shape:
    // a key going missing between that check and the request, or a resume, which is not checked up
    // front. Both arrive as a failed run rather than as a refusal.
    @Test
    void aSiftWithNoProviderKeySendsThemToSettingsRatherThanReportingABug() {
        this.choose(RunMode.SIFT, "2019");
        this.siftFailsWith(new MissingCredentialException(
                new SecretId("anthropic", "ANTHROPIC_API_KEY"),
                "No API key is stored for the 'anthropic' vision provider; add one in Settings"));

        this.presenter.start();

        assertThat(this.finishedView().detail())
                .isEqualTo("A sift cannot be started because your provider key is not set.");
        assertThat(this.finishedView().location()).isEqualTo(Location.SETTINGS);
    }

    // A store answering neither yes nor no is a broken install, so the bug line is earned here and
    // stays. What it must not be is the whole message.
    @Test
    void aSiftWhoseCredentialStoreIsBrokenSaysSoBeforeTheBugLine() {
        this.choose(RunMode.SIFT, "2019");
        this.siftFailsWith(new SecretStoreException(SecretStoreException.Tier.KEYRING,
                "the keyring returned 0x80070005"));

        this.presenter.start();

        assertThat(this.finishedView().detail())
                .startsWith("Your key could not be read. The credential store on this computer "
                        + "refused to answer.")
                .endsWith("the keyring returned 0x80070005");
    }

    @Test
    void aTimeframeWhoseFolderCannotBeReadSaysSoRatherThanReportingABug() {
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.ScopeUnreadableException(Path.of("logs", "sift-prep", "2019"), new RuntimeException()))
                .when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(this.reported().text())
                .contains("cannot be read")
                .contains("Cannot determine the sifts for this timeframe");
    }

    @Test
    void aSiftOutsideTheFoldersInForceSaysWhereItIsAndWhatToDo() {
        // The path is rendered rather than spelled out, since its separator is the platform's.
        final Path outside = Path.of("D:", "old", "sift-prep", "2019");
        this.choose(RunMode.SIFT, "2019");
        doThrow(new Pipeline.RunOutsideWorkingRootException(outside)).when(this.pipeline).cull(any());

        this.presenter.start();

        assertThat(this.reported().text())
                .isEqualTo("This sift is at " + outside + ", which is not inside the "
                        + "folders currently saved. Point your working folder back at "
                        + "the one holding it to work on it again.");
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
        assertThat(this.reported().text()).isEqualTo("Something else is running.");
    }

    @Test
    void aReportAndARefusalAboutTheNextRunAreNeverOnScreenTogether() {
        this.chooseAndStart(RunMode.SIFT, "2019");
        // What a sift does to its own scope: applying the decisions empties the year the field
        // still names, so the launcher behind the report now refuses it.
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        this.setup.refreshCounts();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Finished.class);
        assertThat(this.setup.view().scopeRefusal()).isNotNull();
    }

    @Test
    void aStoppedSpendingLimitOffersToContinueTheRun() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.CEILING_REACHED));

        this.presenter.start();

        assertThat(((CardAction.ContinueRun) requireNonNull(this.finishedView().action())).prepDir()).isEqualTo(PREP_DIR);
    }

    @Test
    void siftingFromAFinishedSortsCardCoversTheWholeTimeframeThatSortFilled() {
        this.aFinishedSortShowing();
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.siftNow(offerOf(2019), AGREED);

        verify(this.pipeline).cull(new CullScope.Year(2019, null));
    }

    // The one line between a card press and somebody's provider balance. Every other test here
    // agrees to the question, so without this one the gate could be deleted and nothing would say.
    @Test
    void backingOutOfTheQuestionStartsNothing() {
        this.aFinishedSortShowing();
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.siftNow(offerOf(2019), _ -> false);

        verify(this.pipeline, never()).cull(any());
    }

    @Test
    void aSiftFromTheCardIsAskedAboutWhereTheProviderSpends() {
        this.aFinishedSortShowing();
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));
        final var asked = new AtomicInteger();

        this.presenter.siftNow(offerOf(2019), _ -> {
            asked.incrementAndGet();
            return true;
        });

        assertThat(asked.get()).isOne();
    }

    @Test
    void aSiftFromTheCardIsAskedAboutOnAFreeProviderToo() {
        when(this.pipeline.configuredProviderSpends()).thenReturn(false);
        this.aFinishedSortShowing();
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));
        final var asked = new AtomicInteger();

        this.presenter.siftNow(offerOf(2019), _ -> {
            asked.incrementAndGet();
            return true;
        });

        assertThat(asked.get()).isOne();
        verify(this.pipeline).cull(new CullScope.Year(2019, null));
    }

    @Test
    void aSiftOfATimeframeHoldingOnlyVideosIsRefusedWithoutEverAsking() {
        this.aFinishedSortShowing();
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2021, 0, 0, List.of(new MonthRow(6, 0, 0)))), 0));
        this.setup.refreshCounts();
        final var asked = new AtomicInteger();

        this.presenter.siftNow(offerOf(2021), _ -> {
            asked.incrementAndGet();
            return true;
        });

        assertThat(asked.get()).isZero();
        verify(this.pipeline, never()).cull(any());
        assertThat(this.reportedOnTheCard().text())
                .isEqualTo("No photos are sorted for 2021, so there is nothing to sift.");
    }

    @Test
    void aSiftRefusedFromTheCardIsReportedOnTheCard() {
        this.aFinishedSortShowing();
        doThrow(new JobInProgressException("Sluice is already running a job."))
                .when(this.pipeline).cull(any());

        this.presenter.siftNow(offerOf(2019), AGREED);

        assertThat(this.reportedOnTheCard().text()).isEqualTo("Sluice is already running a job.");
        assertThat(this.setup.view().message()).isNull();
    }

    // The counts are what the question was sized from, so a press without them would spend on a
    // scope nobody was shown a figure for.
    @Test
    void aSiftIsTurnedBackWhereTheFoldersCouldNotBeCountedRatherThanStartedBlind() {
        this.aFinishedSortShowing();
        when(this.pipeline.sortedTally()).thenThrow(new RuntimeException("the disk went away"));
        this.setup.refreshCounts();

        this.presenter.siftNow(offerOf(2019), AGREED);

        verify(this.pipeline, never()).cull(any());
        assertThat(this.reportedOnTheCard().text())
                .isEqualTo("Your folders could not be read.");
        assertThat(this.reportedOnTheCard().location()).isEqualTo(Location.SETTINGS);
    }

    @Test
    void dismissingTheCardTakesItsRefusalWithIt() {
        this.aFinishedSortShowing();
        doThrow(new JobInProgressException("Sluice is already running a job."))
                .when(this.pipeline).cull(any());
        this.presenter.siftNow(offerOf(2019), AGREED);

        this.presenter.dismissResult();

        assertThat(this.presenter.stage()).isInstanceOf(RunStage.Setup.class);
        assertThat(this.setup.view().message()).isNull();
    }

    @Test
    void aSiftWaitingOnAnAgentOffersNoContinueBecauseNothingHasArrivedToActOn() {
        this.choose(RunMode.SIFT, "2019");
        this.siftEndsWith(waitingBecause(WaitingReason.SHARDS_OUTSTANDING));

        this.presenter.start();

        assertThat(this.finishedView().action()).isNull();
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

    private RunLauncherView.Message reported() {
        return requireNonNull(this.setup.view().message());
    }

    private static CardAction.SiftNow offerOf(final int year) {
        return new CardAction.SiftNow("Sift " + year, year, 6);
    }

    private RunLauncherView.Message reportedOnTheCard() {
        return requireNonNull(((RunStage.Finished) this.presenter.stage()).message());
    }

    private void aFinishedSortShowing() {
        this.choose(RunMode.SORT, "");
        this.sortEndsWith(sortSummaryWith(List.of()));
        this.presenter.start();
    }

    private static CullJobOutcome waitingBecause(final WaitingReason reason) {
        return new CullJobOutcome.Waiting(
                new WaitingCullJob("2019", PREP_DIR, new ShardTally(0, 0, 4), Instant.EPOCH),
                reason, CullReport.nothingSpent("anthropic", 4), null);
    }

    private void choose(final RunMode mode, final String scope) {
        this.setup.setMode(mode);
        this.setup.setScope(scope);
    }

    private void chooseAndStart(final RunMode mode, final String scope) {
        this.choose(mode, scope);
        this.pipelineStarts();
        this.presenter.start();
    }

    private void importEndsWith(final ImportSummary brought) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(brought));
        when(this.pipeline.importFrom(any(), any())).thenReturn(retyped(handle));
    }

    // Each handle is built before the call that returns it is stubbed. Building one inside the
    // argument to when() would stub a second mock while the first stubbing is still open, which
    // Mockito reads as an unfinished one.
    private void siftEndsWith(final CullJobOutcome outcome) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(outcome));
        when(this.pipeline.cull(any())).thenReturn(retyped(handle));
    }

    private void siftFailsWith(final RuntimeException thrown) {
        final JobHandle<Object> handle = finished();
        when(handle.onComplete()).thenReturn(CompletableFuture.failedFuture(thrown));
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
        return new SortSummary(3, 0, 0, 2, 1, 0, 0, 0, List.of(), Guessed.NONE, List.of(), Set.of(2019), warnings, false, 0);
    }

    private void pipelineStarts() {
        final JobHandle<Object> sorted = finished();
        final JobHandle<Object> sifted = finished();
        final JobHandle<Object> moved = finished();
        when(this.pipeline.sort(any())).thenReturn(retyped(sorted));
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
