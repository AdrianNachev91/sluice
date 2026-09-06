package photos.sluice.adapter.ui.view;

import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunLauncherView;
import photos.sluice.adapter.ui.RunResultView;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Tone;
import photos.sluice.adapter.ui.RunResultView.Warning;
import photos.sluice.adapter.ui.RunStage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// The card is mounted on its own and handed states directly, because it takes a view record and
// nothing else. What each ending puts in that record is RunResultsTest's.
class RunResultPaneTest {

    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");

    private static final Path PREP_DIR = Path.of("logs", "sift-prep", "2019");

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void everyCountedThingGetsARowSayingWhatItWasAndHowMany() throws Exception {
        final Parent pane = onFxThread(() -> shown(finished(List.of(
                new Count("result-photos-sorted", "Photos sorted", "980"),
                new Count("result-videos-sorted", "Videos sorted", "122")))));

        assertThat(pane.lookupAll(".run-result-count-label")).extracting(node -> ((TextField) node).getText())
                .containsExactly("Photos sorted", "Videos sorted");
        assertThat(pane.lookupAll(".run-result-count-value")).extracting(node -> ((TextField) node).getText())
                .containsExactly("980", "122");
    }

    @Test
    void aRunThatCountedNothingDrawsNoRowsAtAll() throws Exception {
        final Parent pane = onFxThread(() -> shown(finished(List.of())));

        assertThat(pane.lookupAll(".run-result-count")).isEmpty();
    }

    @Test
    void aWarningDrawsItsHeadlineOverItsDetail() throws Exception {
        final Parent pane = onFxThread(() -> shown(with(new Warning(
                "The dates on these photos may be wrong.", "They came with date files alongside them."))));

        assertThat(pane.lookup("#run-result-warning").isManaged()).isTrue();
        assertThat(text(pane, "#run-result-warning .settings-caution"))
                .isEqualTo("The dates on these photos may be wrong.");
        assertThat(text(pane, "#run-result-warning-detail"))
                .isEqualTo("They came with date files alongside them.");
    }

    @Test
    void aRunWithNothingToWarnAboutKeepsNoRoomForTheStripe() throws Exception {
        final Parent pane = onFxThread(() -> shown(finished(List.of())));

        assertThat(pane.lookup("#run-result-warning").isManaged()).isFalse();
    }

    @Test
    void anOfferToContinueDrawsItsQuestionInTheBodyAndItsButtonInTheActionRow() throws Exception {
        final Parent pane = onFxThread(() -> shown(offering()));

        assertThat(text(pane, "#run-result-resume .selectable-text")).isEqualTo("Continue?");
        assertThat(resume(pane).getText()).isEqualTo("Continue sifting");
        assertThat(resume(pane).isManaged()).isTrue();
    }

    @Test
    void aCardWithNoOfferKeepsNoRoomForEitherHalfOfOne() throws Exception {
        final Parent pane = onFxThread(() -> shown(finished(List.of())));

        assertThat(pane.lookup("#run-result-resume").isManaged()).isFalse();
        assertThat(resume(pane).isManaged()).isFalse();
    }

    // The card outlives every run it shows, so a directory captured when the button was built would
    // continue whichever run happened to be first.
    @Test
    void continuingNamesTheRunTheCardIsShowingRatherThanAnEarlierOne() throws Exception {
        final RunLauncherPresenter presenter = mock(RunLauncherPresenter.class);
        final var redraws = new AtomicInteger();
        final RunResultPane.Mounted mounted = onFxThread(
                () -> RunResultPane.mount(presenter, redraws::incrementAndGet, _ -> { }));
        final Path second = Path.of("logs", "sift-prep", "2018");
        onFxThread(() -> {
            mounted.fill().accept(card(offering()));
            mounted.fill().accept(card(offeringFor(second)));
        });

        onFxThread(() -> ((Button) mounted.node().lookup("#run-resume")).fire());

        verify(presenter).continueRun(second);
        assertThat(redraws.get()).isOne();
    }

    @Test
    void pressingDoneDismissesTheReportAndDrawsAgain() throws Exception {
        final RunLauncherPresenter presenter = mock(RunLauncherPresenter.class);
        final var redraws = new AtomicInteger();
        final Parent pane = onFxThread(
                () -> shown(presenter, redraws::incrementAndGet, card(finished(List.of()))));

        onFxThread(() -> ((Button) pane.lookup("#run-done")).fire());

        verify(presenter).dismissResult();
        assertThat(redraws.get()).isOne();
    }

    @Test
    void continuingAStoppedRunIsDrawnAsNeitherTheWayOnNorTheWayOut() throws Exception {
        final Parent pane = onFxThread(() -> shown(offering()));

        assertThat(resume(pane).getStyleClass()).contains("run-cancel").doesNotContain("run-start");
        assertThat(pane.lookup("#run-done").getStyleClass())
                .contains("run-cancel").doesNotContain("run-start");
    }

    @Test
    void siftingIsDrawnAsTheWayOnWhileDoneStaysQuiet() throws Exception {
        final Parent pane = onFxThread(() -> shown(offeringASift()));

        assertThat(resume(pane).getStyleClass()).contains("run-start").doesNotContain("run-cancel");
        assertThat(pane.lookup("#run-done").getStyleClass())
                .contains("run-cancel").doesNotContain("run-start");
    }

