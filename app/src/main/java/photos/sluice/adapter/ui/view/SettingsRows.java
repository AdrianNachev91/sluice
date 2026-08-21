package photos.sluice.adapter.ui.view;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsView;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

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

    // Marks the scrolling body on the body itself, so a refusal raised deep in a page can find what
    // scrolls it without every layer in between having to pass it down.
    private static final String SCROLL = "scroll";

    // Long enough to read as travel rather than a jump, short enough that a reader adding several
    // cards is never waiting on it.
    private static final Duration SCROLL_TRAVEL = Duration.millis(180);

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
     * A page's body in the pane that scrolls it. Both settings pages are built this way, so the
     * one that is reached from the other does not arrive with different chrome.
     *
     * @param body {@link VBox} the page's own contents
     * @return {@link ScrollPane} the pane to hand the shell
     */
    static ScrollPane scrolling(final VBox body) {
        final var scroll = new ScrollPane(body);
        scroll.getStyleClass().add("settings-scroll");
        scroll.setFitToWidth(true);
        // Left on the body so a refusal can reach it. A refusal is raised from a button deep in the
        // page, which knows the body it sits in and nothing about what scrolls it.
        body.getProperties().put(SCROLL, scroll);
        return scroll;
    }

    /**
     * Puts a page back at its top, for a save that has just rebuilt it.
     *
     * <p>Instant rather than travelled. The banner saying the save took is already drawn up there,
     * and a reader who pressed Save at the foot should find it waiting rather than watch the page
     * arrive. Asked for outright, because a rebuild only happens to reset the scroll position.
     *
     * @param body {@link VBox} the page's own scrolling body
     */
    static void backToTop(final VBox body) {
        if (body.getProperties().get(SCROLL) instanceof final ScrollPane scroll) {
            scroll.setVvalue(0);
        }
    }

    /**
     * Brings a node the page has just added into view.
     *
     * <p>A page long enough to scroll puts a new card below the fold, so the button that added it
     * appears to have done nothing. Travelled rather than jumped: a jump lands the new card where
     * the old one was, which reads as the card being looked at having been replaced.
     *
     * <p>Scrolled to rather than focused. Focus would take the caret into a field the reader has not
     * chosen to type in yet.
     *
     * @param node {@link Node} the thing just added
     */
    static void bringIntoView(final Node node) {
        scrollTo(node, node, true);
    }

    /**
     * Puts the topmost thing this refusal marked at the top of the view, or the summary when it
     * marked nothing.
     *
     * <p>A refused save does not rebuild the screen, so nothing moves on its own. Save sits at the
     * foot, so the reader is already at the bottom when they press it, and the field the refusal is
     * about is usually somewhere above. Left alone, a refusal marks a row nobody is looking at.
     *
     * <p>The summary is the destination only when nothing else is, because it says what needs
     * fixing is marked under the fields. Sending someone down to read that, when there is a mark
     * above, points them the wrong way.
     *
     * <p>Jumped rather than travelled. This is a correction, not an arrival, and it has to be there
     * the moment the reader looks up.
     *
     * @param summary {@link Label} the page-level message, and the last resort to scroll to
     * @param marks the field marks this refusal set
     */
    static void takeTheReaderToTheFault(final Label summary, final Label... marks) {
        scrollTo(summary, topmostMarkedRow(marks).orElse(summary), false);
    }

    /**
     * Scrolls the body holding one node until another sits at the top of the view.
     *
     * @param inBody {@link Node} anything inside the scrolling body, used to find what scrolls
     * @param target {@link Node} what to bring to the top
     * @param travelled boolean whether to move there over time rather than arrive at once
     */
    private static void scrollTo(final Node inBody, final Node target, final boolean travelled) {
        final Parent body = bodyOf(inBody);
        if (body == null || !(body.getProperties().get(SCROLL) instanceof final ScrollPane scroll)) {
            return;
        }
        // A mark's own row has no height until the mark is measured, and a page that has just grown
        // is taller than the layout every position below the new node was read off.
        scroll.applyCss();
        scroll.layout();
        final double scrollable =
                body.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
        if (scrollable <= 0) {
            return;
        }
        final double top = body.sceneToLocal(target.localToScene(target.getBoundsInLocal())).getMinY();
        final double at = Math.clamp(top / scrollable, 0, 1) * scroll.getVmax();
        if (travelled) {
            new Timeline(new KeyFrame(SCROLL_TRAVEL,
                    new KeyValue(scroll.vvalueProperty(), at, Interpolator.EASE_BOTH))).play();
        } else {
            scroll.setVvalue(at);
        }
    }

    /**
     * The scrolling body the summary sits in, however deeply it is nested.
     *
     * <p>Settings puts the summary straight on the body; the categories page puts it below a row of
     * buttons. Walking up until the scroll marker turns up covers both without either page having
     * to say how deep it built.
     *
     * @param from {@link Node} where to start walking up
     * @return {@link Parent} the body carrying the scroll marker, or null when there is none
     */
    private static @Nullable Parent bodyOf(final Node from) {
        for (Parent at = from.getParent(); at != null; at = at.getParent()) {
            if (at.getProperties().get(SCROLL) instanceof ScrollPane) {
                return at;
            }
        }
        return null;
    }

    /**
     * The highest row on the page carrying one of these marks.
     *
     * <p>Ordered by where each one ended up rather than by the order they were checked in. A row
     * moving on the page then cannot leave this pointing at the wrong one. A mark with nothing to
     * say is invisible, which is what keeps a cleared row out of the answer.
     *
     * <p>Only the marks handed in are candidates. Other things on a screen say what is wrong in the
     * same words and the same red. A stale one of those is not where a refused save should send
     * anybody.
     *
     * @param marks the field marks this refusal set
     * @return {@link Optional} of {@link Node} the row to scroll to, empty where none is marked
     */
    private static Optional<Node> topmostMarkedRow(final Label... marks) {
        return Arrays.stream(marks)
                .filter(Node::isVisible)
                .map(Node::getParent)
                .min(Comparator.comparingDouble(row -> row.localToScene(row.getBoundsInLocal()).getMinY()))
                .map(Node.class::cast);
    }

    /**
     * Stops a control taking more characters than the value behind it will accept.
     *
     * <p>The field refuses the keystroke, so the reader cannot reach a value a save would then
     * refuse. It is the other half of a bound the value type also holds, not a replacement for it:
     * a config file reaches that type without passing any control.
     *
     * <p>A paste that would cross the ceiling is truncated to what fits rather than dropped whole.
     * Losing the tail of a long paste is a visible outcome the reader can act on. Losing the paste
     * is one they read as the app ignoring them.
     *
     * @param field {@link TextInputControl} the field to bound
     * @param characters int the most it may hold
     */
    static void holdTo(final TextInputControl field, final int characters) {
        field.setTextFormatter(new TextFormatter<>(change -> {
            if (!change.isContentChange() || change.getControlNewText().length() <= characters) {
                return change;
            }
            final int room = characters - (change.getControlText().length() - change.getSelection().getLength());
            if (room <= 0) {
                return null;
            }
            change.setText(change.getText().substring(0, Math.min(room, change.getText().length())));
            return change;
        }));
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

    /**
     * One folder row: what it draws, what carries its value, and where a refusal marks it.
     *
     * @param row {@link VBox} the row itself, label and field and violation together
     * @param field {@link TextField} the path as typed
     * @param violation {@link Label} what a refused save says about this root, blank when it passed
     */
    record FolderRow(VBox row, TextField field, Label violation) {
    }

    static FolderRow folderRow(final String label, final String id, final SettingsView.FolderField field,
                               final int limit) {
        final var text = new TextField(field.value());
        holdTo(text, limit);
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
     * The banner a screen puts at its top to say what just happened, and the way to close it early.
     *
     * <p>The stylesheet dresses the label inside the row, not the row itself. So the banner is the
     * row, and the message is a label within it.
     *
     * <p>A short confirmation fades on its own. One left standing is still there the next time
     * something is refused, where it reads as a claim about that. A report carrying counts, a path
     * or a consequence stays instead, since four seconds is not long enough to take one in.
     *
     * @param container {@link VBox} the pane's body, which the banner removes itself from
     * @param id {@link String} the node id a test finds the banner by
     * @param text {@link String} what to say
     * @param fades boolean whether it leaves on its own after a few seconds
     * @return {@link HBox} the banner
     */
    static HBox banner(final VBox container, final String id, final String text, final boolean fades) {
        final var said = new Label(text);
        said.setWrapText(true);
        // Hgrow offers a node the spare room; a maximum width is what lets it take any. A label
        // stops at the width of its own text, leaving the dismiss button against the last word
        // rather than at the end of the banner.
        said.setMaxWidth(Double.MAX_VALUE);
        said.setAlignment(Pos.CENTER);
        HBox.setHgrow(said, Priority.ALWAYS);

        final var dismiss = new Button("×");
        dismiss.getStyleClass().add("settings-banner-dismiss");

        final var banner = new HBox(said, dismiss);
        banner.setId(id);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.getStyleClass().add("settings-banner");

        final Runnable remove = () -> container.getChildren().remove(banner);
        dismiss.setOnAction(_ -> remove.run());

        if (fades) {
            final var fade = new FadeTransition(Duration.millis(400), banner);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setOnFinished(_ -> remove.run());
            final var wait = new PauseTransition(Duration.seconds(4));
            wait.setOnFinished(_ -> fade.play());
            wait.play();
        }
        return banner;
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

    private static Label sectionEyebrow(final String text) {
        final var label = new Label(text);
        label.getStyleClass().addAll("eyebrow", "settings-card-title");
        return label;
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

    private static VBox labeledRow(final String label, final Node field, final @Nullable String overrideNote) {
        final var row = new VBox(fieldLabel(label), field);
        if (overrideNote != null) {
            row.getChildren().add(overrideLabel(overrideNote));
        }
        row.getStyleClass().add("settings-row");
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
}
