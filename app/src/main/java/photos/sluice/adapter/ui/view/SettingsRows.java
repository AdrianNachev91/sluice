package photos.sluice.adapter.ui.view;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsView;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The row and card vocabulary every Settings card is built from. Cards, labelled rows, help and
 * caution lines, the glyphs they carry, and a field's own violation marking.
 *
 * <p>Nothing here knows what a setting means. It only knows how to lay one out, the same way
 * across whichever card asks.
 */
final class SettingsRows {

    // A field the screen has something to say about. A pseudo-class rather than a style class,
    // because it is a state the control is in rather than a kind of control it is.
    private static final PseudoClass REFUSED = PseudoClass.getPseudoClass("refused");

    // Where the two parts of a ring glyph's mark sit, measured from the ring's centre. A dot of
    // radius 1 and a stem 6 tall, two apart, span ten. So the far edge of each sits five out, and
    // the pair is centred. The caution and info glyphs are mirror images and share both numbers.
    private static final double DOT_FROM_CENTRE = 4;
    private static final double STEM_FROM_CENTRE = 2;

    private SettingsRows() {}

    /**
     * One panel of the screen: its name, a line saying what the whole panel is for, then its rows.
     *
     * <p>The description is what stops a panel being a name over some controls. A reader who does
     * not already know how photos reach a vision model learns nothing from two numbers under a
     * heading.
     *
     * @param eyebrow {@link String} the panel's name, in capitals
     * @param description what the panel is for, or null where the name says it
     * @param rows {@link Node}[] the panel's contents
     * @return {@link VBox} the card
     */
    static VBox card(final String eyebrow, final @Nullable String description, final Node... rows) {
        final var card = new VBox(sectionEyebrow(eyebrow));
        if (description != null) {
            final var says = new Label(description);
            says.setWrapText(true);
            says.getStyleClass().add("settings-card-intro");
            card.getChildren().add(says);
        }
        card.getChildren().addAll(rows);
        card.getStyleClass().add("card");
        return card;
    }

    /**
     * A heading for a block sitting inside a card, below that card's own name.
     *
     * <p>Quieter than an eyebrow, because an eyebrow announces a panel and this announces a part of
     * one. The credential block is inside the provider panel, since a key belongs to the provider it
     * authenticates and means nothing beside a provider that takes none.
     *
     * @param text {@link String} what the block is called
     * @return {@link Label} the heading
     */
    static Label subsectionHeading(final String text) {
        final var label = new Label(text);
        label.getStyleClass().add("settings-subsection-heading");
        return label;
    }

    private static Label sectionEyebrow(final String text) {
        final var label = new Label(text);
        label.getStyleClass().addAll("eyebrow", "settings-card-title");
        return label;
    }

    /**
     * A field's own name. Carries a style class rather than none, since a JavaFX control left
     * unstyled draws its text in a fixed grey that only suits the light look.
     *
     * @param text {@link String} what the label says
     * @return {@link Label} the styled label
     */
    static Label fieldLabel(final String text) {
        final var label = new Label(text);
        label.getStyleClass().add("settings-field-label");
        return label;
    }

    record FolderRow(VBox row, TextField field, Label violation) {
    }

    static FolderRow folderRow(final String label, final String id, final SettingsView.FolderField field) {
        final var text = new TextField(field.value());
        text.setId(id);
        text.setPromptText(field.suggestion());

        final var fieldRow = new HBox(text, browseButton(text, field.suggestion()));
        fieldRow.getStyleClass().add("settings-field-row");
        // A folder path is as long as it is, and the ones a user cares about are the long ones. The
        // field takes whatever width the row has left rather than truncating at a default.
        HBox.setHgrow(text, Priority.ALWAYS);

        final var violation = violationLabel();
        markWhileSomethingIsWrong(text, violation);
        say(violation, field.violation());

        final var children = new VBox(fieldLabel(label), fieldRow, violation);
        children.getStyleClass().add("settings-row");
        return new FolderRow(children, text, violation);
    }

