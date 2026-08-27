package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.Kind;
import photos.sluice.adapter.ui.RunsView.RunCard;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    void theCardsAreOrderedByWhatWantsSomebodyFirst() {
        final RunsPresenter presenter = presenterOver(run("2015", State.WAITING),
                run("2016", State.DAMAGED), run("2017", State.BLOCKED), run("2018", State.READY));

        assertThat(presenter.view().unfinished()).extracting(RunCard::scope)
                .containsExactly("2018", "2017", "2016", "2015");
    }

    @Test
    void aRunWhoseShardsAreAllInIsOfferedAWayToFinishIt() {
        final RunsPresenter presenter = presenterOver(run("2019", State.READY));

        final RunCard card = presenter.view().unfinished().getFirst();

        assertThat(card.headline()).isEqualTo("Ready to finish");
        assertThat(card.actions()).extracting(Action::label)
                .containsExactly("Finish this sift", "Discard");
    }

    @Test
    void aRunStillWaitingOnSheetsIsOfferedAWayToLookForThem() {
        final RunsPresenter presenter = presenterOver(run("2019", State.WAITING));

        assertThat(presenter.view().unfinished().getFirst().actions()).extracting(Action::label)
                .containsExactly("Check for answers", "Discard");
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
                .isEqualTo("This sift's records could not be read, so none of these photos were "
                        + "moved.");
    }

    @Test
    void aBlockedRunSpanningTwoKindsOfFaultNamesNeitherRatherThanPickingOne() {
        final var health = new PrepDirHealth(State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("a")), new Finding.MissingReason("m", 0)));
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"), health,
                new ShardTally(2, 2, 2), Instant.now()));

        assertThat(presenter.view().unfinished().getFirst().detail())
                .isEqualTo("There were problems with the sift, so none of these photos were moved.");
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
        final var presenter = new RunsPresenter(pipeline);
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
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        assertThat(presenter.unfinishedRuns()).isZero();
    }

    @Test
    void discardingARunAsksFirstAndNamesWhatItSetsAsideAndWhereItGoes() {
        final RunsPresenter presenter = presenterOver(new CullRunSummary("2019", Path.of("p"),
                new PrepDirHealth(State.WAITING, List.of()), new ShardTally(17, 17, 28), Instant.now()));

        final Action discard = presenter.view().unfinished().getFirst().actions().getLast();

        assertThat(discard.confirm()).isNotNull();
        assertThat(discard.confirm().heading()).isEqualTo("Discard the sift of 2019?");
        assertThat(discard.confirm().question())
                .contains("17 sheet decisions you have already paid for")
                .contains(ARCHIVES.toString())
                .doesNotContain("graveyard");
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
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        verify(pipeline).resume(prepDir, false);
    }

    @Test
    void aRefusedPressLeavesTheCardsAloneAndSaysWhy() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
        when(pipeline.resume(any(), anyBoolean()))
                .thenThrow(new JobInProgressException("Something else is running."));
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(presenter.view().message()).isNotNull();
        assertThat(requireNonNull(presenter.view().message()).text()).isEqualTo("Something else is running.");
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
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getLast());

        assertThat(presenter.view().message()).isNotNull();
        assertThat(requireNonNull(presenter.view().message()).text())
                .startsWith("That sift finished before it could be discarded");
    }

    @Test
    void aReadThatWorksClearsWhatAFailedOneHadToReport() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns())
                .thenThrow(new IllegalStateException("nope"))
                .thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        final var presenter = new RunsPresenter(pipeline);
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
        final var presenter = new RunsPresenter(pipeline);
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
        final var presenter = new RunsPresenter(pipeline);
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
        final var presenter = new RunsPresenter(pipeline);

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
        final JobHandle<CullJobOutcome> job = neverFinishes();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(presenter.working()).isTrue();
        assertThat(presenter.view().unfinished().getFirst().actions()).isEmpty();
    }

    @Test
    void aRefusedPressLeavesTheScreenAbleToTryAgain() {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.READY))));
        when(pipeline.resume(any(), anyBoolean()))
                .thenThrow(new JobInProgressException("busy"));
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();

        presenter.press(presenter.view().unfinished().getFirst().actions().getFirst());

        assertThat(presenter.working()).isFalse();
        assertThat(presenter.view().unfinished().getFirst().actions()).isNotEmpty();
    }

    @Test
    void nothingIsAskedOfTheFacadeUntilTheScreenIsRead() {
        final Pipeline pipeline = pipeline();
        new RunsPresenter(pipeline);

        verify(pipeline, never()).cullRuns();
    }

    private static RunsPresenter presenterOver(final CullRunSummary... runs) {
        final Pipeline pipeline = pipeline();
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(runs)));
        final var presenter = new RunsPresenter(pipeline);
        presenter.refresh();
        return presenter;
    }

    private static Pipeline pipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.archivesFolder()).thenReturn(ARCHIVES);
        return pipeline;
    }

    private static CullRunSummary run(final String scope, final State state) {
        return new CullRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(state, List.of()),
                state == State.DAMAGED || state == State.COMPLETE ? null : new ShardTally(2, 2, 4),
                Instant.now());
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
        final var presenter = new RunsPresenter(pipeline);
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
