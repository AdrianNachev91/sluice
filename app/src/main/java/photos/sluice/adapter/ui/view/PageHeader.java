package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;

/**
 * The bar a page keeps pinned above whatever scrolls: its name, its Save, and whatever the last save
 * had to say.
 *
 * <p>Pinned because Save at the foot of a long page means scrolling the whole way down to reach it,
 * every time. A second button at the top would only move the problem to the middle of the page.
 * That is where a reader stands when they have just edited something.
 *
 * <p>The message sits here rather than beside the fields, because this is where the button that
 * produced it is. A page-level refusal at the foot of a page nobody has scrolled to is a message
 * nobody reads.
 */
final class PageHeader {

    /**
     * The built bar and the two controls a page has to reach again.
     *
     * @param header {@link VBox} the bar itself, to sit above the scrolling body
     * @param save {@link Button} the page's Save
     * @param status {@link TextArea} where a refusal or a report lands
     */
    record Result(VBox header, Button save, TextArea status) {

        /**
         * Takes the last operation's message off the bar.
         *
         * <p>The bar is built once and outlives every rebuild, so without this a line about the
         * last draw stands over the one that has replaced it.
         */
        void clearStatus() {
            this.status.setText("");
            SelectableText.dressAs(this.status, "settings-save-status");
        }
    }

    private PageHeader() {
    }

    /**
     * Builds the bar.
     *
     * @param heading {@link String} the page's own name
     * @param saveId {@link String} the node id Save carries
     * @param above a control to sit above the heading, such as the way back, or null for none
     * @return {@link Result} the bar and its controls
     */
    static Result build(final String heading, final String saveId, final @Nullable Node above) {
        final TextField name = SelectableText.line(heading);
        name.getStyleClass().add("pane-heading");

        final var save = new Button("Save");
        save.setId(saveId);

        // Stacked rather than laid out in a row, so each is placed against the bar itself. In a row
        // the heading would decide what is left for Save, and each page's heading is a different
        // width.
        final var actions = new StackPane(name, save);
        StackPane.setAlignment(name, Pos.CENTER_LEFT);
        StackPane.setAlignment(save, Pos.CENTER_RIGHT);
        actions.getStyleClass().add("pane-header-actions");

        final TextArea status = SelectableText.prose();
        status.getStyleClass().add("settings-save-status");
        // No text, no line. A bar that always reserved a row for a message would put a permanent
        // gap between the heading and the page.
        SettingsRows.showWhileTextPresent(status);

        final var header = new VBox();
        if (above != null) {
            header.getChildren().add(above);
        }
        header.getChildren().addAll(actions, status);
        header.getStyleClass().add("pane-header");
        return new Result(header, save, status);
    }

