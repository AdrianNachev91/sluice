package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;

/**
 * A sentence with any web address in it drawn as a link.
 *
 * <p>JavaFX has no control that does this. {@code Hyperlink} is one a caller builds, and {@code
 * TextFlow} is what lets one sit inside a sentence. Nothing in the toolkit reads text looking for
 * an address, so the reading is here.
 *
 * <p>The address stays visible as itself rather than hiding behind words. One that has since moved
 * then still leaves the reader something to copy or search for. That is the whole of what a link
 * going nowhere would otherwise cost them.
 */
final class LinkedText {

    // What marks a word as an address. One scheme rather than a general parser: the only text this
    // reads is written by this app's own providers. An address offered over anything else is still
    // shown, as the plain words it was written as, and this app does not put a reader on it.
    private static final String SCHEME = "https://";

    // A box with an arrow leaving it, drawn rather than taken from a font. A character that renders
    // on one desktop can draw a box on another. A shape cannot go missing.
    private static final String LEAVES_THE_APP =
            "M2 4 h4 v1 h-3 v6 h6 v-3 h1 v4 h-8 z M8 2 h4 v4 h-1 v-2.3 l-3.6 3.6 -0.7-0.7 3.6-3.6 h-2.3 z";

    // Ordinary sentence punctuation, which ends a word without belonging to the address in it. A
    // full stop closing the sentence would otherwise be opened as part of the address.
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}\"'";

    private LinkedText() {}

    /**
     * Reads one sentence and lays it out, every address in it clickable.
     *
     * @param sentence {@link String} the words to draw
     * @return {@link TextFlow} those words, with each address drawn as a link
     */
    static TextFlow of(final String sentence) {
        final var parts = new ArrayList<Node>();
        for (final String word : sentence.split(" ", -1)) {
            if (!parts.isEmpty()) {
                parts.add(word(" "));
            }
            parts.addAll(read(word));
        }
        final var flow = new TextFlow(parts.toArray(Node[]::new));
        flow.getStyleClass().add("linked-text");
        return flow;
    }

    /**
     * One word, as either plain text or a link and whatever punctuation followed it.
     *
     * @param word {@link String} the word to read
     * @return a {@link List} of {@link Node} what to draw for it
     */
    private static List<Node> read(final String word) {
        if (!word.startsWith(SCHEME)) {
            return List.of(word(word));
        }
        int end = word.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(word.charAt(end - 1)) >= 0) {
            end--;
        }
        final String address = word.substring(0, end);
        final String after = word.substring(end);
        final Node link = link(address);
        return after.isEmpty() ? List.of(link) : List.of(link, word(after));
    }

    /**
     * Plain words, carrying a class of their own so a stylesheet can reach them.
     *
     * <p>A {@link Text} built here has no style class at all. The {@code text} class belongs to the
     * ones JavaFX makes inside a {@link javafx.scene.control.Labeled}'s skin, so a rule written
     * against that reaches every label on the screen and none of these. Unreached, they draw at the
     * default black, which is legible on a light ground and almost invisible on a dark one.
     *
     * @param words {@link String} what to draw
     * @return {@link Text} those words, styleable
     */
    private static Text word(final String words) {
        final var text = new Text(words);
        text.getStyleClass().add("linked-text-word");
        return text;
    }

    /**
     * One address, drawn as a link that leaves this app.
     *
     * @param address {@link String} the address, already stripped of any sentence punctuation
     * @return {@link Node} the link
     */
    private static Node link(final String address) {
        final var mark = new SVGPath();
        mark.setContent(LEAVES_THE_APP);
        mark.getStyleClass().add("linked-text-mark");
        final var link = new Hyperlink(address, mark);
        link.setContentDisplay(ContentDisplay.RIGHT);
        link.getStyleClass().add("linked-text-link");
        link.setOnAction(_ -> ExternalBrowser.open(address));
        return link;
    }
}
