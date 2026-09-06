package photos.sluice.adapter.ui.view;

import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
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

// What a label does, plus the selection a label cannot do. The sizing assertions are the ones that
// earn their place. A text input control's own preferred size is a column and row count, so a swap
// that skipped this would redraw every screen it touched.
class SelectableTextTest {

    private static final String SHORT = "Photos still in your Inbox";

    private static final String LONG = "Sluice could not read your folders. Check them in Settings, "
            + "and try again once the drive holding them is reachable.";

    private static final double WRAP_AT = 360;

    private static final double ONE_PIXEL = 1.0;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void aLineIsAsWideAsItsWordsRatherThanAColumnCount() throws Exception {
        final double[] widths = onFxThread(() -> {
            final var label = new Label(SHORT);
            final TextField line = SelectableText.line(SHORT);
            laidOut(new VBox(label, line));
            return new double[] {label.getWidth(), line.getWidth()};
        });

        assertThat(widths[1]).isCloseTo(widths[0], within(ONE_PIXEL));
    }

    @Test
    void aLineIsAsTallAsALabelOfTheSameWords() throws Exception {
        final double[] heights = onFxThread(() -> {
            final var label = new Label(SHORT);
            final TextField line = SelectableText.line(SHORT);
            laidOut(new VBox(label, line));
            return new double[] {label.getHeight(), line.getHeight()};
        });

        assertThat(heights[1]).isCloseTo(heights[0], within(ONE_PIXEL));
    }

    @Test
    void proseIsAsTallAsAWrappedLabelAtTheSameWidth() throws Exception {
        final double[] heights = onFxThread(() -> {
            final var label = new Label(LONG);
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setPrefWidth(WRAP_AT);
            label.setMaxWidth(WRAP_AT);
            final TextArea prose = SelectableText.prose(LONG);
            prose.setPrefWidth(WRAP_AT);
            prose.setMaxWidth(WRAP_AT);
            laidOut(new VBox(label, prose));
            return new double[] {label.getHeight(), prose.getHeight()};
        });

        assertThat(heights[1]).isCloseTo(heights[0], within(ONE_PIXEL));
    }

    @Test
    void textPutInAfterwardsIsMeasuredToo() throws Exception {
        final double[] heights = onFxThread(() -> {
            final var label = new Label(LONG);
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setPrefWidth(WRAP_AT);
            label.setMaxWidth(WRAP_AT);
            final TextArea prose = SelectableText.prose();
            prose.setPrefWidth(WRAP_AT);
            prose.setMaxWidth(WRAP_AT);
            final VBox box = laidOut(new VBox(label, prose));
            prose.setText(LONG);
            box.applyCss();
            box.layout();
            return new double[] {label.getHeight(), prose.getHeight()};
        });

        assertThat(heights[1]).isCloseTo(heights[0], within(ONE_PIXEL));
    }

    // The tests above hand the block its width before it is ever laid out, which is the one case
    // that worked. A column that changes width is what every screen actually does.
    @Test
    void proseRewrapsWhenTheColumnHoldingItNarrows() throws Exception {
        final double[] heights = onFxThread(() -> {
            final var label = new Label(LONG);
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            final TextArea prose = SelectableText.prose(LONG);
            final VBox column = laidOut(new VBox(label, prose));
            column.resize(WRAP_AT, column.getHeight());
            column.applyCss();
            column.layout();
            return new double[] {label.getHeight(), prose.getHeight()};
        });

        assertThat(heights[1]).isCloseTo(heights[0], within(ONE_PIXEL));
    }

    // Without it a container asks for a height with no width named, and gets one measured against
    // whatever width was set last.
    @Test
    void proseTellsAContainerThatItsHeightFollowsItsWidth() throws Exception {
        final Orientation bias = onFxThread(() -> SelectableText.prose(LONG).getContentBias());

        assertThat(bias).isEqualTo(Orientation.HORIZONTAL);
    }

    @Test
    void aReaderCannotTypeIntoIt() throws Exception {
        final String held = onFxThread(() -> {
            final TextField line = SelectableText.line(SHORT);
            laidOut(new VBox(line));
            return line.isEditable() ? "editable" : line.getText();
        });

        assertThat(held).isEqualTo(SHORT);
    }

    // Only the focused one can be copied, so a passage still lit on a block the reader has left
    // promises a keystroke that would take something else.
    @Test
    void aSelectionGoesOutWhenTheReaderSelectsSomewhereElse() throws Exception {
        final String left = onFxThread(() -> {
            final TextField first = SelectableText.line(SHORT);
            final TextField second = SelectableText.line(SHORT);
            laidOut(new VBox(first, second));
            first.requestFocus();
            first.selectAll();
            second.requestFocus();
            return first.getSelectedText();
        });

        assertThat(left).isEmpty();
    }

    @Test
    void itIsNotATabStop() throws Exception {
        final boolean traversable = onFxThread(() -> SelectableText.line(SHORT).isFocusTraversable());

        assertThat(traversable).isFalse();
    }

    // Screens restate what a line is dressed as on every save, refusal and clear. Doing that with
    // setAll took the disguise and the toolkit's own classes off with it, and the line drew as a
    // field from that press on.
    @Test
    void beingDressedForAStateKeepsTheClassesThatDrawItAsText() throws Exception {
        final TextArea line = onFxThread(() -> {
            final TextArea prose = SelectableText.prose(SHORT);
            SelectableText.dressAs(prose, "settings-violation");
            return prose;
        });

        assertThat(line.getStyleClass())
                .containsExactly("text-input", "text-area", "selectable-text", "settings-violation");
    }

    @Test
    void aLineDressedTwiceDoesNotKeepTheFirstStatesClass() throws Exception {
        final TextArea line = onFxThread(() -> {
            final TextArea prose = SelectableText.prose(SHORT);
            SelectableText.dressAs(prose, "settings-violation");
            SelectableText.dressAs(prose, "settings-confirmation");
            return prose;
        });

        assertThat(line.getStyleClass()).contains("settings-confirmation")
                .doesNotContain("settings-violation");
    }

    // Both are text input controls to the toolkit, and a screen asking whether the reader is
    // mid-input has to tell them apart. Asked of a real field, the answer has to stay no.
    @Test
    void aFieldAReaderTypesIntoDoesNotDrawAsText() throws Exception {
        final boolean field = onFxThread(() -> SelectableText.drawsAsText(new TextField()));
        final boolean ours = onFxThread(() -> SelectableText.drawsAsText(SelectableText.line(SHORT)));

        assertThat(field).isFalse();
        assertThat(ours).isTrue();
    }

    // The property outlives a screen restating the style classes, which the class alone would not.
    @Test
    void itStillDrawsAsTextAfterBeingDressedForAState() throws Exception {
        final boolean ours = onFxThread(() -> {
            final TextArea prose = SelectableText.prose(SHORT);
            SelectableText.dressAs(prose, "settings-violation");
            return SelectableText.drawsAsText(prose);
        });

        assertThat(ours).isTrue();
    }

    private static <T extends VBox> T laidOut(final T box) {
        final var stage = new Stage();
        stage.setScene(Stylesheet.applyTo(new Scene(box, 500, 400)));
        stage.show();
        box.applyCss();
        box.layout();
        return box;
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }
}
