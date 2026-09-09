package photos.sluice.adapter.ui.view;

import javafx.scene.control.Button;
import javafx.scene.shape.SVGPath;

/**
 * The way out of a screen that has no sidebar entry of its own.
 */
final class BackButton {

    private BackButton() {
    }

    /**
     * The way back to the screen this one was opened from.
     *
     * @param id {@link String} the button's own id
     * @param label {@link String} what it says
     * @param onBack {@link Runnable} returns to that screen
     * @return {@link Button} the way back
     */
    static Button of(final String id, final String label, final Runnable onBack) {
        final var back = new Button(label);
        back.setId(id);
        back.getStyleClass().add("button-quiet");
        back.setGraphic(uTurnGlyph());
        back.setOnAction(_ -> onBack.run());
        return back;
    }

    /**
     * The arrow on the way back: a band turning through 180 degrees, with the head pointing down at
     * the far end.
     *
     * <p>Drawn as one filled outline rather than a stroked line, because a stroke on an
     * {@link SVGPath} contributes nothing: the shape is filled. Its own coordinates run 0 to 16, and
     * the stylesheet scales it to the size a button's text sits at.
     *
     * @return {@link SVGPath} the glyph
     */
    private static SVGPath uTurnGlyph() {
        final var glyph = new SVGPath();
        glyph.setContent("M12.2 14 L12.2 7 A3.2 3.2 0 0 0 5.8 7 L5.8 9 L8.2 9 L4.9 14 L1.6 9 "
                + "L4 9 L4 7 A5 5 0 0 1 14 7 L14 14 Z");
        glyph.getStyleClass().add("u-turn-glyph");
        return glyph;
    }
}