    // The button outlives every card it draws, so a weight left on from the last one would make a
    // Continue loud the moment it followed a Sift.
    @Test
    void theActionButtonDropsTheWeightOfTheCardBeforeIt() throws Exception {
        final RunResultPane.Mounted mounted = onFxThread(
                () -> RunResultPane.mount(mock(RunLauncherPresenter.class), () -> { }, _ -> { }));

        onFxThread(() -> mounted.fill().accept(card(offeringASift())));
        onFxThread(() -> mounted.fill().accept(card(offering())));

        assertThat(mounted.node().lookup("#run-resume").getStyleClass())
                .contains("run-cancel").doesNotContain("run-start");
    }

    @Test
    void anOfferToSiftDrawsItsButtonAndNoQuestionAboveIt() throws Exception {
        final Parent pane = onFxThread(() -> shown(offeringASift()));

        assertThat(resume(pane).getText()).isEqualTo("Sift 2019");
        assertThat(pane.lookup("#run-result-resume").isManaged()).isFalse();
    }

    @Test
    void aRefusedPressIsReportedOnTheCardRatherThanBehindIt() throws Exception {
        final Parent pane = onFxThread(() -> shown(mock(RunLauncherPresenter.class), () -> { },
                new RunStage.Finished(offeringASift(),
                        new RunLauncherView.Message("Something else is running now.", true))));

        assertThat(text(pane, "#run-result-message")).isEqualTo("Something else is running now.");
        assertThat(pane.lookup("#run-result-message").getStyleClass()).contains("settings-violation");
    }

    @Test
    void aCardWithNothingToReportKeepsNoRoomForTheLine() throws Exception {
        final Parent pane = onFxThread(() -> shown(finished(List.of())));

        assertThat(pane.lookup("#run-result-message").isManaged()).isFalse();
    }

    @Test
    void aFailedRunMarksTheCardSoItsOwnSentenceCanBeDrawnToBeRead() throws Exception {
        final Parent pane = onFxThread(() -> shown(new RunResultView("Sorting stopped.", Tone.FAILED,
                "Sluice could not do that.", List.of(), null, null, "Done")));

        assertThat(pane.getPseudoClassStates()).contains(FAILED);
    }

    @Test
    void aFailedRunPutsItsOwnSentenceOnItsOwnGround() throws Exception {
        final Parent pane = onFxThread(() -> shown(new RunResultView("Rescuing could not finish.",
                Tone.FAILED, "That file holds something other than the text Sluice wrote there.",
                List.of(), null, null, "Done")));

        assertThat(pane.lookup("#run-result-detail-box").getStyleClass()).contains("warning-box");
    }

    @Test
    void anEndingThatIsNotAFailureLeavesItsSentenceWhereEveryOtherLineSits() throws Exception {
        final Parent pane = onFxThread(() -> shown(new RunResultView("Sorting stopped.",
                Tone.UNFINISHED, "205 of them are still in your Inbox.", List.of(), null, null,
                "Done")));

        assertThat(pane.lookup("#run-result-detail-box").getStyleClass())
                .doesNotContain("warning-box");
    }

    @Test
    void aCardThatShowedAFailureDropsTheFailureMarkWhenTheNextRunEndsWell() throws Exception {
        final RunResultPane.Mounted mounted = onFxThread(
                () -> RunResultPane.mount(mock(RunLauncherPresenter.class), () -> { }, _ -> { }));
        onFxThread(() -> mounted.fill().accept(card(new RunResultView("Sorting stopped.", Tone.FAILED,
                "Broke.", List.of(), null, null, "Done"))));

        onFxThread(() -> mounted.fill().accept(card(finished(List.of()))));

        assertThat(mounted.node().getPseudoClassStates()).doesNotContain(FAILED);
    }

    private static RunResultView finished(final List<Count> counts) {
        return new RunResultView("Sorting finished.", Tone.FINISHED, null, counts, null, null,
                "Done");
    }

    private static RunResultView with(final Warning warning) {
        return new RunResultView("Sorting finished.", Tone.FINISHED, null, List.of(), warning,
                null, "Done");
    }

    private static RunResultView offering() {
        return offeringFor(PREP_DIR);
    }

    private static RunResultView offeringFor(final Path prepDir) {
        return new RunResultView("Sifting stopped at its spending limit.", Tone.UNFINISHED, null,
                List.of(), null,
                new CardAction.ContinueRun("Continue?", "Continue sifting", prepDir), "Done");
    }

    private static RunResultView offeringASift() {
        return new RunResultView("Sorting finished.", Tone.FINISHED, null, List.of(), null,
                new CardAction.SiftNow("Sift 2019", 2019, 6), "Done");
    }

    private static RunStage.Finished card(final RunResultView view) {
        return new RunStage.Finished(view, null);
    }

    private static Button resume(final Parent pane) {
        return (Button) pane.lookup("#run-resume");
    }

    private static String text(final Parent pane, final String selector) {
        return ((TextInputControl) pane.lookup(selector)).getText();
    }

    private static Parent shown(final RunResultView view) {
        return shown(mock(RunLauncherPresenter.class), () -> { }, card(view));
    }

    // The real stylesheet. A scene built without it answers for Modena rather than for this app,
    // and the assertions above read style classes the sheet is what gives meaning to.
    private static Parent shown(final RunLauncherPresenter presenter, final Runnable redraw,
                                final RunStage.Finished showing) {
        final RunResultPane.Mounted mounted = RunResultPane.mount(presenter, redraw, _ -> { });
        mounted.fill().accept(showing);
        final var page = (Parent) mounted.node();
        final var scene = new Scene(new StackPane(page), 900, 700);
        scene.getStylesheets().add(
                Objects.requireNonNull(RunResultPaneTest.class.getResource("/ui/sluice.css"),
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