    /**
     * Puts a message under a field, or takes the one that is there away.
     *
     * @param violation {@link Label} the row's own violation label
     * @param message what is wrong with this field, or null when nothing is
     */
    static void say(final Label violation, final @Nullable String message) {
        violation.setText(message == null ? "" : message);
    }

    /**
     * Keeps a field marked for exactly as long as its row has something to say about it.
     *
     * <p>The message underneath says what to fix. The ring says which control to fix it on, which
     * matters most where the page is long enough that the two can be read apart.
     *
     * <p>Driven off the message rather than set beside it, so no caller can mark one and forget the
     * other. Registered before the row's first message, so a violation the screen opens with is
     * marked too.
     *
     * @param field {@link Node} the control the message is about
     * @param violation {@link Label} the message under it
     */
    static void markWhileSomethingIsWrong(final Node field, final Label violation) {
        violation.textProperty().addListener((_, _, message) ->
                field.pseudoClassStateChanged(REFUSED, !message.isEmpty()));
    }

    /**
     * A button that opens a folder picker and writes what was picked back into the field.
     *
     * <p>Opens on the folder the field already names, or on the suggestion when it is empty. Where
     * neither is a folder yet, the picker opens wherever the platform puts it.
     *
     * @param text {@link TextField} the field the picked folder is written into
     * @param suggestion {@link String} where to open when the field is empty
     * @return {@link Button} the browse button
     */
    private static Button browseButton(final TextField text, final String suggestion) {
        final var browse = new Button("Browse...");
        browse.getStyleClass().add("button-quiet");
        browse.setOnAction(_ -> {
            final var chooser = new DirectoryChooser();
            final File initial = nearestExistingFolder(text.getText().isBlank() ? suggestion : text.getText());
            if (initial != null) {
                chooser.setInitialDirectory(initial);
            }
            final File chosen = chooser.showDialog(text.getScene().getWindow());
            if (chosen != null) {
                text.setText(chosen.getAbsolutePath());
            }
        });
        return browse;
    }

    /**
     * The folder a picker should open on, given the path a field holds or suggests.
     *
     * <p>The nearest one that exists, rather than that one or nothing. A suggestion names where
     * Sluice would put a folder, so on a fresh install it is precisely the folder that is missing.
     * Answering null there opens the picker on the list of drives, and its parent is a great deal
     * closer to the answer than that.
     *
     * <p>The text is whatever is in the field, so it does not have to name a path this system could
     * ever have. Refusing outright would make Browse do nothing at all, on exactly the value a user
     * opened the picker to replace.
     *
     * @param text {@link String} the path to open on, which need not exist
     * @return {@link File} the nearest existing folder at or above it, or null when none of it does
     */
    private static @Nullable File nearestExistingFolder(final String text) {
        final Path named;
        try {
            named = Path.of(text).toAbsolutePath();
        } catch (final InvalidPathException e) {
            return null;
        }
        for (Path candidate = named; candidate != null; candidate = candidate.getParent()) {
            final File folder = candidate.toFile();
            if (folder.isDirectory()) {
                return folder;
            }
        }
        return null;
    }

    /**
     * A labelled row carrying one line saying what the setting is for, under the label and above the
     * control.
     *
     * <p>For the settings whose name does not carry its own meaning. A folder root explains itself;
     * an endpoint does not.
     *
     * @param label {@link String} the field's own name
     * @param explanation {@link String} what this setting is for, in a sentence
     * @param field {@link Node} the control
     * @param overrideNote a note about what outranks this value, or null
     * @return {@link VBox} the row
     */
    static VBox explainedRow(final String label, final String explanation, final Node field,
                             final @Nullable String overrideNote) {
        final var row = labeledRow(label, field, overrideNote);
        row.getChildren().add(1, helpLine(explanation));
        return row;
    }

