package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.job.ShardTally;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// What only a built scene graph can be wrong about. Which cards the screen draws, what each one
// holds, and what a fold takes off the screen. What a state means is RunsPresenterTest's.
class RunsPaneTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void everyUnfinishedRunGetsACardCarryingWhatItCoversAndWhereItGotTo() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.WAITING)));

        assertThat(pane.lookup("#run-card-2019")).isNotNull();
        assertThat(textsIn(pane, "#run-card-2019"))
                .contains("2019", "Waiting for sheets",
                        "2 out of 4 sheets are judged and healthy. 2 are still missing.");
    }

    // Height, not managed: the section stays laid out at nothing so the fold has a height to
    // travel to. Waited for rather than read straight away, because that travel takes a moment.
    @Test
    void aFinishedRunTakesUpNoRoomUntilItsSectionIsOpened() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2018", State.COMPLETE)));
        assertThat(heightOfFinished(pane)).isZero();

        onFxThread(() -> fire(pane, "#runs-completed-toggle"));

        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> heightOfFinished(pane) > 0);
        assertThat(pane.lookup("#run-card-2018")).isNotNull();
    }

    @Test
    void theFoldSaysHowManyRunsAreInsideIt() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2018", State.COMPLETE),
                run("2017", State.COMPLETE)));

        assertThat(((Button) pane.lookup("#runs-completed-toggle")).getText())
                .isEqualTo("Finished runs (2)");
    }

    @Test
    void aRunReadyToFinishDrawsBothItsButtons() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.READY)));

        assertThat(pane.lookup("#run-continue-2019")).isNotNull();
        assertThat(pane.lookup("#run-discard-2019")).isNotNull();
    }

    @Test
    void aBlockedRunDrawsNoWayToCarryOn() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.BLOCKED)));

        assertThat(pane.lookup("#run-continue-2019")).isNull();
        assertThat(pane.lookup("#run-discard-2019")).isNotNull();
    }

    @Test
    void aWaitingRunDrawsItsFolderAndTheWayToCopyItsInstructions() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.WAITING)));

        assertThat(textsIn(pane, "#run-card-2019"))
                .contains(Path.of("logs", "sift-prep", "2019").toString());
        assertThat(pane.lookup("#run-copy-prompt")).isNotNull();
    }

    // A run with judged sheets and nothing blamed has stalled. Its follow-up is then the
    // instructions themselves, the facade refusing the redo it would otherwise do first.
    @Test
    void copyingTheInstructionsPutsThemOnTheClipboard() throws Exception {
        final Pipeline pipeline = stalledPipeline();
        when(pipeline.launchPromptFor(any())).thenReturn("Sift the photo sheets in ...");
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-copy-prompt"));

        assertThat(onFxThread(() -> Clipboard.getSystemClipboard().getString()))
                .isEqualTo("Sift the photo sheets in ...");
    }

    @Test
    void instructionsThatCouldNotBeWrittenLeaveTheButtonAloneAndSayWhy() throws Exception {
        final Pipeline pipeline = stalledPipeline();
        when(pipeline.launchPromptFor(any())).thenThrow(new IllegalStateException("nope"));
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-copy-prompt"));

        assertThat(((Button) pane.lookup("#run-copy-prompt")).getText()).isNotEqualTo("Copied");
        assertThat(pane.lookup("#runs-message").isManaged()).isTrue();
    }

    @Test
    void askingForRejectedAnswersAgainPutsTheInstructionsOnTheClipboard() throws Exception {
        final Pipeline pipeline = rejectedAnswersPipeline();
        when(pipeline.redoRejectedAnswers(any())).thenReturn("write them again");
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-redo-2019"));

        assertThat(onFxThread(() -> Clipboard.getSystemClipboard().getString())).isEqualTo("write them again");
    }

    // The only press on this screen that reports on itself. A follow-up redraws instead, and the
    // card it would have said it on is gone by then. A run nothing has answered gets no follow-up,
    // so this is the press that leaves the run where it was.
    @Test
    void copyingInstructionsThatLeaveTheRunAloneSaysSoOnTheButton() throws Exception {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(new CullRunSummary("2019",
                Path.of("logs", "sift-prep", "2019"), new PrepDirHealth(State.WAITING, List.of()),
                new ShardTally(0, 0, 4), Instant.now()))));
        when(pipeline.launchPromptFor(any())).thenReturn("Sift the photo sheets in ...");
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-copy-prompt"));

        assertThat(((Button) pane.lookup("#run-copy-prompt")).getText()).isEqualTo("Copied");
    }

    // A run whose records went bad between the card being drawn and the press.
    @Test
    void aRefusedPressLeavesTheClipboardAloneAndSaysWhy() throws Exception {
        final Pipeline pipeline = rejectedAnswersPipeline();
        when(pipeline.redoRejectedAnswers(any()))
                .thenThrow(new MalformedPrepJsonException("index.json will not parse",
                        new IllegalStateException("unexpected end of input")));
        final var untouched = new ClipboardContent();
        untouched.putString("untouched");
        onFxThread(() -> Clipboard.getSystemClipboard().setContent(untouched));
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-redo-2019"));

        assertThat(onFxThread(() -> Clipboard.getSystemClipboard().getString())).isEqualTo("untouched");
        assertThat(pane.lookup("#runs-message").isManaged()).isTrue();
    }

    @Test
    void turningAutoApplyOnArmsThatRunsWatch() throws Exception {
        final Pipeline pipeline = waitingPipeline();
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        onFxThread(() -> select(pane, "#run-auto-apply-2019"));

        verify(pipeline).startWatching(Path.of("logs", "sift-prep", "2019"));
    }

    @Test
    void aWaitingRunOnAProviderThatJudgesForItselfDrawsNoToggleAndNoInstructions() throws Exception {
        final Pipeline pipeline = waitingPipeline();
        when(pipeline.configuredProviderSpends()).thenReturn(true);
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        assertThat(pane.lookup("#run-auto-apply-2019")).isNull();
        assertThat(pane.lookup("#run-copy-prompt")).isNull();
        assertThat(pane.lookup("#run-waive-missing-2019")).isNull();
        assertThat(textsIn(pane, "#run-card-2019"))
                .contains(Path.of("logs", "sift-prep", "2019").toString());
    }

    @Test
    void aRunPastWaitingDrawsNoneOfThat() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.READY)));

        assertThat(pane.lookup("#run-copy-prompt")).isNull();
        assertThat(pane.lookup("#run-waive-missing-2019")).isNull();
    }

    @Test
    void clearingIsDeadWithNothingFinishedToClear() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.WAITING)));

        assertThat(pane.lookup("#runs-clear-completed").isDisabled()).isTrue();
    }

    @Test
    void anInstallWithNoRunsSaysSoRatherThanDrawingAnEmptyList() throws Exception {
        final Parent pane = onFxThread(RunsPaneTest::built);

        assertThat(pane.lookup("#runs-nothing-yet").isManaged()).isTrue();
        assertThat(pane.lookup("#runs-unreadable").isManaged()).isFalse();
        assertThat(((Label) pane.lookup("#runs-nothing-yet")).getText()).contains("No sifts");
    }

    @Test
    void aFolderThatCouldNotBeReadSaysSoInsteadOfTheNoRunsLine() throws Exception {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Unlistable(Path.of("logs", "sift-prep")));
        final Parent pane = onFxThread(() -> built(runsPresenter(pipeline)));

        assertThat(pane.lookup("#runs-unreadable").isManaged()).isTrue();
        assertThat(pane.lookup("#runs-nothing-yet").isManaged()).isFalse();
    }

    @Test
    void aScreenWithNothingToReportKeepsNoRoomForTheLineThatWouldReportIt() throws Exception {
        final Parent pane = onFxThread(() -> built(run("2019", State.WAITING)));

        assertThat(pane.lookup("#runs-message").isManaged()).isFalse();
    }

    // The box's own resized height, not its bounds. A box holds its children at their full size
    // whatever ceiling it is under, so its bounds answer for them rather than for the room it takes.
    private static double heightOfFinished(final Parent pane) {
        return ((Region) pane.lookup("#runs-completed-cards")).getHeight();
    }

    private static List<String> textsIn(final Parent pane, final String id) {
        return pane.lookup(id).lookupAll(".label").stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    private static Node fire(final Parent pane, final String id) {
        final Node found = pane.lookup(id);
        ((Button) found).fire();
        return found;
    }

    private static RunsPresenter runsPresenter(final Pipeline pipeline) {
        return new RunsPresenter(pipeline, new RunLauncherPresenter(pipeline, new FxProgressPort()));
    }

    private static Pipeline waitingPipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns())
                .thenReturn(new CullRuns.Listed(List.of(run("2019", State.WAITING))));
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        return pipeline;
    }

    // A check box's own fire() flips it and then raises the action, so this is a press rather than
    // a value written past the control.
    private static Node select(final Parent pane, final String id) {
        final var box = (CheckBox) pane.lookup(id);
        box.fire();
        return box;
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

    private static Pipeline stalledPipeline() {
        final Pipeline pipeline = waitingPipeline();
        when(pipeline.redoRejectedAnswers(any()))
                .thenThrow(new Pipeline.NothingToRedoException(Path.of("logs", "sift-prep", "2019")));
        return pipeline;
    }

    private static Pipeline rejectedAnswersPipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(run("2019", State.BLOCKED,
                List.of(new Finding.PhotosNotJudged("montage-001", List.of("IMG_1.jpg")))))));
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        return pipeline;
    }

    private static Parent built(final CullRunSummary... runs) {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(runs)));
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        return built(runsPresenter(pipeline));
    }

    // Read before the pane is built, as well as by the pane's own background read. So no assertion
    // here depends on which of the two lands first.
    private static Parent built(final RunsPresenter presenter) {
        presenter.refresh();
        final var page = (Parent) RunsPane.pane(presenter, () -> { });
        final var scene = new Scene(new StackPane(page), 900, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(RunsPaneTest.class.getResource("/ui/sluice.css"),
                        "the app stylesheet is missing from the test classpath").toExternalForm());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return page;
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }
}
