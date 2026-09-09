package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.TroubleshootView.Action;
import photos.sluice.adapter.ui.TroubleshootView.Answer;
import photos.sluice.adapter.ui.TroubleshootView.Deed;
import photos.sluice.adapter.ui.TroubleshootView.Option;
import photos.sluice.adapter.ui.TroubleshootView.Problem;
import photos.sluice.adapter.ui.TroubleshootView.ProblemStack;
import photos.sluice.application.port.in.CullJobOutcome;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.PathsMisconfiguredException;
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
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootPresenterTest {

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    private static final Path PHOTO = Path.of("D:", "Sorted", "Photos", "2019", "06", "a.jpg");

    private static final Path SORTED = Path.of("D:", "Sorted");

    @Test
    void theScreenNamesTheRunItWasOpenedAgainst() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED, List.of()));

        assertThat(presenter.view().heading()).isEqualTo("Troubleshoot 2019");
    }

    @Test
    void drawingTheScreenAgainIsNotAReport() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("a.log")))));

        final int drawn = presenter.view().reportNumber();

        assertThat(presenter.view().reportNumber()).isEqualTo(drawn);
    }

    @Test
    void aProblemTheRepairCouldNotSettleIsDrawnWithTheAnswersItStillHas() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log")))));

        final Problem problem = rows(presenter).getFirst();

        assertThat(problem.problem()).isEqualTo("A photo this sift wants to move is not where it was.");
        assertThat(problem.about()).isEqualTo(PHOTO.toString());
        assertThat(problem.options()).extracting(Option::answer)
                .containsExactly(Answer.RECHECK, Answer.SKIP_FILE);
    }

    @Test
    void severalFaultsOfOneKindAreCountedInOneHeadingWithTheFilesUnderIt() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.SourceOutsideSorted(PHOTO, SORTED),
                        new Finding.SourceOutsideSorted(Path.of("b.jpg"), SORTED),
                        new Finding.SourceOutsideSorted(Path.of("c.jpg"), SORTED))));

        final ProblemStack stacked = presenter.view().problems().getFirst();

        assertThat(presenter.view().problems()).hasSize(1);
        assertThat(stacked.heading())
                .isEqualTo("3 photos this sift wants to move are not under the Sorted folder currently saved.");
        assertThat(stacked.rows()).extracting(Problem::problem).containsOnlyNulls();
        assertThat(stacked.rows()).extracting(Problem::about)
                .containsExactly(PHOTO.toString(), "b.jpg", "c.jpg");
    }

    @Test
    void aFaultFoundOnceKeepsItsOwnSentenceAndTakesNoHeading() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.SourceOutsideSorted(PHOTO, SORTED))));

        final ProblemStack alone = presenter.view().problems().getFirst();

        assertThat(alone.heading()).isNull();
        assertThat(alone.rows()).extracting(Problem::problem)
                .containsExactly("A photo this sift wants to move is not under the Sorted folder currently saved.");
    }

    @Test
    void kindsKeepTheOrderTheFirstOfEachWasFoundIn() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingShard("montage-002", "decisions-002.json"),
                        new Finding.CorruptShard("montage-003", "decisions-003.json"),
                        new Finding.MissingShard("montage-004", "decisions-004.json"))));

        assertThat(presenter.view().problems()).extracting(ProblemStack::heading)
                .containsExactly("2 sheets have not been judged yet.", null);
    }

    @Test
    void everyRowUnderAHeadingKeepsItsOwnAnswers() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("a.log")),
                        new Finding.MissingSource(Path.of("b.jpg"), Path.of("b.log")))));

        final ProblemStack stacked = presenter.view().problems().getFirst();

        assertThat(stacked.rows()).hasSize(2);
        assertThat(stacked.rows()).allSatisfy(problem ->
                assertThat(problem.options()).extracting(Option::answer)
                        .containsExactly(Answer.RECHECK, Answer.SKIP_FILE));
        assertThat(rows(presenter)).extracting(Problem::id).doesNotHaveDuplicates();
    }

    @Test
    void answeringOneOfAPairLeavesTheSurvivorSpeakingForItself() {
        final Finding.MissingSource second = new Finding.MissingSource(Path.of("b.jpg"),
                Path.of("b.log"));
        final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                List.of(new Finding.MissingSource(PHOTO, Path.of("a.log")), second));
        final TroubleshootPresenter presenter = opened(pipeline);
        assertThat(presenter.view().problems().getFirst().heading())
                .isEqualTo("2 photos this sift wants to move are not where they were.");
        when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of(second)));

        pressOption(presenter, Answer.SKIP_FILE);

        assertThat(presenter.view().problems().getFirst().heading()).isNull();
        assertThat(rows(presenter)).extracting(Problem::problem)
                .containsExactly("A photo this sift wants to move is not where it was.");
    }

    // Nothing produces two of either kind today, so the fixture is built rather than observed.
    @Test
    void aKindWithNoPluralDrawsARowEachRatherThanACountedHeading() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptIndex(Path.of("a", "index.json")),
                        new Finding.CorruptIndex(Path.of("b", "index.json")))));

        final ProblemStack neither = presenter.view().problems().getFirst();

        assertThat(neither.heading()).isNull();
        assertThat(neither.rows()).extracting(Problem::problem)
                .containsExactly("The record of what this sift covers is damaged.",
                        "The record of what this sift covers is damaged.");
    }

    @Test
    void aProblemNoAnswerCanSettleIsStillDrawn() {
        final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED,
                List.of(new Finding.CorruptShard("montage-002", "decisions-002.json"),
                        new Finding.MissingShard("montage-003", "decisions-003.json"))));

        assertThat(rows(presenter)).extracting(Problem::problem)
                .containsExactly("One sheet's answers cannot be read.",
                        "One sheet has not been judged yet.");
        assertThat(rows(presenter)).allSatisfy(problem ->
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

    @Nested
    class Answering {

        @Test
        void goesThroughTheFacadeAsWhatThatFindingMeans() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.CorruptSidecar("montage-002")));
            final TroubleshootPresenter presenter = opened(pipeline);

            pressOption(presenter, Answer.APPLY_SHEET_ANYWAY);

            verify(pipeline).answer(PREP_DIR,
                    new ChoiceAnswer.ResolveCorruptSidecar("montage-002", CorruptSidecarResolution.APPLY_ANYWAY),
                    AnswerSource.DESKTOP);
        }

        @Test
        void anOverlapGoesAgainstTheFileTheDecisionNamed() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.VerdictUnreviewableOverlap(
                            new Decision.Classification(PHOTO, "junk", "blurry"))));
            final TroubleshootPresenter presenter = opened(pipeline);

            pressOption(presenter, Answer.TREAT_AS_UNREVIEWABLE);

            verify(pipeline).answer(PREP_DIR,
                    new ChoiceAnswer.ResolveOverlap(PHOTO, OverlapResolution.TREAT_AS_UNREVIEWABLE),
                    AnswerSource.DESKTOP);
        }

        @Test
        void aProblemLeavesTheListAndSaysWhatWasSettledAtTheTop() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("move-records.log"))));
            final TroubleshootPresenter presenter = opened(pipeline);
            when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));

            pressOption(presenter, Answer.SKIP_FILE);

            assertThat(rows(presenter)).isEmpty();
            final RunLauncherView.Message said = requireNonNull(presenter.view().message());
            assertThat(said.text()).contains("go on without that photo");
            assertThat(said.refused()).isFalse();
        }

        @Test
        void doingItTwiceReportsTwiceEvenWhenBothSayTheSameThing() {
            final Finding.MissingSource second = new Finding.MissingSource(Path.of("b.jpg"),
                    Path.of("b.log"));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("a.log")), second));
            final TroubleshootPresenter presenter = opened(pipeline);
            when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of(second)));
            pressOption(presenter, Answer.SKIP_FILE);
            final int afterTheFirst = presenter.view().reportNumber();

            pressOption(presenter, Answer.SKIP_FILE);

            assertThat(presenter.view().message()).isEqualTo(
                    new RunLauncherView.Message("The sift will go on without that photo. Nothing has "
                            + "touched it, so a later sift can still pick it up.", false));
            assertThat(presenter.view().reportNumber()).isGreaterThan(afterTheFirst);
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
        void aRowPressedAfterTheRunMovedIsReadAgainInstead() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.CorruptSidecar("montage-002")));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem stale = rows(presenter).getFirst();
            when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
            presenter.press(stale, stale.options().getFirst());

            presenter.press(stale, stale.options().getFirst());

            verify(pipeline).answer(any(), any(), any());
            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("changed while you were looking at it");
        }

        // Answering the first of three shifts the other two down an index.
        @Test
        void aRowPressedAfterItsNeighbourShiftedStillTakesTheRowTheReaderChose() {
            final Path photoB = Path.of("D:", "Sorted", "Photos", "2019", "06", "b.jpg");
            final Path photoC = Path.of("D:", "Sorted", "Photos", "2019", "06", "c.jpg");
            final Finding.MissingSource findingA = new Finding.MissingSource(PHOTO, Path.of("a.log"));
            final Finding.MissingSource findingB = new Finding.MissingSource(photoB, Path.of("b.log"));
            final Finding.MissingSource findingC = new Finding.MissingSource(photoC, Path.of("c.log"));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(findingA, findingB, findingC));
            final TroubleshootPresenter presenter = opened(pipeline);
            final List<Problem> drawn = rows(presenter);
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
        void aRowWhoseFindingHasGoneIsReadAgainInstead() {
            final Finding.MissingSource findingA = new Finding.MissingSource(PHOTO, Path.of("a.log"));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of(findingA));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem problemA = rows(presenter).getFirst();
            // Something else settled it between the row being drawn and the press landing.
            when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
            skipFile(presenter, problemA);

            skipFile(presenter, problemA);

            verify(pipeline, times(1)).answer(any(), any(), any());
            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("changed while you were looking at it");
        }
    }

    @Nested
    class ReadingTheRun {

        @Test
        void neitherLookingAgainNorBeingOvertakenSpeaksOverOneThatFailed() {
            final Finding.MissingSource missing = new Finding.MissingSource(PHOTO, Path.of("a.log"));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of(missing));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem row = rows(presenter).getFirst();
            when(pipeline.cullRun(any())).thenThrow(new IllegalStateException("the folder is gone"));

            presenter.press(row, option(row, Answer.RECHECK));

            final RunLauncherView.Message afterLooking =
                    requireNonNull(presenter.view().message());
            assertThat(afterLooking.refused()).isTrue();
            assertThat(afterLooking.text()).doesNotContain("no longer a problem");

            presenter.press(row, option(row, Answer.SKIP_FILE));

            final RunLauncherView.Message afterPressing =
                    requireNonNull(presenter.view().message());
            assertThat(afterPressing.refused()).isTrue();
            assertThat(afterPressing.text()).doesNotContain("read again");
        }

        // A roots refusal, that being the only thing cullRun throws.
        @Test
        void aRunNobodyCouldReadSaysSoWhereTheCountsWouldGo() {
            final var roots = new PathsMisconfiguredException(
                    List.of(new NotADirectory(PathRole.WORKING_ROOT, Path.of("D:", "gone"))));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("a.log"))));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem row = rows(presenter).getFirst();
            when(pipeline.cullRun(any())).thenThrow(roots);

            presenter.press(row, option(row, Answer.SKIP_FILE));

            assertThat(rows(presenter)).isEmpty();
            assertThat(presenter.view().summary()).isEqualTo(RunRefusals.refuseSentence(roots));
            assertThat(presenter.view().summary())
                    .isEqualTo(requireNonNull(presenter.view().message()).text());
        }

        @Test
        void aFailedOneTakesFinishOffTheScreen() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("a.log"))));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem row = rows(presenter).getFirst();
            // The answer clears the last finding, so the run reads as ready and offers Finish. The row
            // the reader is still looking at is now stale, and pressing it again reads once more.
            doReturn(summary(State.READY, List.of())).when(pipeline).cullRun(any());
            presenter.press(row, option(row, Answer.SKIP_FILE));
            assertThat(presenter.view().actions()).extracting(Action::id)
                    .contains("troubleshoot-finish");
            doThrow(new PathsMisconfiguredException(
                    List.of(new NotADirectory(PathRole.WORKING_ROOT, Path.of("D:", "gone")))))
                    .when(pipeline).cullRun(any());

            presenter.press(row, option(row, Answer.SKIP_FILE));

            assertThat(presenter.view().actions()).extracting(Action::id)
                    .doesNotContain("troubleshoot-finish");
        }

        // The press is driven from inside the draw rather than from a second thread. A second thread
        // would have to hit a window a few instructions wide, and would pass by missing it. The call to
        // archivesFolder is made while the buttons are being built. That is after the summary above
        // them is worked out, so it lands in that window every time.
        @Test
        void oneDrawTakesEveryPartOfOneReadingRatherThanTheLatestOfEach() {
            final var roots = new PathsMisconfiguredException(
                    List.of(new NotADirectory(PathRole.WORKING_ROOT, Path.of("D:", "gone"))));
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("a.log"))));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem row = rows(presenter).getFirst();
            doThrow(roots).doReturn(summary(State.READY, List.of())).when(pipeline).cullRun(any());
            presenter.press(row, option(row, Answer.SKIP_FILE));
            final var pressedMidDraw = new AtomicBoolean();
            when(pipeline.archivesFolder()).thenAnswer(_ -> {
                if (pressedMidDraw.compareAndSet(false, true)) {
                    presenter.press(row, option(row, Answer.SKIP_FILE));
                }
                return Path.of("logs", "archives");
            });

            final TroubleshootView drawn = presenter.view();

            // Without it the press never happened and the rest of this passes for the wrong reason.
            assertThat(pressedMidDraw).isTrue();
            assertThat(drawn.summary()).isEqualTo(RunRefusals.refuseSentence(roots));
            assertThat(drawn.actions()).extracting(Action::id).doesNotContain("troubleshoot-finish");
        }

        @Test
        void oneThatLandsTakesTheCouldNotReadLineBack() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.MissingSource(PHOTO, Path.of("a.log"))));
            final TroubleshootPresenter presenter = opened(pipeline);
            final Problem row = rows(presenter).getFirst();
            final var roots = new PathsMisconfiguredException(
                    List.of(new NotADirectory(PathRole.WORKING_ROOT, Path.of("D:", "gone"))));
            when(pipeline.cullRun(any())).thenThrow(roots);
            presenter.press(row, option(row, Answer.RECHECK));
            assertThat(presenter.view().summary()).isEqualTo(RunRefusals.refuseSentence(roots));
            // doReturn, because when(...) would call the still-throwing stub while setting up the
            // replacement for it.
            doReturn(summary(State.READY, List.of())).when(pipeline).cullRun(any());

            presenter.press(row, option(row, Answer.RECHECK));

            assertThat(presenter.view().summary()).isNotEqualTo(RunRefusals.refuseSentence(roots));
        }

        // Two failures at once, so the reader has one problem rather than two.
        @Test
        void aFailedOneOutranksThePassThatCouldNotRun() {
            final Pipeline pipeline = pipeline();
            final JobHandle<TroubleshootReport> refused =
                    failing(new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
            when(pipeline.troubleshoot(any())).thenReturn(refused);
            final var roots = new PathsMisconfiguredException(
                    List.of(new NotADirectory(PathRole.WORKING_ROOT, Path.of("D:", "gone"))));
            when(pipeline.cullRun(any())).thenThrow(roots);
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

            presenter.open(PREP_DIR, "2019");

            assertThat(presenter.view().summary()).isEqualTo(RunRefusals.refuseSentence(roots));
            assertThat(presenter.view().summary())
                    .isEqualTo(requireNonNull(presenter.view().message()).text());
        }
    }

    @Nested
    class ThePass {

        @Test
        void theScreenSaysItIsLookingUntilItHasAnswered() {
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
        void oneThatCouldNotStartSaysWhyRatherThanDrawingAnEmptyScreen() {
            final Pipeline pipeline = pipeline();
            when(pipeline.troubleshoot(any()))
                    .thenThrow(new JobInProgressException("Something else is running."));
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

            presenter.open(PREP_DIR, "2019");

            assertThat(presenter.view().checking()).isNull();
            assertThat(requireNonNull(presenter.view().message()).text())
                    .isEqualTo("Something else is running.");
            // The banner leaves on its own, so without this line the screen it leaves behind is empty.
            assertThat(presenter.view().summary()).isEqualTo("Something else is running.");
        }

        // The pass reaches a move-log reconcile, which refuses on a shard contract that does not
        // validate.
        @Test
        void oneRefusedOnTheRunsOwnAnswersSaysSoInWordsRatherThanInTypes() {
            final Pipeline pipeline = pipeline();
            final JobHandle<TroubleshootReport> pass = failing(
                    new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
            when(pipeline.troubleshoot(any())).thenReturn(pass);
            when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of()));
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

            presenter.open(PREP_DIR, "2019");

            assertThat(requireNonNull(presenter.view().message()).text())
                    .isEqualTo("This sift's answers do not hold together, so nothing was moved.")
                    .doesNotContain(ApplyException.class.getName());
        }

        @Test
        void theReportIsTheOneItWroteAndCopyingItSaysSo() {
            final TroubleshootPresenter presenter = opened(pipelineReporting(State.BLOCKED, List.of()));

            assertThat(requireNonNull(presenter.view().detail()).text()).isEqualTo("the technical report");
            assertThat(presenter.detailCopied()).isFalse();
            assertThat(presenter.detail()).isEqualTo("the technical report");
            assertThat(presenter.detailCopied()).isTrue();
        }

        @Test
        void oneThatCouldNotRunSaysSoWhereTheCountsWouldGo() {
            final Pipeline pipeline = pipeline();
            final JobHandle<TroubleshootReport> refused =
                    failing(new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
            when(pipeline.troubleshoot(any())).thenReturn(refused);
            when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED,
                    List.of(new Finding.CorruptSidecar("montage-002"))));
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));

            presenter.open(PREP_DIR, "2019");

            assertThat(presenter.view().summary())
                    .isEqualTo("This sift's answers do not hold together, so nothing was moved.");
            assertThat(presenter.view().summary())
                    .isEqualTo(requireNonNull(presenter.view().message()).text());
            assertThat(rows(presenter)).hasSize(1);
        }

        @Test
        void oneThatRanTakesTheCouldNotRunLineBack() {
            final Pipeline pipeline = pipeline();
            final JobHandle<TroubleshootReport> refused =
                    failing(new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
            when(pipeline.troubleshoot(any())).thenReturn(refused);
            when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));
            presenter.open(PREP_DIR, "2019");
            assertThat(presenter.view().summary()).contains("do not hold together");
            final var health = new PrepDirHealth(State.READY, List.of());
            final JobHandle<TroubleshootReport> ran = reporting(new TroubleshootReport(
                    health, false, null, List.of(), health, "the technical report"));
            doReturn(ran).when(pipeline).troubleshoot(any());

            presenter.open(PREP_DIR, "2019");

            assertThat(presenter.view().summary()).isEqualTo("Nothing left to put right.");
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

        // The two numbers are counted from different things, so neither implies the other.
        @Test
        void theSummaryReadsAsACountInEveryCombinationOfRepairedAndLeft() {
            assertThat(summaryOf(0, State.BLOCKED, List.of(new Finding.CorruptSidecar("montage-002"))))
                    .isEqualTo("Nothing repaired, 1 left.");
            assertThat(summaryOf(1, State.READY, List.of())).isEqualTo("1 problem repaired, none left.");
            assertThat(summaryOf(0, State.READY, List.of())).isEqualTo("Nothing left to put right.");
        }
    }

    @Nested
    class Finishing {

        @Test
        void isOfferedOnlyOnceNothingBlocksTheRun() {
            assertThat(opened(pipelineReporting(State.BLOCKED,
                    List.of(new Finding.CorruptSidecar("montage-002")))).view().actions())
                    .extracting(Action::deed).containsExactly(Deed.DISCARD);
            assertThat(opened(pipelineReporting(State.READY, List.of())).view().actions())
                    .extracting(Action::deed).containsExactly(Deed.DISCARD, Deed.FINISH);
        }

        @Test
        void handsTheRunToTheDashboardAndLeavesTheScreen() {
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
        void oneThatCouldNotStartAnythingSaysSoAndStaysOnThisScreen() {
            final Pipeline pipeline = pipelineReporting(State.READY, List.of());
            final JobHandle<CullJobOutcome> job = neverFinishes();
            when(pipeline.resume(any(), anyBoolean())).thenReturn(job);
            final TroubleshootPresenter presenter = opened(pipeline);
            final var dashboard = new AtomicInteger();
            presenter.setOpenDashboard(dashboard::incrementAndGet);
            // The first press takes the single job slot, so the second one has nothing to start.
            presenter.press(actionOf(presenter, Deed.FINISH));

            presenter.press(actionOf(presenter, Deed.FINISH));

            assertThat(requireNonNull(presenter.view().message()).text())
                    .contains("only one job runs at a time")
                    .contains("was not continued");
            assertThat(dashboard.get()).isEqualTo(1);
            verify(pipeline, times(1)).resume(any(), anyBoolean());
        }
    }

    @Nested
    class Discarding {

        @Test
        void runsThroughTheFacadeAndLeavesTheScreenOnceItHasFinished() {
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

        @Test
        void oneStillRunningHasNotLeftTheScreenYet() {
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
        // call that starts it.
        @Test
        void oneRefusedInsideTheJobSaysWhyAndLeavesTheReaderWhereTheyAre() {
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
                    .isEqualTo("This sift is at " + outside + ", which is not inside the folders "
                            + "currently saved. Point your working folder back at the one holding "
                            + "it to work on it again.")
                    .doesNotContain(Pipeline.RunOutsideWorkingRootException.class.getName());
        }

        @Test
        void oneRefusedBeforeItStartedSaysWhyAndLeavesTheReaderWhereTheyAre() {
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
    }

    @Nested
    class OpeningASecondRun {

        @Test
        void keepsNoneOfTheFirstOnesAnswers() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED,
                    List.of(new Finding.CorruptSidecar("montage-002")));
            final TroubleshootPresenter presenter = opened(pipeline);
            when(pipeline.cullRun(any())).thenReturn(summary(State.READY, List.of()));
            pressOption(presenter, Answer.SET_ASIDE_SHEET);

            presenter.open(Path.of("logs", "sift-prep", "2018"), "2018");

            assertThat(presenter.view().heading()).isEqualTo("Troubleshoot 2018");
            assertThat(presenter.view().problems()).isEmpty();
            assertThat(presenter.view().message()).isNull();
        }

        // The second pass never finishes, which is the only window in which the clearing is visible.
        // Let it report and it overwrites what the first one left, so the screen looks right either way.
        @Test
        void keepsNoneOfTheFirstOnesReport() {
            final Pipeline pipeline = pipelineReporting(State.BLOCKED, List.of());
            final TroubleshootPresenter presenter = opened(pipeline);
            presenter.detail();
            assertThat(presenter.view().detail()).isNotNull();
            assertThat(presenter.detailCopied()).isTrue();
            final JobHandle<TroubleshootReport> running = neverFinishes();
            when(pipeline.troubleshoot(any())).thenReturn(running);

            presenter.open(Path.of("logs", "sift-prep", "2018"), "2018");

            assertThat(presenter.view().detail()).isNull();
            assertThat(presenter.detailCopied()).isFalse();
        }

        // The second pass never finishes, so nothing but open() can have cleared the first one's
        // refusal. Let it finish and its own success arm clears the field instead.
        @Test
        void keepsNoneOfTheFirstOnesRefusal() {
            final Pipeline pipeline = pipeline();
            final JobHandle<TroubleshootReport> refused =
                    failing(new ApplyException("montage-004[#3]: missing 'reason'", List.of()));
            when(pipeline.troubleshoot(any())).thenReturn(refused);
            when(pipeline.cullRun(any())).thenReturn(summary(State.BLOCKED, List.of()));
            final var presenter = new TroubleshootPresenter(pipeline, launcher(pipeline));
            presenter.open(PREP_DIR, "2019");
            assertThat(presenter.view().summary()).contains("do not hold together");
            final JobHandle<TroubleshootReport> running = neverFinishes();
            doReturn(running).when(pipeline).troubleshoot(any());

            presenter.open(Path.of("logs", "sift-prep", "2018"), "2018");

            assertThat(presenter.view().summary()).isNull();
            assertThat(presenter.view().checking()).isNotNull();
        }
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

    private static List<Problem> rows(final TroubleshootPresenter presenter) {
        return presenter.view().problems().stream()
                .flatMap(same -> same.rows().stream())
                .toList();
    }

    private static Option option(final Problem problem, final Answer answer) {
        return problem.options().stream()
                .filter(offered -> offered.answer() == answer)
                .findFirst()
                .orElseThrow();
    }

    private static void pressOption(final TroubleshootPresenter presenter, final Answer answer) {
        final Problem problem = rows(presenter).getFirst();
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
