package photos.sluice.adapter.ui.view;

import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.shape.SVGPath;
import org.jspecify.annotations.Nullable;

/**
 * The web address in a sentence, and the control that opens it.
 *
 * <p>Nothing in the toolkit reads text looking for an address, so the reading is here.
 */
final class LinkedText {

    // What marks a word as an address. One scheme rather than a general parser: the only text this
    // reads is written by this app's own providers. An address offered over anything else is left
    // as the plain words it was written as, and this app does not put a reader on it.
    private static final String SCHEME = "https://";

    // A box with an arrow leaving it, drawn rather than taken from a font. A character that renders
    // on one desktop can draw a box on another. A shape cannot go missing.
    private static final String LEAVES_THE_APP =
            "M2 4 h4 v1 h-3 v6 h6 v-3 h1 v4 h-8 z M8 2 h4 v4 h-1 v-2.3 l-3.6 3.6 -0.7-0.7 3.6-3.6 h-2.3 z";

    // Ordinary sentence punctuation, which ends a word without belonging to the address in it. A
    // full stop closing the sentence would otherwise be opened as part of the address.
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}\"'";

    // The sentence beside it already shows the address, so naming it again would put it on the
    // screen twice.
    private static final String OPENS_IT = "Open in your browser";

    private LinkedText() {}

    /**
     * The first web address in a sentence, if it holds one.
     *
     * @param sentence {@link String} the words to read
     * @return {@link String} the address, stripped of any sentence punctuation, or null where the
     *         sentence offers none
     */
    static @Nullable String addressIn(final String sentence) {
        for (final String word : sentence.split(" ", -1)) {
            if (word.startsWith(SCHEME)) {
                int end = word.length();
                while (end > 0 && TRAILING_PUNCTUATION.indexOf(word.charAt(end - 1)) >= 0) {
                    end--;
                }
                return word.substring(0, end);
            }
        }
        return null;
    }

    /**
     * A control that opens one address, marked as leaving this app.
     *
     * @param address {@link String} the address, already stripped of any sentence punctuation
     * @return {@link Hyperlink} the control
     */
    static Hyperlink opening(final String address) {
        final var mark = new SVGPath();
        mark.setContent(LEAVES_THE_APP);
        mark.getStyleClass().add("linked-text-mark");
        final var link = new Hyperlink(OPENS_IT, mark);
        link.setContentDisplay(ContentDisplay.RIGHT);
        link.getStyleClass().add("linked-text-link");
        link.setOnAction(_ -> ExternalBrowser.open(address));
        return link;
    }
}
