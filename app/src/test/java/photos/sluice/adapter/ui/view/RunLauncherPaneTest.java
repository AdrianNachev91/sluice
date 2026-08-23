package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.Pipeline;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// What only a built scene graph can be wrong about. Which controls the screen draws, what a press
// does to the ones beside it, and what a line with nothing to say takes up. What a scope means is
// RunLauncherPresenterTest's.
class RunLauncherPaneTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void everyModeGetsAButtonAndTheOneInForceIsTheOnlyOneMarked() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        assertThat(modeButtons(pane)).extracting(ToggleButton::getText)
                .containsExactly("Sort", "Sift", "Move to library", "Curate", "Rescue");
        assertThat(modeButtons(pane)).filteredOn(ToggleButton::isSelected)
                .extracting(ToggleButton::getText).containsExactly("Sort");
    }

    @Test
    void aYearsMonthsAreOnScreenOnlyWhileThatYearIsChosen() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        // Height, not managed: the box stays laid out at zero so the fold has a height to travel
        // to. Waited for rather than read straight away, because that travel takes a moment.
        settledOpen(pane, 2019, false);

        onFxThread(() -> fire(pane, "#run-year-2019"));
        settledOpen(pane, 2019, true);

        onFxThread(() -> fire(pane, "#run-year-2018"));
        settledOpen(pane, 2019, false);
        settledOpen(pane, 2018, true);
    }

    /**
     * Waits for one year's months to finish opening or closing.
     */
    private static void settledOpen(final Parent pane, final int year, final boolean open)
            throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> {
            final Node months = onFxThread(() -> pane.lookup("#run-months-" + year));
            final double height = onFxThread(months::getBoundsInLocal).getHeight();
            return open ? height > 0 : height == 0;
        });
    }

    @Test
    void openingAYearBelowTheFoldBringsItsMonthsIntoView() throws Exception {
        // Short enough that the cards do not fit. That is the only state the pane can scroll in,
        // and so the only one where the fold has a position to work out.
        final Parent pane = onFxThread(() -> built(presenter(), 260));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        final ScrollPane scroll = scrollIn(pane);
        onFxThread(() -> {
            scroll.setVvalue(0);
            return scroll;
        });

        onFxThread(() -> fire(pane, "#run-year-2018"));
        settledOpen(pane, 2018, true);

        // Waited for rather than read once. The pane travels in the fold's own timeline. A box with
        // any height at all is not yet a pane that has finished moving.
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> inView(pane, 2018));
        assertThat(onFxThread(scroll::getVvalue)).isGreaterThan(0);
    }

    @Test
    void choosingAnotherYearMovesThePaneOnceRatherThanTwice() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter(), 260));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        onFxThread(() -> fire(pane, "#run-year-2019"));
        settledOpen(pane, 2019, true);

        onFxThread(() -> fire(pane, "#run-year-2018"));
        settledOpen(pane, 2019, false);
        settledOpen(pane, 2018, true);

        // Two boxes move here, one closing and one opening. A pane whose travel was worked out from
        // either alone counts only half the change in the page's height, and never arrives here.
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> inView(pane, 2018));
    }

    @Test
    void theSortedRowsAreGreyedWhileTheModeReadsTheInbox() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> fire(pane, "#run-mode-sift"));
        assertThat(pane.lookup("#run-year-2019").isDisabled()).isFalse();

        onFxThread(() -> fire(pane, "#run-mode-sort"));

        assertThat(pane.lookup("#run-year-2019").isDisabled()).isTrue();
        assertThat(pane.lookup("#run-month-2019-6").isDisabled()).isTrue();
        assertThat(pane.lookup("#run-scope-field").isDisabled()).isTrue();
    }

    @Test
    void pressingAMonthMarksItAndLeavesItsYearMarkedToo() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        onFxThread(() -> fire(pane, "#run-year-2019"));

        onFxThread(() -> fire(pane, "#run-month-2019-7"));

        assertThat(((TextField) pane.lookup("#run-scope-field")).getText()).isEqualTo("2019 7");
        assertThat(((ToggleButton) pane.lookup("#run-month-2019-7")).isSelected()).isTrue();
        assertThat(((ToggleButton) pane.lookup("#run-month-2019-6")).isSelected()).isFalse();
        assertThat(((ToggleButton) pane.lookup("#run-year-2019")).isSelected()).isTrue();
    }

    @Test
    void pressingAMonthLeavesTheRowItselfInPlace() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        onFxThread(() -> fire(pane, "#run-year-2019"));
        final Node before = pane.lookup("#run-month-2019-6");

        onFxThread(() -> fire(pane, "#run-month-2019-6"));

        assertThat(pane.lookup("#run-month-2019-6")).isSameAs(before);
    }

    @Test
    void clearingTheScopeFieldPutsOutTheYearRowItHadLit() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        onFxThread(() -> fire(pane, "#run-year-2019"));
        assertThat(((ToggleButton) pane.lookup("#run-year-2019")).isSelected()).isTrue();

        onFxThread(() -> type(pane, ""));

        assertThat(yearRows(pane)).noneMatch(ToggleButton::isSelected);
    }

    @Test
    void pressingAModeChangesWhatTheScopeFieldSaysItTakes() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> fire(pane, "#run-mode-move"));
        final String beforehand = text(pane, "#run-scope-hint");

        onFxThread(() -> fire(pane, "#run-mode-sift"));

        assertThat(text(pane, "#run-scope-hint")).isNotEqualTo(beforehand).contains("click one below");
    }

    @Test
    void pressingAModeChangesWhatTheScreenSaysThatModeDoes() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        assertThat(text(pane, "#run-mode-hint")).contains("into Sorted");

        onFxThread(() -> fire(pane, "#run-mode-move"));

        assertThat(text(pane, "#run-mode-hint")).contains("into your library");
    }

    @Test
    void pressingTheModeAlreadyInForceLeavesItSelected() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        onFxThread(() -> fire(pane, "#run-mode-sort"));

        assertThat(((ToggleButton) pane.lookup("#run-mode-sort")).isSelected()).isTrue();
    }

    @Test
    void typingAScopeTheModeCannotTakeMarksItOnTheScreen() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> fire(pane, "#run-mode-move"));

        onFxThread(() -> type(pane, "2019 6,8,11"));

        assertThat(text(pane, "#run-scope-refusal")).contains("run of months");
        assertThat(pane.lookup("#run-start").isDisabled()).isTrue();
    }

    @Test
    void aScopeTheModeTakesLeavesNothingMarkedAndTheButtonLive() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> fire(pane, "#run-mode-move"));

        onFxThread(() -> type(pane, "2019 6-8"));

        assertThat(pane.lookup("#run-scope-refusal").isVisible()).isFalse();
        assertThat(pane.lookup("#run-start").isDisabled()).isFalse();
    }

    @Test
    void oneRowIsDrawnPerStagedYearSayingWhatItHolds() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        assertThat(pane.lookup("#run-year-2019")).isNotNull();
        assertThat(pane.lookup("#run-year-2018")).isNotNull();
        assertThat(insideText(pane, "#run-year-2019")).containsExactly("2019", "100 photos and 10 videos");
    }

    @Test
    void clickingAYearRowTypesThatYearIntoTheScopeField() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));

        onFxThread(() -> fire(pane, "#run-year-2018"));

        assertThat(((TextField) pane.lookup("#run-scope-field")).getText()).isEqualTo("2018");
        assertThat(((ToggleButton) pane.lookup("#run-year-2018")).isSelected()).isTrue();
    }

    // Identity, not behaviour. A rebuilt row answers a second press just as well, because the test
    // looks it up again by id and finds the replacement. What a rebuild destroys is the control the
    // reader is still pressing. Whether it is the same node afterwards is the only thing that
    // separates the two.
    @Test
    void pressingAYearRowLeavesTheRowItselfInPlace() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        onFxThread(() -> chooseAModeTheRowsScope(pane));
        final Node before = pane.lookup("#run-year-2018");

        onFxThread(() -> fire(pane, "#run-year-2018"));
        onFxThread(() -> fire(pane, "#run-year-2019"));

        assertThat(pane.lookup("#run-year-2018")).isSameAs(before);
        assertThat(((TextField) pane.lookup("#run-scope-field")).getText()).isEqualTo("2019");
        assertThat(((ToggleButton) pane.lookup("#run-year-2019")).isSelected()).isTrue();
        assertThat(((ToggleButton) pane.lookup("#run-year-2018")).isSelected()).isFalse();
    }

    @Test
    void pressingAModeLeavesTheButtonItselfInPlace() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));
        final Node before = pane.lookup("#run-mode-sift");

        onFxThread(() -> fire(pane, "#run-mode-sift"));
        onFxThread(() -> fire(pane, "#run-mode-curate"));

        assertThat(pane.lookup("#run-mode-sift")).isSameAs(before);
        assertThat(text(pane, "#run-scope-hint")).contains("sorts your photos before it looks at them");
    }

    @Test
    void aCostThisRunWillNotCarryTakesNoRoomOnTheScreen() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        onFxThread(() -> fire(pane, "#run-mode-sift"));
        onFxThread(() -> type(pane, "2019"));

        assertThat(pane.lookup("#run-estimate").isManaged()).isFalse();
        assertThat(pane.lookup("#run-estimate-disclaimer").isManaged()).isFalse();
    }

    @Test
    void aCostThisRunWillCarryIsDrawnWithWhatTheFigureIsWorth() throws Exception {
        final Pipeline pipeline = pipeline();
        when(pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(148_231, 6_402, false, true));
        final Parent pane = onFxThread(() -> built(new RunLauncherPresenter(pipeline)));

        onFxThread(() -> fire(pane, "#run-mode-sift"));
        onFxThread(() -> type(pane, "2019"));

        assertThat(pane.lookup("#run-estimate").isManaged()).isTrue();
        assertThat(text(pane, "#run-estimate-figure")).isEqualTo("About 150,000 tokens");
        assertThat(text(pane, "#run-estimate-disclaimer")).contains("An estimate, not a quote");
    }

    @Test
    void theInboxCardSaysWhatIsWaitingAndHowMuchRoomItTakes() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        assertThat(text(pane, "#run-inbox-headline")).isEqualTo("300 photos and videos");
        assertThat(text(pane, "#run-inbox-detail")).isEqualTo("976.6 KB");
    }

    @Test
    void withNothingStagedTheSortedCardSaysSoInsteadOfDrawingAnEmptyList() throws Exception {
        final Pipeline pipeline = pipeline();
        when(pipeline.sortedTally()).thenReturn(new SortedTally(List.of()));
        final Parent pane = onFxThread(() -> built(new RunLauncherPresenter(pipeline)));

        assertThat(pane.lookup("#run-year-2019")).isNull();
        assertThat(text(pane, "#run-nothing-staged")).contains("Sort your Inbox first");
    }

    @Test
    void aScreenWithNothingToReportKeepsNoRoomForTheLineThatWouldReportIt() throws Exception {
        final Parent pane = onFxThread(() -> built(presenter()));

        assertThat(pane.lookup("#run-message").isManaged()).isFalse();
        assertThat(pane.lookup("#run-nothing-staged").isManaged()).isFalse();
        // Sort is the mode the screen opens on, and the one whose hint is empty because its
        // description above already says what it takes.
        assertThat(pane.lookup("#run-scope-hint").isManaged()).isFalse();
    }

    private static List<ToggleButton> modeButtons(final Parent pane) {
        return ((HBox) pane.lookup(".run-mode-row")).getChildren().stream()
                .map(ToggleButton.class::cast)
                .toList();
    }

    // By class rather than by walking the children. A year holding months is wrapped in a stack
    // with them, so it is not a direct child of the row container.
    private static List<ToggleButton> yearRows(final Parent pane) {
        return pane.lookupAll(".run-year-row").stream()
                .map(ToggleButton.class::cast)
                .toList();
    }

    private static List<String> insideText(final Parent pane, final String id) {
        return ((HBox) ((ToggleButton) pane.lookup(id)).getGraphic()).getChildren().stream()
                .map(child -> ((Label) child).getText())
                .toList();
    }

    private static String text(final Parent pane, final String id) {
        return ((Label) pane.lookup(id)).getText();
    }

    private static Node chooseAModeTheRowsScope(final Parent pane) {
        return fire(pane, "#run-mode-sift");
    }

    private static ScrollPane scrollIn(final Parent pane) {
        return (ScrollPane) pane.lookup(".settings-scroll");
    }

    private static boolean inView(final Parent pane, final int year) throws Exception {
        return onFxThread(() -> {
            final ScrollPane scroll = scrollIn(pane);
            final Node months = pane.lookup("#run-months-" + year);
            final double viewport = scroll.getViewportBounds().getHeight();
            final double scrollable = scroll.getContent().getLayoutBounds().getHeight() - viewport;
            final double top = scrollable > 0 ? scroll.getVvalue() / scroll.getVmax() * scrollable : 0;
            final double foot = scroll.getContent()
                    .sceneToLocal(months.localToScene(months.getLayoutBounds())).getMaxY();
            return foot <= top + viewport + 1;
        });
    }

    private static Node fire(final Parent pane, final String id) {
        final Node found = pane.lookup(id);
        if (found instanceof final ToggleButton toggle) {
            toggle.fire();
        } else {
            ((Button) found).fire();
        }
        return found;
    }

    private static Node type(final Parent pane, final String scope) {
        final var field = (TextField) pane.lookup("#run-scope-field");
        field.setText(scope);
        return field;
    }

    private static RunLauncherPresenter presenter() {
        return new RunLauncherPresenter(pipeline());
    }

    private static Pipeline pipeline() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 10, List.of(new MonthRow(6, 40, 10), new MonthRow(7, 30, 0),
                        new MonthRow(11, 30, 0))),
                new YearRow(2018, 50, 0, List.of(new MonthRow(1, 50, 0))))));
        when(pipeline.estimateFor(anyInt())).thenReturn(new SpendEstimate(0, 0, true, false));
        return pipeline;
    }

    // The counts are read before the pane is built, as well as by the pane's own background read.
    // So no assertion here depends on which of the two lands first.
    private static Parent built(final RunLauncherPresenter presenter) {
        return built(presenter, 800);
    }

    private static Parent built(final RunLauncherPresenter presenter, final int height) {
        presenter.refreshCounts();
        final var page = (Parent) RunLauncherPane.pane(presenter);
        final var scene = new Scene(new StackPane(page), 900, height);
        scene.getStylesheets().add(
                Objects.requireNonNull(RunLauncherPaneTest.class.getResource("/ui/sluice.css"),
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
