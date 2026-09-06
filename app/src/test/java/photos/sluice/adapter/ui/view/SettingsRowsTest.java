package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// The shared row vocabulary's own behaviour, where it has any. Most of it builds nodes and is
// judged rendered; holdTo is the piece that refuses input, and a refusal has to be exercised.
class SettingsRowsTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void typingStopsAtTheCeiling() throws Exception {
        final TextField field = onFxThread(() -> bounded(5));

        onFxThread(() -> field.appendText("abcdefgh"));

        assertThat(field.getText()).isEqualTo("abcde");
    }

    // A paste arrives at the formatter as one change carrying every character at once, which is the
    // case a per-keystroke guard would let straight through.
    @Test
    void aPasteTooLongIsCutToWhatFitsRatherThanDropped() throws Exception {
        final TextField field = onFxThread(() -> bounded(10));

        onFxThread(() -> field.replaceSelection("a whole paragraph pasted in one go"));

        assertThat(field.getText()).isEqualTo("a whole pa");
    }

    @Test
    void aPasteIntoAFieldAlreadyPartlyFullOnlyTakesTheRoomLeft() throws Exception {
        final TextField field = onFxThread(() -> bounded(10));
        onFxThread(() -> field.setText("abc"));

        onFxThread(() -> {
            field.positionCaret(3);
            field.replaceSelection("defghijklmnop");
        });

        assertThat(field.getText()).isEqualTo("abcdefghij");
    }

    // Replacing a selection frees the room it occupied, so the paste has more space than the
    // untouched length suggests. Getting this wrong truncates a replacement that would have fitted.
    @Test
    void replacingASelectionCountsTheRoomThatSelectionGivesBack() throws Exception {
        final TextField field = onFxThread(() -> bounded(10));
        onFxThread(() -> field.setText("abcdefghij"));

        onFxThread(() -> {
            field.selectRange(0, 5);
            field.replaceSelection("12345");
        });

        assertThat(field.getText()).isEqualTo("12345fghij");
    }

    @Test
    void aFieldAtItsCeilingTakesNothingMore() throws Exception {
        final TextField field = onFxThread(() -> bounded(3));
        onFxThread(() -> field.setText("abc"));

        onFxThread(() -> field.appendText("d"));

        assertThat(field.getText()).isEqualTo("abc");
    }

    @Test
    void aMultiLineBoxStopsAtItsCeilingCountingNewlines() throws Exception {
        final TextArea box = onFxThread(() -> {
            final var area = new TextArea();
            SettingsRows.holdTo(area, 6);
            return area;
        });

        onFxThread(() -> box.appendText("one\ntwo\nthree"));

        assertThat(box.getText()).isEqualTo("one\ntw");
    }

    // Deleting has to stay possible in a field sitting at its ceiling, which a filter that refused
    // every change over the limit would prevent.
    @Test
    void aFieldAtItsCeilingCanStillBeEmptied() throws Exception {
        final TextField field = onFxThread(() -> bounded(3));
        onFxThread(() -> field.setText("abc"));

        onFxThread(field::clear);

        assertThat(field.getText()).isEmpty();
    }

    // Read inside the same block that starts the movement, so no frame can have run yet. The
    // version of this that set the value outright would already be at the top by this line.
    @Test
    void travellingToTheTopDoesNotArriveAtOnce() throws Exception {
        final ScrollPane scroll = onFxThread(SettingsRowsTest::aPageTallerThanItsWindow);
        final var body = (VBox) scroll.getContent();
        onFxThread(() -> scroll.setVvalue(scroll.getVmax()));

        final double[] rightAfter = new double[1];
        onFxThread(() -> {
            SettingsRows.travelToTop(body);
            rightAfter[0] = scroll.getVvalue();
        });

        assertThat(rightAfter[0]).isEqualTo(scroll.getVmax());
    }

    // A row stretches its children to its tallest, which in a banner is the dismiss button.
    // Wrapping text lays its words against its own top edge, so a stretched sentence draws above
    // the middle of the ground behind it.
    @Test
    void theBannersSentenceIsNotStretchedToTheHeightOfItsDismissButton() throws Exception {
        final TextArea said = onFxThread(SettingsRowsTest::aBannerOnScreen);

        assertThat(said.getHeight()).isCloseTo(said.prefHeight(said.getWidth()), within(1.0));
    }

    private static TextArea aBannerOnScreen() {
        final var page = new VBox();
        page.getChildren().addFirst(SettingsRows.banner(page, "probe-banner", "Settings saved.", false));
        final var stage = new Stage();
        stage.setScene(Stylesheet.applyTo(new Scene(page, 700, 300)));
        stage.show();
        page.applyCss();
        page.layout();
        return (TextArea) page.lookup(".settings-banner-text");
    }

    private static ScrollPane aPageTallerThanItsWindow() {
        return aPage(40);
    }

    private static ScrollPane aPage(final int rowCount) {
        final var body = new VBox(rows(rowCount));
        final ScrollPane scroll = SettingsRows.scrolling(body);
        final var stage = new Stage();
        stage.setScene(new Scene(new VBox(scroll), 300, 200));
        stage.show();
        scroll.applyCss();
        scroll.layout();
        return scroll;
    }

    private static Label[] rows(final int count) {
        final var rows = new Label[count];
        for (int i = 0; i < count; i++) {
            rows[i] = new Label("row " + i);
            rows[i].setMinHeight(30);
        }
        return rows;
    }

    private static TextField bounded(final int characters) {
        final var field = new TextField();
        SettingsRows.holdTo(field, characters);
        return field;
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }

    private static void onFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
    }
}
