package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.TroubleshootView.Action;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.adapter.ui.TroubleshootView.Deed;
import photos.sluice.adapter.ui.TroubleshootView.Option;
import photos.sluice.adapter.ui.TroubleshootView.Problem;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.AnswerSource;
import photos.sluice.domain.cull.ChoiceAnswer;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootPresenterTest {

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    private static final Path PHOTO = Path.of("D:", "Sorted", "Photos", "2019", "06", "a.jpg");

    @Test
    void theScreenNamesTheRunItWasOpenedAgainst() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED, List.of()));

        assertThat(presenter.view().heading()).isEqualTo("Troubleshoot 2019");
    }

    @Test
    void itSaysItIsLookingUntilThePassHasAnswered() {
        final Pipeline pipeline = pipeline();
        final JobHandle<TroubleshootReport> pass = neverFinishes();
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

        presenter.open(PREP_DIR, "2019");

        assertThat(presenter.view().checking()).isNotNull();
        assertThat(presenter.view().summary()).isNull();
        assertThat(presenter.view().detail()).isNull();
    }

    @Test
    void aProblemTheRepairCouldNotSettleIsDrawnWithTheAnswersItStillHas() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log")))));

        final Problem problem = presenter.view().problems().getFirst();

        assertThat(problem.problem()).isEqualTo("A photo this sift wants to move is not where it was.");
        assertThat(problem.about()).isEqualTo(PHOTO.toString());
        assertThat(problem.options()).extracting(Option::answer)
                .containsExactly(Answer.RECHECK, Answer.SKIP_FILE);
    }

    @Test
    void aProblemNoAnswerCanSettleIsStillDrawn() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptShard("montage-002", "decisions-002.json"),
                        new Finding.MissingShard("montage-003", "decisions-003.json"))));

        assertThat(presenter.view().problems()).extracting(Problem::problem)
                .containsExactly("One sheet's answers cannot be read.",
                        "One sheet has not been judged yet.");
        assertThat(presenter.view().problems()).allSatisfy(problem ->
                assertThat(problem.options()).isEmpty());
        assertThat(presenter.view().nothingLeft()).isNotNull();
    }

    @Test
    void nothingToAnswerNamesJudgingAgainOnlyWhereAFindingBlamesASheet() {
        final TroubleshootPresenter withNoSheetToBlame = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.GroupSpansMultipleMontages("junk",
                                List.of("montage-002", "montage-003")),
                        new Finding.MissingShard("montage-004", "decisions-004.json"))));
        final TroubleshootPresenter withASheetToBlame = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingShard("montage-004", "decisions-004.json"),
                        new Finding.CorruptShard("montage-005", "decisions-005.json"))));

        assertThat(withNoSheetToBlame.view().nothingLeft())
                .isEqualTo("Nothing here can settle these, and no sheet can be judged again to "
                        + "clear them. Discarding the sift is the only thing that can be done.");
        assertThat(withASheetToBlame.view().nothingLeft())
                .isEqualTo("Those sheets are unusable and nothing here can settle them. Have them "
                        + "judged again, or discard the sift.");
    }

    @Test
    void aRunWithSomethingToAnswerSaysNothingAboutHavingNothingToAnswer() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002"))));

        assertThat(presenter.view().nothingLeft()).isNull();
    }

    @Test
    void aRunWithNothingLeftAtAllSaysSoOnce() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.READY, List.of()));

        assertThat(presenter.view().summary()).isEqualTo("Nothing left to put right.");
        assertThat(presenter.view().nothingLeft()).isNull();
    }

    @Test
    void answeringGoesThroughTheFacadeAsWhatThatFindingMeans() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002")));
        final TroubleshootPresenter presenter = opened(pipeline);

        pressOption(presenter, Answer.APPLY_SHEET_ANYWAY);

        verify(pipeline).answer(PREP_DIR,
                new ChoiceAnswer.ResolveCorruptSidecar("montage-002", CorruptSidecarResolution.APPLY_ANYWAY),
                AnswerSource.DESKTOP);
    }

    @Test
    void anOverlapIsAnsweredAgainstTheFileTheDecisionNamed() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.DecisionUnreviewableOverlap(
                        new Decision.Classification(PHOTO, "junk", "blurry"))));
        final TroubleshootPresenter presenter = opened(pipeline);

        pressOption(presenter, Answer.TREAT_AS_UNREVIEWABLE);

        verify(pipeline).answer(PREP_DIR,
                new ChoiceAnswer.ResolveOverlap(PHOTO, OverlapResolution.TREAT_AS_UNREVIEWABLE),
                AnswerSource.DESKTOP);
    }

    @Test
    void anAnsweredProblemCollapsesToWhatItSettled() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log"))));
        final TroubleshootPresenter presenter = opened(pipeline);
        // The answer resolves the finding, so the reading behind the redraw reports a clean run.
        when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));

        pressOption(presenter, Answer.SKIP_FILE);

        final Problem settled = presenter.view().problems().getFirst();
        assertThat(settled.outcome()).contains("go on without that photo");
        assertThat(settled.options()).isEmpty();
    }

    @Test
    void sayingThePhotoIsBackRecordsNothingAndLooksAgain() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log"))));
        final TroubleshootPresenter presenter = opened(pipeline);
        when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));

        pressOption(presenter, Answer.RECHECK);

        verify(pipeline, never()).answer(any(), any(), any());
        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("That is no longer a problem.");
        assertThat(presenter.view().problems()).isEmpty();
    }

    @Test
    void aPhotoStillMissingSaysSoRatherThanReportingItPutRight() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log"))));
        final TroubleshootPresenter presenter = opened(pipeline);

        pressOption(presenter, Answer.RECHECK);

        final RunLauncherView.Message said = requireNonNull(presenter.view().message());
        assertThat(said.text()).isEqualTo("It is still missing.");
        assertThat(said.refused()).isTrue();
        assertThat(presenter.view().problems()).hasSize(1);
    }

    @Test
    void aRowPressedAfterTheRunMovedIsReadAgainRatherThanAnswered() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002")));
        final TroubleshootPresenter presenter = opened(pipeline);
        final Problem stale = presenter.view().problems().getFirst();
        when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
        presenter.press(stale, stale.options().getFirst());

        presenter.press(stale, stale.options().getFirst());

        verify(pipeline).answer(any(), any(), any());
        assertThat(requireNonNull(presenter.view().message()).text())
                .contains("changed while you were looking at it");
    }

    // Answering the first of three shifts the other two down an index. A press drawn before that
    // still names the row the reader chose, which is the whole reason the row carries its finding.
    @Test
    void aRowAnsweredAfterItsNeighbourShiftedStillAnswersTheRowTheReaderChose() {
        final Path photoB = Path.of("D:", "Sorted", "Photos", "2019", "06", "b.jpg");
        final Path photoC = Path.of("D:", "Sorted", "Photos", "2019", "06", "c.jpg");
        final Finding.MissingSource findingA = new Finding.MissingSource(PHOTO, Path.of("a.log"));
        final Finding.MissingSource findingB = new Finding.MissingSource(photoB, Path.of("b.log"));
        final Finding.MissingSource findingC = new Finding.MissingSource(photoC, Path.of("c.log"));
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(findingA, findingB, findingC));
        final TroubleshootPresenter presenter = opened(pipeline);
        final List<Problem> drawn = presenter.view().problems();
        final Problem problemA = drawn.get(0);
        final Problem problemB = drawn.get(1);
        when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of(findingB, findingC)));
        skipFile(presenter, problemA);

        skipFile(presenter, problemB);

        verify(pipeline).answer(PREP_DIR, new ChoiceAnswer.SkipMissingSource(PHOTO), AnswerSource.DESKTOP);
        verify(pipeline).answer(PREP_DIR, new ChoiceAnswer.SkipMissingSource(photoB), AnswerSource.DESKTOP);
        verify(pipeline, never()).answer(PREP_DIR, new ChoiceAnswer.SkipMissingSource(photoC),
                AnswerSource.DESKTOP);
    }

    @Test
    void aRowWhoseFindingHasGoneIsReadAgainRatherThanAnswered() {
        final Finding.MissingSource findingA = new Finding.MissingSource(PHOTO, Path.of("a.log"));
        final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of(findingA));
        final TroubleshootPresenter presenter = opened(pipeline);
        final Problem problemA = presenter.view().problems().getFirst();
        // Something else settled it between the row being drawn and the press landing.
        when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
        skipFile(presenter, problemA);

        skipFile(presenter, problemA);

        verify(pipeline, times(1)).answer(any(), any(), any());
        assertThat(requireNonNull(presenter.view().message()).text())
                .contains("changed while you were looking at it");
    }

    @Test
    void finishingIsOfferedOnlyOnceNothingBlocksTheRun() {
        assertThat(opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002")))).view().actions())
                .extracting(Action::deed).containsExactly(Deed.DISCARD);
        assertThat(opened(pipelineReporting(State.READY, List.of())).view().actions())
                .extracting(Action::deed).containsExactly(Deed.DISCARD, Deed.FINISH);
    }

    @Test
    void finishingHandsTheRunToTheDashboardAndLeavesTheScreen() {
        final Pipeline pipeline = pipelineReporting(State.READY, List.of());
        final JobHandle<CullJobOutcome> job = neverFinishes();
        when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
        final TroubleshootPresenter presenter = opened(pipeline);
        final var left = new AtomicInteger();
        final var dashboard = new AtomicInteger();
        presenter.setOpenRuns(left::incrementAndGet);
        presenter.setOpenDashboard(dashboard::incrementAndGet);

        presenter.press(actionOf(presenter, Deed.FINISH));

        verify(pipeline).resume(PREP_DIR, false);
        assertThat(dashboard.get()).isEqualTo(1);
        assertThat(left.get()).isZero();
    }

    @Test
    void discardingRunsThroughTheFacadeAndLeavesTheScreenOnceItHasFinished() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of());
        final JobHandle<DiscardReport> job = reporting(new DiscardReport(PREP_DIR, 3));
        when(pipeline.discard(any())).thenReturn(job);
        final TroubleshootPresenter presenter = opened(pipeline);
        final var left = new AtomicInteger();
        presenter.setOpenRuns(left::incrementAndGet);

        presenter.press(actionOf(presenter, Deed.DISCARD));

        verify(pipeline).discard(PREP_DIR);
        assertThat(left.get()).isEqualTo(1);
    }

    // The screen stays put while the job runs, so a discard that fails has somewhere to report it.
    @Test
    void aDiscardStillRunningHasNotLeftTheScreenYet() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of());
        final JobHandle<DiscardReport> job = neverFinishes();
        when(pipeline.discard(any())).thenReturn(job);
        final TroubleshootPresenter presenter = opened(pipeline);
        final var left = new AtomicInteger();
        presenter.setOpenRuns(left::incrementAndGet);

        presenter.press(actionOf(presenter, Deed.DISCARD));

        assertThat(left.get()).isZero();
        assertThat(presenter.working()).isTrue();
    }

    // Almost everything that refuses a discard is raised inside the job rather than out of the
    // call that starts it. This is the arm carrying the refusals a reader actually meets.
    @Test
    void aDiscardRefusedInsideTheJobSaysWhyAndLeavesTheReaderWhereTheyAre() {
        // The path is rendered rather than spelled out, since its separator is the platform's.
        final Path outside = Path.of("D:", "old", "2019");
        final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of());
        final JobHandle<DiscardReport> job = failing(
                new Pipeline.RunOutsideWorkingRootException(outside));
        when(pipeline.discard(any())).thenReturn(job);
        final TroubleshootPresenter presenter = opened(pipeline);
        final var left = new AtomicInteger();
        presenter.setOpenRuns(left::incrementAndGet);

        presenter.press(actionOf(presenter, Deed.DISCARD));

        assertThat(left.get()).isZero();
        assertThat(presenter.working()).isFalse();
        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("That sift is at " + outside + ", which is not inside the folders Sluice "
                        + "is set up with now. Point your working folder back at the one holding "
                        + "it to work on it again.")
                .doesNotContain(Pipeline.RunOutsideWorkingRootException.class.getName());
    }

    @Test
    void aDiscardThatWasRefusedSaysWhyAndLeavesTheReaderWhereTheyAre() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of());
        when(pipeline.discard(any())).thenThrow(new JobInProgressException("Something else is running."));
        final TroubleshootPresenter presenter = opened(pipeline);
        final var left = new AtomicInteger();
        presenter.setOpenRuns(left::incrementAndGet);

        presenter.press(actionOf(presenter, Deed.DISCARD));

        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Something else is running.");
        assertThat(left.get()).isZero();
    }

    @Test
    void aPassThatCouldNotStartSaysWhyRatherThanDrawingAnEmptyScreen() {
        final Pipeline pipeline = pipeline();
        when(pipeline.troubleshoot(any()))
                .thenThrow(new JobInProgressException("Something else is running."));
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

        presenter.open(PREP_DIR, "2019");

        assertThat(presenter.view().checking()).isNull();
        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Something else is running.");
    }

    // The pass reaches a move-log reconcile, which refuses on a shard contract that does not
    // validate. This screen is the only place a reader can meet that refusal.
    @Test
    void aPassRefusedOnTheRunsOwnAnswersSaysSoInWordsRatherThanInTypes() {
        final Pipeline pipeline = pipeline();
        final JobHandle<TroubleshootReport> pass = failing(
                new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of()));
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

        presenter.open(PREP_DIR, "2019");

        assertThat(requireNonNull(presenter.view().message()).text())
                .isEqualTo("Sluice could not work on this sift, because the answers in it do not "
                        + "hold together.")
                .doesNotContain(ApplyException.class.getName());
    }

    @Test
    void theReportIsTheOneThePassWroteAndCopyingItSaysSo() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED, List.of()));

        assertThat(requireNonNull(presenter.view().detail()).text()).isEqualTo("the technical report");
        assertThat(presenter.detailCopied()).isFalse();
        assertThat(presenter.detail()).isEqualTo("the technical report");
        assertThat(presenter.detailCopied()).isTrue();
    }

    @Test
    void openingASecondRunKeepsNoneOfTheFirstOnesAnswers() {
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002")));
        final TroubleshootPresenter presenter = opened(pipeline);
        when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
        pressOption(presenter, Answer.SET_ASIDE_SHEET);

        presenter.open(Path.of("logs", "sift-prep", "2018"), "2018");

        assertThat(presenter.view().heading()).isEqualTo("Troubleshoot 2018");
        assertThat(presenter.view().problems()).isEmpty();
    }

    @Test
    void theSummaryCountsTheRepairsMadeAndWhatIsStillLeft() {
        final Pipeline pipeline = pipeline();
        final JobHandle<TroubleshootReport> pass = reporting(new TroubleshootReport(
                new PrepDirHealth(State.BLOCKED, List.of(new Finding.StrayShard("decisions-009.json"))),
                true, null, List.of("decisions-009.json -> montage-002"),
                new PrepDirHealth(State.BLOCKED, List.of()), "the technical report"));
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED,
                List.of(new Finding.CorruptSidecar("montage-002"))));
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));
        presenter.open(PREP_DIR, "2019");

        assertThat(presenter.view().summary()).isEqualTo("2 problems repaired, 1 left.");
    }

    // The two numbers are counted from different things, so neither implies the other. Each of the
    // four combinations is a line somebody reads.
    @Test
    void theSummaryReadsAsACountInEveryCombinationOfRepairedAndLeft() {
        assertThat(summaryOf(0, State.BLOCKED, List.of(new Finding.CorruptSidecar("montage-002"))))
                .isEqualTo("Nothing repaired, 1 left.");
        assertThat(summaryOf(1, State.READY, List.of())).isEqualTo("1 problem repaired, none left.");
        assertThat(summaryOf(0, State.READY, List.of())).isEqualTo("Nothing left to put right.");
    }

    // repairs is how many the pass reported putting right: a rebuilt index counts one, and each
    // stray shard it re-homed counts one more.
    private static String summaryOf(final int repairs, final State after, final List<Finding> open) {
        final Pipeline pipeline = pipeline();
        final List<String> strays = repairs == 0 ? List.of() : List.of("decisions-009.json -> montage-002");
        final JobHandle<TroubleshootReport> pass = reporting(new TroubleshootReport(
                new PrepDirHealth(State.BLOCKED, List.of()), false, null, strays,
                new PrepDirHealth(after, open), "the technical report"));
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        when(pipeline.cullRun(any())).thenReturn(summary(after, open));
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));
        presenter.open(PREP_DIR, "2019");
        return requireNonNull(presenter.view().summary());
    }

    private static void skipFile(final TroubleshootPresenter presenter, final Problem problem) {
        presenter.press(problem, problem.options().stream()
                .filter(option -> option.answer() == Answer.SKIP_FILE)
                .findFirst()
                .orElseThrow());
    }

    private static void pressOption(final TroubleshootPresenter presenter, final Answer answer) {
        final Problem problem = presenter.view().problems().getFirst();
        presenter.press(problem, problem.options().stream()
                .filter(option -> option.answer() == answer)
                .findFirst()
                .orElseThrow());
    }

    private static Action actionOf(final TroubleshootPresenter presenter, final Deed deed) {
        return presenter.view().actions().stream()
                .filter(action -> action.deed() == deed)
                .findFirst()
                .orElseThrow();
    }

    private static TroubleshootPresenter opened(final Pipeline pipeline) {
        final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));
        presenter.open(PREP_DIR, "2019");
        return presenter;
    }

    private static Pipeline pipelineReporting(final State state, final List<Finding> findings) {
        final Pipeline pipeline = pipeline();
        final var health = new PrepDirHealth(state, findings);
        // Built before the stubbing rather than inside it. Mockito reads a mock() call made while
        // a when() is open as the start of a second stubbing, and refuses the first.
        final JobHandle<TroubleshootReport> pass = reporting(new TroubleshootReport(
                health, false, null, List.of(), health, "the technical report"));
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        when(pipeline.cullRun(any())).thenReturn(summary(state, findings));
        return pipeline;
    }

    private static CullRunSummary summary(final State state, final List<Finding> findings) {
        return new CullRunSummary("2019", PREP_DIR, new PrepDirHealth(state, findings), null,
                Instant.now());
    }

    private static Pipeline pipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        return pipeline;
    }

    private static RunLauncherPresenter launcher(final Pipeline pipeline) {
        return new RunLauncherPresenter(pipeline, new FxProgressPort());
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> reporting(final T report) {
        final JobHandle<T> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(report));
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> failing(final Throwable failure) {
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