    private static VBox labeledRow(final String label, final Node field, final @Nullable String overrideNote) {
        final var row = new VBox(fieldLabel(label), field);
        if (overrideNote != null) {
            row.getChildren().add(overrideLabel(overrideNote));
        }
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * A label and its control on one line, for a control short enough to sit beside its own name.
     *
     * <p>The colon is what a label does when it is beside the thing it names rather than above it.
     * Any note still goes underneath, since it is about the whole row.
     *
     * @param label {@link String} the control's name, without its colon
     * @param field {@link Node} the control
     * @param overrideNote a note about what outranks this value, or null
     * @return {@link VBox} the line, and the note under it if there is one
     */
    static VBox inlineLabeledRow(final String label, final Node field, final @Nullable String overrideNote) {
        final var line = new HBox(fieldLabel(label + ":"), field);
        line.getStyleClass().add("settings-inline-row");
        final var row = new VBox(line);
        if (overrideNote != null) {
            row.getChildren().add(overrideLabel(overrideNote));
        }
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * A field's own violation message, built empty and taking no space until it has something to
     * say. A label that appears and disappears moves everything under it.
     *
     * @return {@link Label} the label a refusal fills
     */
    static Label violationLabel() {
        final var violation = new Label();
        violation.setWrapText(true);
        violation.getStyleClass().add("settings-violation");
        violation.managedProperty().bind(violation.visibleProperty());
        violation.visibleProperty().bind(violation.textProperty().isNotEmpty());
        return violation;
    }

    /**
     * A caution: something the user configured is not being used, and the screen carries on anyway.
     *
     * <p>Not a violation. Nothing here refuses to save and no value is lost, so the red a broken
     * folder root wears would overstate it. What it needs instead is to be noticed.
     *
     * @param text {@link String} what to tell the user
     * @return {@link HBox} the glyph and the message, the glyph beside the first line
     */
    static HBox cautionRow(final String text) {
        final var label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-caution");
        HBox.setHgrow(label, Priority.ALWAYS);
        final var row = new HBox(cautionGlyph(), label);
        row.getStyleClass().add("settings-caution-row");
        return row;
    }

    /**
     * An exclamation mark in a ring, drawn from shapes.
     *
     * <p>No character that renders as one can be relied on across the three desktops this app runs
     * on, and a font without it draws a box instead. Shapes cannot go missing. Three of them rather
     * than one path: an SVGPath is filled, so a ring drawn as one depends on two circles
     * cancelling by winding. A stroked {@link Circle} states the ring outright.
     *
     * @return {@link StackPane} the glyph
     */
    private static StackPane cautionGlyph() {
        final var ring = new Circle(7.5);
        ring.getStyleClass().add("caution-ring");
        final var stem = new Rectangle(2, 6);
        stem.setTranslateY(-STEM_FROM_CENTRE);
        stem.getStyleClass().add("caution-mark");
        final var dot = new Circle(1);
        dot.setTranslateY(DOT_FROM_CENTRE);
        dot.getStyleClass().add("caution-mark");
        return sized(new StackPane(ring, stem, dot));
    }

    /**
     * A lower-case i in a ring, drawn from shapes, the same way {@link #cautionGlyph} is.
     *
     * <p>A fact worth noticing is not a caution. The exclamation shape already carries that second
     * meaning on this screen, so a fact reusing it would read as a problem that is not one.
     *
     * @return {@link StackPane} the glyph
     */
    static StackPane infoGlyph() {
        final var ring = new Circle(7.5);
        ring.getStyleClass().add("info-ring");
        final var dot = new Circle(1);
        dot.setTranslateY(-DOT_FROM_CENTRE);
        dot.getStyleClass().add("info-mark");
        final var stem = new Rectangle(2, 6);
        stem.setTranslateY(STEM_FROM_CENTRE);
        stem.getStyleClass().add("info-mark");
        return sized(new StackPane(ring, dot, stem));
    }

    /**
     * Fixes a glyph at the size the text beside it is drawn for, and lets its shapes sit off the
     * pixel grid.
     *
     * <p>A {@link StackPane} rounds where it puts each child. The ring is fifteen across inside a
     * box of sixteen, so rounding pushes it a whole pixel down while the mark, an even ten tall,
     * lands centred. That leaves the mark half a pixel high in its ring, which is what a reader
     * sees at this size. Nothing here is drawn on the grid anyway, since every edge of a circle is
     * already smoothed.
     *
     * @param glyph {@link StackPane} the ring and its mark
     * @return {@link StackPane} that same glyph
     */
    private static StackPane sized(final StackPane glyph) {
        glyph.setSnapToPixel(false);
        glyph.setMinSize(16, 16);
        glyph.setPrefSize(16, 16);
        glyph.setMaxSize(16, 16);
        return glyph;
    }

    static Label overrideLabel(final String text) {
        final var label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-override-note");
        return label;
    }

    /**
     * One line of quiet explanation under a control.
     *
     * @param text {@link String} what it says
     * @return {@link Label} the line, wrapping at the row's width
     */
    static Label helpLine(final String text) {
        final var line = new Label(text);
        line.setWrapText(true);
        line.getStyleClass().add("settings-help");
        return line;
    }

    /**
     * A number field over one range: typeable, refusing anything outside it keystroke by keystroke,
     * and taking a typed value the moment focus leaves.
     *
     * <p>An editable {@link Spinner} does not commit its editor's text on its own. A value typed
     * and then left by clicking Save would be discarded, in favour of whatever the spinner last
     * held.
     *
     * <p>Committing has to be safe before it can be automatic. The commit parses the editor's text,
     * and a spinner hands an empty or non-numeric editor straight to its converter, which throws.
     * That throw lands in a focus listener with nobody to catch it, and leaves the spinner holding
     * null. So the formatter refuses what the converter cannot take.
     *
     * <p>Only the ceiling is enforced while typing. Every number passes through its own shorter
     * prefixes on the way to being typed, so refusing those would make anything above the floor
     * unreachable. The spinner clamps a value under the floor when it commits.
     *
     * @param range {@link SettingsView.NumberRange} what this field accepts
     * @param value int the value to open on
     * @return {@link Spinner} of {@link Integer} the field
     */
    static Spinner<Integer> numberField(final SettingsView.NumberRange range, final int value) {
        final var spinner = new Spinner<Integer>(range.least(), range.most(), value, range.step());
        spinner.setEditable(true);
        final int digits = String.valueOf(range.most()).length();
        spinner.getEditor().setTextFormatter(new TextFormatter<>(change -> {
            final String proposed = change.getControlNewText();
            if (!proposed.matches("\\d{0," + digits + "}")) {
                return null;
            }
            // Empty is allowed while typing, since clearing the field is how a value gets replaced.
            // What it must never do is reach the commit below.
            return proposed.isEmpty() || Integer.parseInt(proposed) <= range.most() ? change : null;
        }));
        // Spinner's own built-in focus-lost handling runs before this listener and commits the
        // editor's text through the value factory's converter regardless. An empty string commits
        // to null rather than throwing, so by the time this runs spinner.getValue() can already be
        // null. The last value known good is tracked independently rather than trusted from there.
        final int[] lastValid = {value};
        spinner.valueProperty().addListener((_, _, newValue) -> {
            // The property's declared type is not nullable, so the IDE reads this guard as always
            // true. The null is the state described above: the converter commits one for empty
            // text, and a test proves it by failing without this.
            //noinspection ConstantValue
            if (newValue != null) {
                lastValid[0] = newValue;
            }
        });
        // The editor's focus, not the spinner's. Focus belongs to whichever node actually owns it,
        // and for an editable spinner that is the text field inside it. A listener on the spinner
        // hears nothing, so the value would only ever be what the arrows last set.
        spinner.getEditor().focusedProperty().addListener((_, _, stillFocused) -> {
            if (stillFocused) {
                return;
            }
            if (spinner.getEditor().getText().isEmpty()) {
                spinner.getValueFactory().setValue(lastValid[0]);
                spinner.getEditor().setText(String.valueOf(lastValid[0]));
                return;
            }
            spinner.increment(0);
        });
        return spinner;
    }

    /**
     * What a field will accept, said in the help line above it.
     *
     * <p>Read off the same range the field enforces, so the sentence cannot promise a number the
     * field refuses. Without it a user who types past the ceiling gets a keystroke that does
     * nothing and no reason for it.
     *
     * @param range {@link SettingsView.NumberRange} the field's own bounds
     * @return {@link String} a sentence naming them
     */
    static String anythingFrom(final SettingsView.NumberRange range) {
        return "Anything from " + range.least() + " to " + range.most() + ".";
    }
}
