package photos.sluice.adapter.ui.view;

import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Text drawn as a label but able to be selected and copied.
 *
 * <p>A {@code Label}'s text cannot be highlighted or copied. JavaFX offers no way to retrofit that.
 * <a href="https://bugs.openjdk.org/browse/JDK-8091644">JDK-8091644</a> is open with no fix
 * version, and a label has no API mapping a click to a character. A text input control has all of
 * it already, so text a reader may want to take is drawn in one held read-only.
 *
 * <p>Two shapes, matching what a label does. {@link #line} is one line that takes the width of its
 * own words. {@link #prose} wraps inside whatever width it is given and grows to the height that
 * wrapping needs.
 *
 * <p>Both measure the words themselves. A text input control's own preferred size is a column and
 * row count rather than what it holds. Measured unstyled: a field asked 148.5 px to hold words
 * 130.8 px wide. An area asked 181 px of height for two lines needing 34. Measuring a string in a
 * font at a width is the question a label answers about itself. So the two agree by construction
 * rather than by adjustment.
 */
final class SelectableText {

    private static final String DISGUISED = "selectable-text";

    // Where the structural classes are kept, so a screen restating what a line is dressed as can
    // put back what the toolkit and the disguise put on.
    private static final String STRUCTURE = "photos.sluice.selectable-text.structure";

    private SelectableText() {}

    /**
     * One line of text, as wide as the words in it.
     *
     * @param text {@link String} what it says
     * @return {@link TextField} the line
     */
    static TextField line(final String text) {
        final var field = new Line();
        field.setText(text);
        disguise(field);
        return field;
    }

    /**
     * One line of text with nothing in it yet, for a caller that fills it later.
     *
     * @return {@link TextField} the line
     */
    static TextField line() {
        return line("");
    }

    /**
     * Text that wraps inside the width it is given.
     *
     * @param text {@link String} what it says
     * @return {@link TextArea} the block
     */
    static TextArea prose(final String text) {
        final var area = new Prose();
        area.setText(text);
        area.setWrapText(true);
        disguise(area);
        return area;
    }

    /**
     * Wrapping text with nothing in it yet, for a caller that fills it later.
     *
     * @return {@link TextArea} the block
     */
    static TextArea prose() {
        return prose("");
    }

    /**
     * Whether a node is one of these rather than a control a reader fills in.
     *
     * <p>Both are text input controls to the toolkit, so anything asking "is the reader mid-input"
     * has to be able to tell them apart. Asked of the property rather than the style class, which a
     * screen is free to restate.
     *
     * @param node the node to ask about, or null where nothing holds focus
     * @return boolean true when it only draws text
     */
    static boolean drawsAsText(final @Nullable Node node) {
        return node instanceof final TextInputControl control
                && control.getProperties().containsKey(STRUCTURE);
    }

    /**
     * Restates what a control is dressed as, keeping the classes that make it one of these.
     *
     * <p>A screen that reports a refusal, then a confirmation, then nothing, sets the classes for
     * each state. Doing that with {@code setAll} takes the disguise off with them, along with the
     * toolkit's own {@code text-input}. The control draws as a field from that moment on. It cost
     * nothing on a label, whose only class is one nobody styles.
     *
     * @param control {@link TextInputControl} the line or block to dress
     * @param classes the style classes for the state it is now in
     */
    static void dressAs(final TextInputControl control, final String... classes) {
        control.getStyleClass().setAll(structureOf(control));
        control.getStyleClass().addAll(classes);
    }

    private static void disguise(final TextInputControl control) {
        control.setEditable(false);
        control.getStyleClass().add(DISGUISED);
        // Taken before a caller adds anything, so it holds what the toolkit gave the control plus
        // the disguise, and nothing a screen dresses it in afterwards.
        control.getProperties().put(STRUCTURE, List.copyOf(control.getStyleClass()));
        // Out of the tab order, which a label was never in. A click still focuses it, and focus is
        // what a selection needs, so this takes nothing away from what it is here for.
        control.setFocusTraversable(false);
        // No right-click menu. A field offers one in the toolkit's own words, on every heading and
        // every two-word label, where Select All means nothing. Dragging and double-clicking are
        // how a reader selects text, and both are untouched.
        control.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, Event::consume);
        // Each of these owns its own selection, so a page of them lights up every passage a reader
        // has dragged over and keeps them all lit. Only the focused one can be copied, which makes
        // the rest a claim the keyboard will not honour.
        control.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                control.deselect();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> structureOf(final TextInputControl control) {
        final Object held = control.getProperties().get(STRUCTURE);
        return held == null ? List.of(DISGUISED) : (List<String>) held;
    }

    /**
     * A text node kept for measuring, laid out the way the control's own words are.
     *
     * <p>Not in the scene. It is asked how tall or wide a string comes out in a given font at a
     * given width. That is the same question a label answers about itself.
     */
    private static final class Ruler {

        private final Text measure = new Text();

        private Ruler() {
            // The bounds a text input control's own skin lays its lines out in. The default drops
            // the font's leading. That is nothing on Segoe UI and 1.7 px a line on DejaVu Sans. So
            // measuring in it asks for less height than the words are drawn in, and the last line
            // comes out cut through the descenders.
            this.measure.setBoundsType(TextBoundsType.LOGICAL_VERTICAL_CENTER);
        }

        private void take(final TextInputControl control, final double wrapAt) {
            this.measure.setText(control.getText());
            this.measure.setFont(control.getFont());
            this.measure.setWrappingWidth(wrapAt);
        }

        private double height() {
            return this.measure.getLayoutBounds().getHeight();
        }

        private double width() {
            return this.measure.getLayoutBounds().getWidth();
        }
    }

    private static final class Line extends TextField {

        private final Ruler ruler = new Ruler();

        @Override
        protected double computePrefWidth(final double height) {
            this.ruler.take(this, 0);
            return this.ruler.width() + this.snappedLeftInset() + this.snappedRightInset();
        }

        @Override
        protected double computeMaxWidth(final double height) {
            return this.computePrefWidth(height);
        }

        @Override
        protected double computePrefHeight(final double width) {
            this.ruler.take(this, 0);
            return this.ruler.height() + this.snappedTopInset() + this.snappedBottomInset();
        }
    }

    private static final class Prose extends TextArea {

        private final Ruler ruler = new Ruler();

        // What tells every container that the height depends on the width. Without it a parent
        // asks for a height with no width named, and gets one measured against whatever width was
        // set last. A wrapping label reports the same bias. A text area reports none, its height
        // being a row count that no width can change.
        @Override
        public Orientation getContentBias() {
            return Orientation.HORIZONTAL;
        }

        // The width the words would take on one line, which is what a wrapping label answers. A
        // text area answers a column count instead, the same number whatever it holds. Nothing
        // stretches this block to that width, since a parent gives it the room it has. What the
        // answer decides is the line count a parent gets back when it asks how tall this would be
        // at its preferred width. A column count turns one line into two.
        @Override
        protected double computePrefWidth(final double height) {
            this.ruler.take(this, 0);
            return this.ruler.width() + this.snappedLeftInset() + this.snappedRightInset();
        }

        @Override
        protected double computePrefHeight(final double width) {
            this.ruler.take(this, this.wrapAt(width));
            return this.ruler.height() + this.snappedTopInset() + this.snappedBottomInset();
        }

        // A width of -1 asks how tall this would like to be with no width named. Answering with no
        // wrapping at all would be one line, however long the sentence. So it measures against the
        // width it already has, and a block with none has not been laid out yet.
        private double wrapAt(final double width) {
            final double sides = this.snappedLeftInset() + this.snappedRightInset();
            final double given = width < 0 ? this.getWidth() : width;
            return Math.max(0, given - sides);
        }

        // A wrapped label's minimum height is its preferred one. A column short of room otherwise
        // shrinks it to a single line, and the sentence comes out cut rather than wrapped.
        @Override
        protected double computeMinHeight(final double width) {
            return this.computePrefHeight(width);
        }
    }
}
