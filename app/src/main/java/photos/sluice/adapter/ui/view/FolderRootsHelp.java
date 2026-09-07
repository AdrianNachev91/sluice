package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

/**
 * What somebody needs to know before choosing the three folder roots.
 *
 * <p>One panel rather than a note under each field, because every rule here is about how the three
 * folders sit against each other. A note under one field can only describe that field.
 */
final class FolderRootsHelp {

    private static final String[] RULES = {
            "Keep your Library and your Inbox apart. Neither may sit inside the other, and they may "
                    + "not be the same folder.",
            "Your Inbox usually sits inside your Working root, which is fine. It must not be the Working "
                    + "root itself, or a folder that holds it.",
            "Pick a Working root that is not synced to the cloud. Sluice moves a lot of files through it, "
                    + "and a sync client would copy every one of them.",
            "Choose your Library carefully. It is where your photos live from then on, and moving it "
                    + "later means copying the whole Library across.",
    };

    /**
     * Prevents instantiation of this static factory class.
     */
    private FolderRootsHelp() {
    }

    /**
     * The panel, ready to sit inside whichever card holds the three folder rows.
     *
     * <p>On its own ground rather than as plain text under the rows. These are rules a reader is
     * meant to take in before choosing, and set flat against the card they read as one more field's
     * help line, which is the one thing they are not.
     *
     * @return {@link Node} the panel
     */
    static Node panel() {
        final var panel = new VBox(SettingsRows.subsectionHeading("Choosing your folders"));
        for (final String rule : RULES) {
            panel.getChildren().add(rule(rule));
        }
        panel.setId("folder-roots-help");
        panel.getStyleClass().add("folder-roots-help");
        return SettingsRows.badgedCallout(panel);
    }

    /**
     * One rule, behind its own bullet.
     *
     * <p>The bullet is drawn rather than typed. No bullet character can be relied on across the
     * three desktops this app runs on, the same reason the caution and info marks are shapes. A font
     * without it would draw a box in front of every rule.
     *
     * <p>The dot sits against the top of the row rather than its middle. A rule that wraps to two
     * lines would otherwise centre its bullet on the pair, pointing at the gap between them.
     *
     * @param text {@link String} the rule
     * @return {@link HBox} the bullet and its line, wrapping at the panel's width
     */
    private static HBox rule(final String text) {
        final TextArea line = SelectableText.prose(text);
        line.getStyleClass().addAll("folder-roots-help-rule", "settings-caution");
        HBox.setHgrow(line, Priority.ALWAYS);

        final var dot = new Circle(2.5);
        dot.getStyleClass().add("folder-roots-help-bullet");
        sitOnTheLetter(dot, line);

        final var row = new HBox(dot, line);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("folder-roots-help-line");
        return row;
    }

    /**
     * Drops the bullet to the middle of a lowercase letter on the rule's first line.
     *
     * <p>Worked out from the font rather than set to a number that looks right. The middle of a
     * lowercase letter is half an x-height above the baseline, and the x-height is the ink of an
     * "x", which is what {@link TextBoundsType#VISUAL} measures. The layout bounds of the same
     * letter are a whole line tall and would put the dot nowhere near it.
     *
     * <p>The circle sits in the row itself rather than in a label of its own. A label places a
     * graphic inside a box it sizes from its own font, so moving the dot means fighting that box
     * instead of saying where the dot goes.
     *
     * <p>Redone whenever the font changes, since the stylesheet is free to change the size and this
     * offset is only right for the size it was worked out from.
     *
     * @param dot {@link Circle} the bullet
     * @param line {@link TextArea} the rule, whose font decides everything here
     */
    private static void sitOnTheLetter(final Circle dot, final TextArea line) {
        line.fontProperty().addListener((_, _, _) -> place(dot, line));
        // The rule's baseline is only known once it has a skin and has been laid out. Asking a Text
        // node for the font's ascent instead lands a pixel high: a label puts its own leading above
        // the first line, and that pixel is the difference between the middle of a letter and the
        // top of one.
        line.layoutBoundsProperty().addListener((_, _, _) -> place(dot, line));
        place(dot, line);
    }

    /**
     * Puts the bullet on the middle of the rule's first line, as an eye reads that middle.
     *
     * <p>Not the middle of an "x". A rule opens with a capital and is full of letters that reach
     * above the x-height, so the weight of the line sits higher than half an x-height. Centring on
     * the capitals instead overshoots, because most of the line is lowercase. The middle of the two
     * is what the eye settles on.
     *
     * @param dot {@link Circle} the bullet, laid out with its top against the row
     * @param line {@link TextArea} the rule it belongs to
     */
    private static void place(final Circle dot, final TextArea line) {
        final double baseline = line.getBaselineOffset();
        if (baseline <= 0) {
            return;
        }
        final double lowercase = inkHeight("x", line);
        final double capital = inkHeight("X", line);
        dot.setTranslateY(baseline - (lowercase + capital) / 4 - dot.getRadius());
    }

    /**
     * How tall one letter's ink stands, which is what the eye measures a line by.
     *
     * <p>{@link TextBoundsType#VISUAL} is the ink. The layout bounds of the same letter are a whole
     * line tall, and would place the bullet nowhere near it.
     *
     * @param letter {@link String} the letter to measure
     * @param line {@link TextArea} the rule, for its font
     * @return double the ink height
     */
    private static double inkHeight(final String letter, final TextArea line) {
        final var ink = new Text(letter);
        ink.setFont(line.getFont());
        ink.setBoundsType(TextBoundsType.VISUAL);
        return ink.getLayoutBounds().getHeight();
    }
}
