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
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
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

    // Marks the scrolling body on the body itself. A refusal raised deep in a page can then find
    // what scrolls it, without every layer in between having to pass it down.
    private static final String SCROLL = "scroll";

    // Long enough to read as travel rather than a jump, short enough that a reader adding several
    // cards is never waiting on it.
    private static final Duration SCROLL_TRAVEL = Duration.millis(180);

    // Marks a spinner as mid-number. Held on the control itself, since it is the control that knows
    // and every page asking the question has one to hand.
    private static final String TYPED_INTO = "typedInto";

    // The one slot a page reports a save in. Named rather than per-tone, so a refusal replaces a
    // confirmation instead of standing beside one.
    static final String REPORT = "settings-report-banner";

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
            final TextArea says = SelectableText.prose(description);
            says.getStyleClass().add("settings-card-intro");
            card.getChildren().add(says);
        }
        card.getChildren().addAll(rows);
        card.getStyleClass().add("card");
        return card;
    }

    /**
     * Has a spinner keep track of whether the reader is in its text or on its arrows.
     *
     * <p>A spinner reports itself as holding focus in both cases, never its editor, so nothing about
     * the focus owner separates them. It has to remember which the reader last reached for.
     *
     * <p>A press inside the text puts the caret there, which is enough on its own. The reader is in
     * the field whether or not they have typed a character yet. A press on an arrow, or either
     * arrow key, is the other case, and the value it lands on is whole at every step.
     *
     * <p>Filters rather than handlers. The editor consumes a typed character before the spinner's
     * own handlers run, so listening on the way up hears nothing.
     *
     * @param spinner {@link Spinner} the field to watch
     */
    private static void rememberWhichInputItLastTook(final Spinner<Integer> spinner) {
        spinner.addEventFilter(KeyEvent.KEY_TYPED, _ -> spinner.getProperties().put(TYPED_INTO, true));
        spinner.addEventFilter(KeyEvent.KEY_PRESSED, pressed -> {
            if (pressed.getCode() == KeyCode.UP || pressed.getCode() == KeyCode.DOWN) {
                spinner.getProperties().put(TYPED_INTO, false);
            }
        });
        spinner.addEventFilter(MouseEvent.MOUSE_PRESSED, pressed ->
                spinner.getProperties().put(TYPED_INTO,
                        pressed.getTarget() instanceof final Node hit && inside(hit, spinner.getEditor())));
    }

    /**
     * Whether a reader is partway through typing a number into this spinner.
     *
     * @param spinner {@link Spinner} the field to ask
     * @return boolean true while the last thing it took was a typed character
     */
    static boolean beingTypedInto(final Spinner<?> spinner) {
        return Boolean.TRUE.equals(spinner.getProperties().get(TYPED_INTO));
    }

    /**
     * Whether one node is another, or sits within it.
     *
     * @param node {@link Node} where the press landed
     * @param within {@link Node} the part being asked about
     * @return boolean true when the press was inside it
     */
    private static boolean inside(final Node node, final Node within) {
        for (Node walk = node; walk != null; walk = walk.getParent()) {
            if (walk == within) {
                return true;
            }
        }
        return false;
    }

    /**
     * A muted line built empty and written onto later, which takes no room at all while it has
     * nothing to say.
     *
     * <p>A screen reserving a row for every line it might one day show would carry those gaps on
     * every screen that has nothing to put in them.
     *
     * <p>Its sibling {@link #helpLine} is the other kind: built with its words and never changed.
     * The two take an id and a sentence respectively, which is a difference nothing but the name
     * would show at a call site.
     *
     * @param id {@link String} the control's id
     * @return {@link TextArea} the line
     */
    static TextArea emptyHelpLine(final String id) {
        final TextArea line = SelectableText.prose();
        line.setId(id);
        line.getStyleClass().add("settings-help");
        showWhileItSaysSomething(line);
        return line;
    }

    /**
     * Puts what a screen has to report on the line it reports from.
     *
     * <p>Only a refusal wears the caution colour, and the class comes off again on every fill. A
     * report that worked would otherwise be dressed as whatever the last refusal was.
     *
     * @param line {@link TextArea} the screen's own report line
     * @param said {@link Message} what to report, or null for nothing
     * @param caution {@link String} the style class a refusal wears on this screen
     */
    static void report(final TextArea line, final @Nullable Message said, final String caution) {
        line.setText(said == null ? "" : said.text());
        line.getStyleClass().remove(caution);
        if (said != null && said.refused()) {
            line.getStyleClass().add(caution);
        }
    }

    /**
     * Turns a fold's marker to say which way it is, putting one on the first time it is asked.
     *
     * <p>A fold's control is a plain label, and nothing about a label says it can be pressed. The
     * marker is the whole of that affordance.
     *
     * @param toggle {@link Button} the fold's own control
     * @param open boolean whether the section below it is showing
     */
    static void pointing(final Button toggle, final boolean open) {
        if (toggle.getGraphic() == null) {
            toggle.setGraphic(foldMarker());
        }
        toggle.getGraphic().setRotate(open ? 90 : 0);
    }

    /**
     * The marker on a fold: a triangle, drawn rather than typed so no font has to carry it.
     *
     * @return {@link Polygon} the triangle, pointing right
     */
    private static Polygon foldMarker() {
        final var triangle = new Polygon(0, 0, 0, 8, 6, 4);
        triangle.getStyleClass().add("fold-marker");
        return triangle;
    }

    /**
     * A button that asks before it acts.
     *
     * <p>The question is asked before anything happens, so a reader who backs out leaves what the
     * press was about exactly as it was.
     *
     * @param id {@link String} the button's own id
     * @param label {@link String} what it says
     * @param leading boolean whether this is the way on, drawn to be reached for
     * @param confirm {@link Confirmation} what to ask first, or null where it needs no asking
     * @param press {@link Runnable} what a reader who agreed asked for
     * @return {@link Button} the button
     */
    static Button actionButton(final String id, final String label, final boolean leading,
                               final @Nullable Confirmation confirm, final Runnable press) {
        final var button = new Button(label);
        button.setId(id);
        button.getStyleClass().add(leading ? "run-start" : "run-cancel");
        button.setOnAction(_ -> {
            if (Dialogs.agreed(button, confirm)) {
                press.run();
            }
        });
        return button;
    }

    /**
     * A help line opening with a mark drawn in the mark's own colour rather than the line's.
     *
     * <p>The mark is a separate label because a label draws its text in one colour. Wrapped lines
     * then sit under the sentence rather than under the mark, which is what a footnote wants.
     *
     * @param line {@link Region} the sentence, which decides whether the whole row is showing
     * @param glyph {@link String} the mark to open with
     * @param markClasses the style classes carrying the mark's colour and its gap
     * @return {@link HBox} the line, taking room only while the sentence says something
     */
    static HBox markedHelpLine(final Region line, final String glyph, final String... markClasses) {
        final TextField mark = SelectableText.line(glyph);
        mark.getStyleClass().add("settings-help");
        mark.getStyleClass().addAll(markClasses);
        final var row = new HBox(mark, line);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(line, Priority.ALWAYS);
        row.managedProperty().bind(row.visibleProperty());
        row.visibleProperty().bind(line.visibleProperty());
        return row;
    }

    /**
     * A help line with a word or two inside it leading somewhere else in this app.
     *
     * <p>A flow rather than a row, so the sentence wraps across the link the way prose does. Three
     * pieces, because a label draws its whole text one way and only the middle should look
     * pressable.
     *
     * @param id {@link String} the line's own id
     * @param link {@link Hyperlink} the words that lead there, already wired
     * @return {@link LinkedLine} the line and the two stretches of prose around the link
     */
    static LinkedLine linkedHelpLine(final String id, final Hyperlink link) {
        final var before = new Text();
        final var after = new Text();
        before.getStyleClass().add("linked-text-word");
        after.getStyleClass().add("linked-text-word");
        final var flow = new TextFlow(before, link, after);
        flow.setId(id);
        flow.getStyleClass().add("settings-help");
        flow.managedProperty().bind(flow.visibleProperty());
        flow.visibleProperty().bind(before.textProperty().isNotEmpty());
        return new LinkedLine(flow, before, after);
    }

    /**
     * A help line built around a link, and the prose either side of it.
     *
     * @param flow {@link TextFlow} the line itself
     * @param before {@link Text} what is said ahead of the link
     * @param after {@link Text} what is said after it
     */
    record LinkedLine(TextFlow flow, Text before, Text after) {
    }

    /**
     * A few words that open another screen when pressed.
     *
     * <p>The toolkit's own link, undressed. Its visited state and its standing underline both say
     * something about a page left behind, and a screen in this app is not that.
     *
     * @param id {@link String} the link's own id
     * @param goes {@link Runnable} what it opens
     * @return {@link Hyperlink} the words, taking room only while they say something
     */
    static Hyperlink inAppLink(final String id, final Runnable goes) {
        final var link = new Hyperlink();
        link.setId(id);
        link.getStyleClass().add("in-app-link");
        link.setOnAction(_ -> goes.run());
        link.managedProperty().bind(link.visibleProperty());
        link.visibleProperty().bind(link.textProperty().isNotEmpty());
        return link;
    }

    /**
     * Has a label take up room only while it carries text.
     *
     * @param line {@link TextInputControl} the line to bind
     */
    static void showWhileItSaysSomething(final TextInputControl line) {
        line.managedProperty().bind(line.visibleProperty());
        line.visibleProperty().bind(line.textProperty().isNotEmpty());
    }

    /**
     * Has a label take up room only while it carries text.
     *
     * @param line {@link Labeled} the line to bind
     */
    static void showWhileItSaysSomething(final Labeled line) {
        line.managedProperty().bind(line.visibleProperty());
        line.visibleProperty().bind(line.textProperty().isNotEmpty());
    }

    /**
     * Text for a label, where nothing to say is an empty string rather than a missing one.
     *
     * @param said what the presenter had, or null where it had nothing
     * @return {@link String} what to put in the label
     */
    static String orNothing(final @Nullable String said) {
        return said == null ? "" : said;
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
     * Puts a report at the head of a page, in the tone the outcome deserves.
     *
     * <p>Every outcome of a save is said the same way, so none of them reads as less finished than
     * the others. A refusal left on its own line in the header was the one report with no ground of
     * its own.
     *
     * <p>Replaces whatever this page was saying before. Two reports of the same save stacked up
     * would leave the reader deciding which one is current.
     *
     * <p>A refusal and a caution stay until they are dismissed. Only a plain confirmation fades: it
     * says a thing the reader already knows they asked for, and there is nothing in it to act on.
     *
     * @param body {@link VBox} the page's own scrolling body
     * @param tone the style class saying which kind of report this is, or null for a plain
     *     confirmation
     * @param message {@link String} what to say
     * @param fades boolean whether it leaves on its own after a few seconds
     */
    static void report(final VBox body, final @Nullable String tone, final String message,
                       final boolean fades) {
        clearReport(body);
        final HBox said = banner(body, REPORT, message, fades);
        if (tone != null) {
            said.getStyleClass().add(tone);
        }
        body.getChildren().addFirst(said);
        travelToTop(body);
    }

    /**
     * Takes whatever a page was last saying off it.
     *
     * @param body {@link VBox} the page's own scrolling body
     */
    static void clearReport(final VBox body) {
        body.getChildren().removeIf(node -> REPORT.equals(node.getId()));
    }

    /**
     * Carries the reader up to the top of the page.
     *
     * <p>Travelled rather than jumped, so a save reads as the page moving rather than as a
     * different screen appearing.
     *
     * <p>Nothing puts the reader back where they were first. A scrolling pane keeps its own place
     * across a rebuild, adjusting what it holds so the same contents stay in view. It clamps to the
     * end where the new page is too short to reach the old position. Both are what a reader would
     * expect, so the movement starts from where they actually are.
     *
     * @param body {@link VBox} the page's own scrolling body
     */
    static void travelToTop(final VBox body) {
        if (body.getProperties().get(SCROLL) instanceof final ScrollPane scroll) {
            travelTo(scroll, 0);
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
        scrollTo(node, node);
    }

    /**
     * Scrolls the body holding one node until another sits at the top of the view.
     *
     * <p>Travelled rather than jumped. Every caller is an arrival at something the reader asked
     * for, so the movement is what tells them where it came from.
     *
     * @param inBody {@link Node} anything inside the scrolling body, used to find what scrolls
     * @param target {@link Node} what to bring to the top
     */
    private static void scrollTo(final Node inBody, final Node target) {
        final Parent body = bodyOf(inBody);
        if (body == null || !(body.getProperties().get(SCROLL) instanceof final ScrollPane scroll)) {
            return;
        }
        // A page that has just grown is taller than the layout every position below the new node
        // was read off.
        scroll.applyCss();
        scroll.layout();
        final double scrollable = scrollableIn(body, scroll);
        if (scrollable <= 0) {
            return;
        }
        final double top = body.sceneToLocal(target.localToScene(target.getBoundsInLocal())).getMinY();
        travelTo(scroll, Math.clamp(top / scrollable, 0, 1) * scroll.getVmax());
    }

    /**
     * Moves a pane to a position over {@link #SCROLL_TRAVEL} rather than at once.
     *
     * @param scroll {@link ScrollPane} what to move
     * @param at double the vvalue to arrive at
     */
    private static void travelTo(final ScrollPane scroll, final double at) {
        // A page already there would otherwise run an animation that shows nothing, and hold the
        // reader for its duration before whatever comes next.
        if (scroll.getVvalue() == at) {
            return;
        }
        new Timeline(new KeyFrame(SCROLL_TRAVEL,
                new KeyValue(scroll.vvalueProperty(), at, Interpolator.EASE_BOTH))).play();
    }

    /**
     * How many pixels of a page sit outside the view.
     *
     * @param body {@link Parent} the scrolling body
     * @param scroll {@link ScrollPane} the pane holding it
     * @return double the scrollable height, zero or less where the page fits
     */
    private static double scrollableIn(final Parent body, final ScrollPane scroll) {
        return body.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
    }

    /**
     * The scrolling body a node sits in, however deeply it is nested.
     *
     * <p>Walking up until the scroll marker turns up means no page has to say how deep it built.
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
     * Stops a control taking more characters than the value behind it will accept.
     *
     * <p>The field refuses the keystroke, so the reader cannot reach a value a save would then
     * refuse. It is the other half of a bound the value type also holds, never a replacement for it.
     * A config file reaches that type without passing any control.
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
     * @return {@link TextField} the heading
     */
    static TextField subsectionHeading(final String text) {
        final TextField heading = SelectableText.line(text);
        heading.getStyleClass().add("settings-subsection-heading");
        return heading;
    }

    /**
     * A field's own name. Carries a style class rather than none, since a JavaFX control left
     * unstyled draws its text in a fixed grey that only suits the light look.
     *
     * @param text {@link String} what the label says
     * @return {@link TextField} the styled label
     */
    static TextField fieldLabel(final String text) {
        final TextField label = SelectableText.line(text);
        label.getStyleClass().add("settings-field-label");
        return label;
    }

    /**
     * One folder row: what it draws, what carries its value, and where a refusal marks it.
     *
     * @param row {@link VBox} the row itself, label and field and violation together
     * @param field {@link TextField} the path as typed
     * @param violation {@link TextArea} what a refused save says about this root, blank when it
     *                  passed
     */
    record FolderRow(VBox row, TextField field, TextArea violation) {
    }

    static FolderRow folderRow(final String label, final String explanation, final String id,
                               final SettingsView.FolderField field, final int limit) {
        final var text = new TextField(field.value());
        holdTo(text, limit);
        text.setId(id);
        text.setPromptText(field.suggestion());

        acceptSuggestionOnArrowRight(text, field.suggestion());
        final var fieldRow = new HBox(text, useSuggestedButton(text, field.suggestion()),
                browseButton(text, field.suggestion()));
        fieldRow.getStyleClass().add("settings-field-row");
        // A folder path is as long as it is, and the ones a user cares about are the long ones. The
        // field takes whatever width the row has left rather than truncating at a default.
        HBox.setHgrow(text, Priority.ALWAYS);

        final var violation = violationLabel();
        markWhileSomethingIsWrong(text, violation);
        say(violation, field.violation());

        final var children = new VBox(fieldLabel(label), helpLine(explanation), fieldRow, violation);
        children.getStyleClass().add("settings-row");
        return new FolderRow(children, text, violation);
    }

    /**
     * A button that takes the folder this row suggests, for a reader who would rather click than
     * type it out.
     *
     * <p>Disabled once the field holds anything, rather than appearing and disappearing. A control
     * that comes and goes resizes the field beside it on the first character typed.
     *
     * <p>Suggested, never "default". These roots have no configured default at all, which is why an
     * install with none set meets the first-run card instead of running against guessed folders.
     * A button calling them defaults would claim a property the app does not have.
     *
     * @param text {@link TextField} the field to fill
     * @param suggestion {@link String} the folder this row suggests
     * @return {@link Button} the button
     */
    private static Button useSuggestedButton(final TextField text, final String suggestion) {
        final var use = new Button("Use suggested");
        use.getStyleClass().add("button-suggest");
        use.disableProperty().bind(text.textProperty().isNotEmpty());
        use.setOnAction(_ -> text.setText(suggestion));
        return use;
    }

    /**
     * Takes the suggestion on the key a reader would already try for ghost text.
     *
     * <p>Right and End, never Tab. Tab moves focus, and a form whose fields fill themselves as
     * somebody tabs past would configure folders nobody chose. Both keys do nothing in an empty
     * field otherwise, so neither is taken away from anything.
     *
     * <p>Only while the field is empty. Once it holds a path, Right and End are how a reader moves
     * around inside it.
     *
     * @param text {@link TextField} the field to fill
     * @param suggestion {@link String} the folder this row suggests
     */
    private static void acceptSuggestionOnArrowRight(final TextField text, final String suggestion) {
        text.addEventFilter(KeyEvent.KEY_PRESSED, key -> {
            if (text.getText().isEmpty() && (key.getCode() == KeyCode.RIGHT || key.getCode() == KeyCode.END)) {
                text.setText(suggestion);
                text.positionCaret(suggestion.length());
                key.consume();
            }
        });
    }

    /**
     * The line saying what the mark on those field names means.
     *
     * <p>Said as a sentence rather than marked on each row. A mark on a field means a form will
     * not submit without it, and this one does: the folders can be filled in over several saves.
     * What is actually true is that nothing runs until all three are set, and this is where that is
     * said.
     *
     * <p>Placed by whichever card draws the rows, and never further away than the rows themselves.
     * A mark whose legend is on another screen explains nothing.
     *
     * @return {@link TextArea} the legend
     */
    static TextArea requiredLegend() {
        final TextArea legend = SelectableText.prose("Sluice cannot start any work on your photos "
                + "until all three are set. You can fill them in one at a time and save as you go.");
        legend.setId("folder-roots-required-legend");
        legend.getStyleClass().add("settings-required-legend");
        return legend;
    }

    /**
     * Puts a message under a field, or takes the one that is there away.
     *
     * @param violation {@link TextArea} the row's own violation label
     * @param message what is wrong with this field, or null when nothing is
     */
    static void say(final TextArea violation, final @Nullable String message) {
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
     * @param violation {@link TextArea} the message under it
     */
    static void markWhileSomethingIsWrong(final Node field, final TextArea violation) {
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
     * @return {@link TextArea} the line a refusal fills
     */
    static TextArea violationLabel() {
        final TextArea violation = SelectableText.prose();
        violation.getStyleClass().add("settings-violation");
        showWhileItSaysSomething(violation);
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
        final TextArea said = SelectableText.prose(text);
        said.getStyleClass().add("settings-banner-text");
        // Hgrow offers a node the spare room; a maximum width is what lets it take any. A label
        // stops at the width of its own text, leaving the dismiss button against the last word
        // rather than at the end of the banner.
        said.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(said, Priority.ALWAYS);

        final var dismiss = new Button("×");
        dismiss.getStyleClass().add("settings-banner-dismiss");

        final var banner = new HBox(said, dismiss);
        banner.setId(id);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.getStyleClass().add("settings-banner");
        // A row stretches its children to the tallest of them, which here is the dismiss button.
        // Wrapping text lays its words against its own top edge. Stretched past what they need,
        // the sentence sits above the middle of the ground behind it.
        banner.setFillHeight(false);

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
        final TextArea label = SelectableText.prose(text);
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

    /**
     * Puts a block of text on the info ground, with the mark standing on its top-left corner.
     *
     * <p>The mark sits on the border rather than inside the box. Inside, it takes a column of the
     * first line and leaves every line after it indented past nothing. It carries the card's own
     * colour, which is what lets it break the border it stands on instead of printing over it.
     *
     * @param content {@link Region} what the box holds
     * @return {@link Node} the box, ready to sit in a card
     */
    static Node badgedCallout(final Region content) {
        content.getStyleClass().add("settings-callout");
        final StackPane badge = infoGlyph();
        badge.getStyleClass().add("callout-badge");
        final var framed = new StackPane(content, badge);
        StackPane.setAlignment(badge, Pos.TOP_LEFT);
        StackPane.setMargin(badge, new Insets(-8, 0, 0, 13));
        // A box with something standing on its edge needs room above it, or the badge reads as
        // belonging to whatever line sits there.
        VBox.setMargin(framed, new Insets(12, 0, 0, 0));
        return framed;
    }

    static TextArea overrideLabel(final String text) {
        final TextArea label = SelectableText.prose(text);
        label.getStyleClass().add("settings-override-note");
        return label;
    }

    /**
     * One line of quiet explanation under a control.
     *
     * @param text {@link String} what it says
     * @return {@link TextArea} the line, wrapping at the row's width
     */
    static TextArea helpLine(final String text) {
        final TextArea line = SelectableText.prose(text);
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
        rememberWhichInputItLastTook(spinner);
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

    private static TextField sectionEyebrow(final String text) {
        final TextField eyebrow = SelectableText.line(text);
        eyebrow.getStyleClass().addAll("eyebrow", "settings-card-title");
        return eyebrow;
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
        browse.getStyleClass().add("button-last-resort");
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