    /**
     * A page built as a pinned bar over a body that scrolls.
     *
     * <p>The bar sits outside the {@link ScrollPane}, and the pane takes whatever height is left.
     * Inside it, the bar would scroll away with everything else.
     *
     * @param header {@link VBox} the pinned bar
     * @param body {@link VBox} the page's own scrolling contents
     * @return {@link VBox} the page, ready for the shell's content area
     */
    static VBox pinnedPage(final Result header, final VBox body) {
        final ScrollPane scroll = SettingsRows.scrolling(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        bindWidthToViewport(scroll, header.header());
        final var page = new VBox(header.header(), scroll);
        page.getStyleClass().add("pinned-header-page");
        saveOnEnter(page, header.save());
        releaseFocusOnOutsideClick(page);
        return page;
    }

    /**
     * Holds a pinned bar to the width of the pane scrolling under it.
     *
     * <p>A bar outside the pane keeps the whole window's width while the page below it gives some
     * up to a scrollbar. Held to what the pane can show instead, the two keep one axis whether or
     * not the page is long enough to scroll.
     *
     * <p>Before the first layout the viewport measures nothing, and a bar bound to that would have
     * no width at all. So an unmeasured pane leaves it unbounded.
     *
     * @param scroll {@link ScrollPane} the pane they have to agree with
     * @param pinned {@link Region} the rows sitting outside it
     */
    static void bindWidthToViewport(final ScrollPane scroll, final Region... pinned) {
        for (final Region row : pinned) {
            row.maxWidthProperty().bind(scroll.viewportBoundsProperty()
                    .map(seen -> seen.getWidth() <= 0 ? Double.MAX_VALUE : seen.getWidth()));
        }
    }

    /**
     * Lets Enter save, except while the caret is in a field.
     *
     * <p>Not a default button, which is the toolkit's own answer and the wrong one here. A default
     * button takes Enter from anywhere, including a folder path someone is halfway through typing,
     * so the page saves what they have not finished writing.
     *
     * <p>Filtered on the way down rather than handled on the way up. A button, a checkbox and a
     * radio all consume Enter without doing anything with it. Listening on the way up would hear
     * the key only from the controls that must not save, which is the opposite of the rule.
     *
     * <p>A control that owes the reader the key still gets it, untouched.
     *
     * @param page {@link VBox} the whole page, header and scrolling body alike
     * @param save {@link Button} the button Enter stands in for
     */
    private static void saveOnEnter(final VBox page, final Button save) {
        page.addEventFilter(KeyEvent.KEY_PRESSED, pressed -> {
            if (pressed.getCode() != KeyCode.ENTER || midInput(page)) {
                return;
            }
            save.fire();
            pressed.consume();
        });
    }

    /**
     * Lets go of a field once the reader clicks somewhere that is not one.
     *
     * <p>A click on a heading, a help line or any other piece of chrome takes focus. Text a reader
     * can select has to be focusable to be selected. Without this the field they have visibly left
     * would keep the keyboard, and Enter would still read as the end of a line nobody is writing.
     *
     * <p>Only a field is let go of, and only when the click landed outside it. A control that took
     * the click for itself, a radio button or a checkbox, is holding focus because the reader chose
     * it. Taking that away would cost them the focus ring and the arrow keys between options.
     *
     * @param page {@link VBox} the whole page, header and scrolling body alike
     */
    private static void releaseFocusOnOutsideClick(final VBox page) {
        page.addEventHandler(MouseEvent.MOUSE_PRESSED, pressed -> {
            if (page.getScene() == null || !isAField(page.getScene().getFocusOwner())) {
                return;
            }
            if (pressed.getTarget() instanceof final Node hit
                    && SettingsRows.sitsInside(hit, page.getScene().getFocusOwner())) {
                return;
            }
            page.requestFocus();
        });
    }

    /**
     * Whether a node is one of the controls a reader types or chooses in.
     *
     * @param node the node holding focus, or null where nothing does
     * @return boolean true for a text control, a dropdown or a spinner
     */
    private static boolean isAField(final @Nullable Node node) {
        if (SelectableText.drawsAsText(node)) {
            return false;
        }
        return node instanceof TextInputControl || node instanceof ComboBoxBase<?>
                || node instanceof Spinner<?>;
    }

    /**
     * Whether the reader is partway through telling one control something.
     *
     * <p>Four controls answer Enter for themselves. A text field and a text area take it as the end
     * of a line. A dropdown takes it as the choice being made. A spinner takes it as the number
     * being finished, but only while one is being typed. The arrows produce a whole value at every
     * step, so Enter after one has nothing left to end.
     *
     * <p>A spinner is the reason this cannot be a type check alone. It reports itself as holding
     * focus whether it is being typed into or stepped, so it is asked which it last took.
     *
     * @param page {@link VBox} the page, used to reach the scene that knows what holds focus
     * @return boolean true while a control is still owed the keystroke
     */
    private static boolean midInput(final VBox page) {
        if (page.getScene() == null) {
            return false;
        }
        final Node owner = page.getScene().getFocusOwner();
        if (SelectableText.drawsAsText(owner)) {
            return false;
        }
        final Spinner<?> spinner = spinnerAround(owner);
        if (spinner != null) {
            return SettingsRows.beingTypedInto(spinner);
        }
        return owner instanceof TextInputControl || owner instanceof ComboBoxBase<?>;
    }

    /**
     * The spinner a focused node belongs to, if it belongs to one.
     *
     * <p>Which node a spinner leaves holding focus is not dependable: it answers as itself in one
     * run and as its own editor in another. Both mean the same thing to a reader, so the question is
     * asked of the spinner either way rather than of whichever node happened to win. Answering off
     * the editor alone would also read every stepped spinner as a text field and refuse to save.
     *
     * @param node the node holding focus, or null where nothing does
     * @return the spinner it sits in, or null when it is not in one
     */
    private static @Nullable Spinner<?> spinnerAround(final @Nullable Node node) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (walk instanceof final Spinner<?> spinner) {
                return spinner;
            }
        }
        return null;
    }
}
