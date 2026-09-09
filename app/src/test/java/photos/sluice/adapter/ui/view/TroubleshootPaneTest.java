package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.TroubleshootPresenter;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.cull.TroubleshootReport;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// What only a built scene graph can be wrong about. Which rows the screen draws, what each carries,
// and what the fold takes off the screen. What a state means is TroubleshootPresenterTest's.
class TroubleshootPaneTest {

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    private static final Path PHOTO = Path.of("D:", "Sorted", "Photos", "2019", "06", "a.jpg");

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void everyProblemGetsARowNamingItAndWhatItHappenedTo() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingSource(PHOTO, Path.of("move-records.log")),
                new Finding.CorruptSidecar("montage-002")));

        assertThat(pane.lookup("#troubleshoot-problem-0")).isNotNull();
        assertThat(pane.lookup("#troubleshoot-problem-1")).isNotNull();
        assertThat(textsIn(pane, "#troubleshoot-problem-0"))
                .contains("A photo this sift wants to move is not where it was.", PHOTO.toString());
    }

    @Test
    void faultsOfOneKindAreDrawnUnderOneCountedHeading() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingShard("montage-002", "decisions-002.json"),
                new Finding.MissingShard("montage-003", "decisions-003.json")));

        assertThat(textsIn(pane, ".troubleshoot-stack-heading"))
                .containsExactly("2 sheets have not been judged yet.");
        assertThat(textsIn(pane, "#troubleshoot-problem-0")).containsExactly("Sheet 2");
        assertThat(textsIn(pane, "#troubleshoot-problem-1")).containsExactly("Sheet 3");
    }

    @Test
    void faultsOfOneKindWithNothingToAnswerShareOneCard() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingShard("montage-002", "decisions-002.json"),
                new Finding.MissingShard("montage-003", "decisions-003.json")));

        final Parent problems = (Parent) pane.lookup("#troubleshoot-problems");
        assertThat(problems.lookupAll(".card")).hasSize(1);
        final Node card = problems.lookup(".card");
        assertThat(card.lookup("#troubleshoot-problem-0")).isNotNull();
        assertThat(card.lookup("#troubleshoot-problem-1")).isNotNull();
    }

    // The buttons are what a reader aims at, so two sets inside one card would read as one set
    // covering both photos.
    @Test
    void faultsOfOneKindAReaderCanAnswerKeepACardEach() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingSource(PHOTO, Path.of("a.log")),
                new Finding.MissingSource(Path.of("D:", "Sorted", "b.jpg"), Path.of("b.log"))));

        final Parent problems = (Parent) pane.lookup("#troubleshoot-problems");
        assertThat(problems.lookupAll(".card")).hasSize(2);
        assertThat(textsIn(pane, ".troubleshoot-stack-heading"))
                .containsExactly("2 photos this sift wants to move are not where they were.");
    }

    @Test
    void aFaultFoundOnceIsDrawnWithoutAHeadingAndSpeaksForItself() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingShard("montage-002", "decisions-002.json")));

        assertThat(pane.lookupAll(".troubleshoot-stack-heading")).isEmpty();
        assertThat(textsIn(pane, "#troubleshoot-problem-0"))
                .contains("One sheet has not been judged yet.", "Sheet 2");
    }

    // Both presses say the same sentence, so the node is what says a fresh banner went up.
    @Test
    void answeringTwicePutsUpASecondBannerRatherThanKeepingTheFirst() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingSource(PHOTO, Path.of("a.log"))));

        onFxThread(() -> fire(pane, "#troubleshoot-answer-0-SKIP_FILE"));
        waitForDraw(() -> pane.lookup("#troubleshoot-banner") != null);
        final Node first = pane.lookup("#troubleshoot-banner");
        onFxThread(() -> fire(pane, "#troubleshoot-answer-0-SKIP_FILE"));
        waitForDraw(() -> {
            final Node now = pane.lookup("#troubleshoot-banner");
            return now != null && now != first;
        });

        assertThat(first).isNotNull();
        assertThat(pane.lookup("#troubleshoot-banner")).isNotNull().isNotSameAs(first);
        // lookup answers with the first match and a new banner goes to the front, so without this
        // the old one could still be down the page.
        assertThat(pane.lookupAll("#troubleshoot-banner")).hasSize(1);
    }

    @Test
    void aRedrawWithNothingNewToReportLeavesTheBannerAlone() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingSource(PHOTO, Path.of("a.log"))));
        onFxThread(() -> fire(pane, "#troubleshoot-answer-0-SKIP_FILE"));
        waitForDraw(() -> pane.lookup("#troubleshoot-banner") != null);
        final Node banner = pane.lookup("#troubleshoot-banner");

        onFxThread(() -> fire(pane, "#troubleshoot-detail-toggle"));

        assertThat(banner).isNotNull();
        assertThat(pane.lookup("#troubleshoot-banner")).isSameAs(banner);
    }

    @Test
    void aProblemWithAnswersDrawsOneButtonPerAnswer() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.CorruptSidecar("montage-002")));

        assertThat(pane.lookup("#troubleshoot-answer-0-SET_ASIDE_SHEET")).isNotNull();
        assertThat(pane.lookup("#troubleshoot-answer-0-APPLY_SHEET_ANYWAY")).isNotNull();
    }

    @Test
    void aProblemNoAnswerCanSettleDrawsARowAndNoButtons() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED,
                new Finding.MissingShard("montage-003", "decisions-003.json")));

        assertThat(pane.lookup("#troubleshoot-problem-0")).isNotNull();
        assertThat(pane.lookup("#troubleshoot-problem-0").lookupAll(".button")).isEmpty();
    }

    // Height, not managed: an area bound to nothing would still be laid out, so the fold is what
    // has to take it off the screen.
    @Test
    void theTechnicalReportTakesUpNoRoomUntilItsFoldIsOpened() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED));
        assertThat(pane.lookup("#troubleshoot-detail-text").isVisible()).isFalse();

        onFxThread(() -> fire(pane, "#troubleshoot-detail-toggle"));

        assertThat(pane.lookup("#troubleshoot-detail-text").isVisible()).isTrue();
        assertThat(((TextArea) pane.lookup("#troubleshoot-detail-text")).getText())
                .isEqualTo("the technical report");
    }

    @Test
    void theTechnicalReportCanBeSelectedAndCopied() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED));

        final var trace = (TextArea) pane.lookup("#troubleshoot-detail-text");

        assertThat(trace.isEditable()).isFalse();
        assertThat(trace.isDisabled()).isFalse();
        assertThat(pane.lookup("#troubleshoot-detail-copy")).isNotNull();
    }

    @Test
    void aRunNothingBlocksDrawsBothItsButtonsAndABlockedOneDrawsOnlyDiscard() throws Exception {
        final Parent ready = onFxThread(() -> built(State.READY));
        assertThat(ready.lookup("#troubleshoot-finish")).isNotNull();
        assertThat(ready.lookup("#troubleshoot-discard")).isNotNull();

        final Parent blocked = onFxThread(() -> built(State.BLOCKED,
                new Finding.CorruptSidecar("montage-002")));
        assertThat(blocked.lookup("#troubleshoot-finish")).isNull();
        assertThat(blocked.lookup("#troubleshoot-discard")).isNotNull();
    }

    @Test
    void thereIsAWayBackToTheRunsList() throws Exception {
        final Parent pane = onFxThread(() -> built(State.BLOCKED));

        assertThat(((Button) pane.lookup("#troubleshoot-back")).getText())
                .isEqualTo("Back to Runs");
    }

    private static List<String> textsIn(final Parent pane, final String id) {
        return pane.lookup(id).lookupAll(".selectable-text").stream()
                .map(node -> ((TextInputControl) node).getText())
                .toList();
    }

    private static Node fire(final Parent pane, final String id) {
        final var button = (Button) pane.lookup(id);
        button.fire();
        return button;
    }

    private static Parent built(final State state, final Finding... findings) {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.archivesFolder()).thenReturn(Path.of("logs", "archives"));
        final var health = new PrepDirHealth(state, List.of(findings));
        final JobHandle<TroubleshootReport> pass = reporting(new TroubleshootReport(
                health, false, null, List.of(), health, "the technical report"));
        when(pipeline.troubleshoot(any())).thenReturn(pass);
        when(pipeline.cullRun(any())).thenReturn(new CullRunSummary("2019", PREP_DIR, health, null,
                Instant.now()));
        final var presenter = new TroubleshootPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));
        presenter.open(PREP_DIR, "2019");
        final var page = (Parent) TroubleshootPane.pane(presenter, _ -> { });
        final var scene = new Scene(new StackPane(page), 900, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(TroubleshootPaneTest.class.getResource("/ui/sluice.css"),
                        "the app stylesheet is missing from the test classpath").toExternalForm());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return page;
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<TroubleshootReport> reporting(final TroubleshootReport report) {
        final JobHandle<TroubleshootReport> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(CompletableFuture.completedFuture(report));
        return handle;
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }

    // An answer runs on its own virtual thread and asks for the redraw only once it ends. Pumping
    // the event queue can therefore happen before anything is queued to pump.
    private static void waitForDraw(final Callable<Boolean> drawn) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, drawn);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
