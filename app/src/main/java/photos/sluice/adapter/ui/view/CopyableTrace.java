package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Text a reader is asked to hand on to somebody else, in a form they can actually take.
 *
 * <p>This app writes no log file, and a reader who opened it from their desktop has no console. So
 * whatever is on screen is the only place the text exists. A {@link Label} cannot be selected, so a
 * panel asking for a bug report in one is asking for something it does not hand over.
 */
final class CopyableTrace {

    private CopyableTrace() {
    }

    /**
     * The text itself, selectable and not editable.
     *
     * <p>How tall it draws and what it is set in are the stylesheet's, under {@code .trace-text}.
     * A trace is longer than any screen, so it is held to a height rather than growing to whatever
     * it holds.
     *
     * @param id {@link String} the control's own id
     * @param text {@link String} what it holds
     * @return {@link TextArea} the block
     */
    static TextArea area(final String id, final String text) {
        final var trace = new TextArea(text);
        trace.setId(id);
        trace.setEditable(false);
        trace.setWrapText(true);
        trace.getStyleClass().add("trace-text");
        return trace;
    }

    /**
     * Puts text on the clipboard.
     *
     * @param text {@link String} what to put there
     */
    static void copyToClipboard(final String text) {
        final var content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * A button that puts that text on the clipboard and says it did.
     *
     * <p>Says so on the button itself, which holds only where nothing redraws the screen the press
     * lands on. A screen that redraws builds a new button, and the word has to come from whatever
     * it draws from.
     *
     * @param id {@link String} the button's own id
     * @param label {@link String} what it says before it is pressed
     * @param copied {@link String} what it says once it has copied
     * @param text a {@link Supplier} of {@link String} what to copy
     * @return {@link Button} the button
     */
    static Button copyButton(final String id, final String label, final String copied,
                             final Supplier<String> text) {
        final var button = new Button(label);
        button.setId(id);
        button.getStyleClass().add("button-quiet");
        button.setOnAction(_ -> {
            copyToClipboard(text.get());
            button.setText(copied);
        });
        return button;
    }

    /**
     * The text behind a fold the reader opens, with a Copy button beside the fold's own control.
     *
     * <p>Folded because the text carries paths from the reader's own machine, and a reader pasting
     * into a public issue publishes whatever is on screen. Opening it is their choice. Copy sits
     * outside the fold and works without opening it, so handing the text to somebody who can read
     * it never requires reading it first.
     *
     * <p>The open state lives on the node returned, so a caller that rebuilds this draws it shut
     * again.
     *
     * @param idPrefix {@link String} the ids of the parts are this plus -toggle, -copy and -text
     * @param label {@link String} what the fold's control says, naming what it reveals
     * @param copy {@link String} what the Copy button says
     * @param copied {@link String} what it says once it has copied
     * @param text {@link String} the text itself
     * @return {@link VBox} the fold, its Copy button and the text, shut
     */
    static VBox fold(final String idPrefix, final String label, final String copy,
                     final String copied, final String text) {
        final var toggle = new Button(label);
        toggle.setId(idPrefix + "-toggle");
        toggle.getStyleClass().add("runs-section-toggle");
        final TextArea body = area(idPrefix + "-text", text);
        SettingsRows.setFoldMarker(toggle, false);

        final var head = new HBox(toggle, SettingsRows.spacer(),
                copyButton(idPrefix + "-copy", copy, copied, () -> text));
        head.setAlignment(Pos.CENTER_LEFT);
        final var whole = new VBox(head, body);
        // No pane to carry: this one is drawn on a panel that fills the content area rather than
        // one that scrolls. Everything else about the travel is what every other fold does.
        final var travel = new SectionFold(body, whole, null);
        final var open = new AtomicBoolean();
        toggle.setOnAction(_ -> {
            final boolean showing = !open.get();
            open.set(showing);
            travel.setOpen(showing);
            SettingsRows.setFoldMarker(toggle, showing);
        });
        return whole;
    }

}
