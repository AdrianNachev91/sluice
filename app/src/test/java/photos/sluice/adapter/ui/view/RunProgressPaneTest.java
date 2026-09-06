package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunProgressView;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// The area is mounted on its own and handed states directly, because it takes a view record and
// nothing else. Reaching these through a started job would stub a facade to prove what a record
// already says.
class RunProgressPaneTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void theHeadingAndTheScopeSayWhatIsRunningAndWhatItCovers() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of())));

        assertThat(text(pane, "#run-progress-heading")).isEqualTo("Sift progress");
        assertThat(text(pane, "#run-progress-scope")).isEqualTo("2019");
    }

    @Test
    void aPhaseTheRunHasNotReachedSitsEmptyRatherThanAnimating() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-applying", "Applying decisions", null, 0, false, false, false)))));

        assertThat(bar(pane).getProgress()).isZero();
        assertThat(pane.lookup("#run-phase-applying").getStyleClass()).contains("run-phase-ahead");
    }

    @Test
    void aPhaseWithCountsDrawsThemBesideABarAtItsOwnFraction() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-sifting", "Sifting", "11 of 28", 11d / 28, true, true, false)))));

        assertThat(text(pane, ".run-phase-label")).isEqualTo("Sifting");
        assertThat(text(pane, ".run-phase-counts")).isEqualTo("11 of 28");
        assertThat(bar(pane).getProgress()).isEqualTo(11d / 28);
    }

    @Test
    void aPhaseThatCannotSayHowMuchWorkItHasRunsTheToolkitsOwnAnimation() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-reading", "Reading", null, 0, false, true, false)))));

        assertThat(bar(pane).getProgress()).isEqualTo(ProgressBar.INDETERMINATE_PROGRESS);
        assertThat(text(pane, ".run-phase-counts")).isEmpty();
    }

    @Test
    void aFinishedPhaseIsMarkedOnTheRowRatherThanOnTheBarInsideIt() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-built", "Reading photos", "28 of 28", 1, true, true, true)))));

        assertThat(pane.lookup("#run-phase-built").getStyleClass()).contains("run-phase-done");
        assertThat(bar(pane).getStyleClass()).doesNotContain("run-phase-done");
    }

    @Test
    void aPhaseThatGaveUpPartWayIsMarkedApartFromOneThatWorkedThrough() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-sorting", "Sorting", null, 0, true, true, true, true, false)))));

        assertThat(pane.lookup("#run-phase-sorting").getStyleClass())
                .contains("run-phase-done", "run-phase-cut-short")
                .doesNotContain("run-phase-through");
    }

    @Test
    void aPhaseThatWorkedThroughToItsEndCarriesNoGaveUpMark() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-built", "Reading photos", "28 of 28", 1,
                        true, true, true, false, true)))));

        assertThat(pane.lookup("#run-phase-built").getStyleClass())
                .contains("run-phase-through")
                .doesNotContain("run-phase-cut-short");
    }

    @Test
    void aPhaseThatEndedWithoutDoingAllItsWorkIsNotMarkedAsHavingDoneIt() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-sorting", "Sorting", "850 of 1,204", 850d / 1204,
                        true, true, true, false, false)))));

        assertThat(pane.lookup("#run-phase-sorting").getStyleClass())
                .contains("run-phase-done")
                .doesNotContain("run-phase-through", "run-phase-cut-short");
    }

    @Test
    void aPhaseStillRunningIsNotMarkedAsOneThatFinished() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-sifting", "Sifting", "11 of 28", 11d / 28, true, true, false)))));

        assertThat(pane.lookup("#run-phase-sifting").getStyleClass()).doesNotContain("run-phase-done");
    }

    @Test
    void everyPhaseReportedGetsItsOwnRowInTheOrderTheyArrived() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-built", "Reading photos", "28 of 28", 1, true, true, true),
                new PhaseBar("run-phase-sifting", "Sifting", "11 of 28", 11d / 28, true, true, false)))));

        assertThat(shownPhaseLabels(pane)).containsExactly("Reading photos", "Sifting");
    }

    @Test
    void aRunWithNoPhaseYetSaysItIsStartingRatherThanShowingAnEmptyArea() throws Exception {
        final Parent pane = onFxThread(() -> shown(new RunProgressView("Sort progress", "2019",
                List.of(), "Starting...", "Stop", true, null, 1)));

        assertThat(text(pane, "#run-progress-waiting")).isEqualTo("Starting...");
    }

    @Test
    void aRunThatHasReportedAPhaseKeepsNoRoomForTheStartingLine() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of(
                new PhaseBar("run-phase-sifting", "Sifting", "11 of 28", 11d / 28, true, true, false)))));

        assertThat(pane.lookup("#run-progress-waiting").isManaged()).isFalse();
    }

    @Test
    void nothingCancelledKeepsNoRoomForTheLineAboutCancelling() throws Exception {
        final Parent pane = onFxThread(() -> shown(view(List.of())));

        assertThat(pane.lookup("#run-progress-cancelling").isManaged()).isFalse();
    }

    @Test
    void aCancellationInFlightSaysSoAndTakesTheButtonOutOfUse() throws Exception {
        final Parent pane = onFxThread(() -> shown(new RunProgressView("Sift progress", "2019",
                List.of(), null, "Stopping...", false, "Finishing the sheet it is already on.", 3)));

        assertThat(text(pane, "#run-progress-cancelling")).isEqualTo("Finishing the sheet it is already on.");
        assertThat(cancel(pane).getText()).isEqualTo("Stopping...");
        assertThat(cancel(pane).isDisabled()).isTrue();
    }

    @Test
    void pressingCancelAsksThePresenterToStopTheRunAndThenDrawsAgain() throws Exception {
        final RunLauncherPresenter presenter = mock(RunLauncherPresenter.class);
        final var redraws = new AtomicInteger();
        final Parent pane = onFxThread(() -> shown(presenter, redraws::incrementAndGet, view(List.of())));

        onFxThread(() -> cancel(pane).fire());

        verify(presenter).cancel();
        assertThat(redraws.get()).isOne();
    }

    private static RunProgressView view(final List<PhaseBar> phases) {
        return new RunProgressView("Sift progress", "2019", phases, phases.isEmpty() ? "Starting..." : null,
                "Stop", true, null, 3);
    }

    // Read off the rows rather than the labels, because a label inside a hidden row still answers
    // true to isVisible. Reserved rows hold the controls below them still and draw nothing, so a
    // reader sees only what this returns.
    private static List<String> shownPhaseLabels(final Parent pane) {
        return pane.lookupAll(".run-phase").stream()
                .filter(Node::isVisible)
                .map(row -> ((TextField) row.lookup(".run-phase-label")).getText())
                .toList();
    }

    private static Button cancel(final Parent pane) {
        return (Button) pane.lookup("#run-cancel");
    }

    private static ProgressBar bar(final Parent pane) {
        return (ProgressBar) pane.lookup(".run-phase-bar");
    }

    private static String text(final Parent pane, final String selector) {
        return ((TextInputControl) pane.lookup(selector)).getText();
    }

    private static Parent shown(final RunProgressView view) {
        return shown(mock(RunLauncherPresenter.class), () -> { }, view);
    }

    // The real stylesheet. A scene built without it answers for Modena rather than for this app,
    // and the assertions above read style classes the sheet is what gives meaning to.
    private static Parent shown(final RunLauncherPresenter presenter, final Runnable redraw,
                                final RunProgressView view) {
        final RunProgressPane.Mounted mounted = RunProgressPane.mount(presenter, redraw);
        mounted.fill().accept(view);
        final var page = (Parent) mounted.node();
        final var scene = new Scene(new StackPane(page), 900, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(RunProgressPaneTest.class.getResource("/ui/sluice.css"),
                        "the app stylesheet is missing from the test classpath").toExternalForm());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return page;
    }

    private static void onFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }
}
