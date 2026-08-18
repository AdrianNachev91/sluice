package photos.sluice.adapter.ui.view;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;
import photos.sluice.adapter.ui.SettingsView;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Settings screen: folder roots, the vision provider and its credential, and the montage grid.
 *
 * <p>Every value it shows and every note under a field comes from {@link SettingsPresenter}. This
 * class lays those out and forwards a click or an edit back to the presenter. It never decides on
 * its own what a field means or whether it is valid.
 */
final class SettingsPane {

    private static final String SAVED = "Settings saved.";
    private static final String SCROLL = "scroll";

    // A field the screen has something to say about. A pseudo-class rather than a style class,
    // because it is a state the control is in rather than a kind of control it is.
    private static final PseudoClass REFUSED = PseudoClass.getPseudoClass("refused");

    // An eye, and the stroke that crosses it out. SVG path data, the same format BrandMark uses and
    // the one SVGPath takes: letters are drawing commands, numbers their coordinates. Drawn rather
    // than written, because every password field a user has met uses this and none spell it out.
    //
    // The stroke is a shape rather than a line, and its own node rather than more of the eye's
    // path. An SVGPath is filled, so a line contributes nothing at all. A shape appended to the
    // eye's path is cancelled where the two overlap, by the non-zero winding rule. Measured: as one
    // path the crossed-out eye is pixel-identical to the open one.
    private static final String EYE = "M12 5C5 5 2 12 2 12s3 7 10 7 10-7 10-7-3-7-10-7zm0 12a5 5 0 "
            + "110-10 5 5 0 010 10zm0-8a3 3 0 100 6 3 3 0 000-6z";
    // Its four corners average to the eye's own centre, which is what puts the stroke through the
    // pupil rather than across a corner.
    private static final String CROSSED_OUT = "M2.6 4.4l1.8-1.8 17 17-1.8 1.8z";

    /**
     * Prevents instantiation of this static factory class.
     */
    private SettingsPane() {
    }

    /**
     * Builds the pane, ready to sit in the shell's content area. Rebuilds its own contents after
     * every save or credential action, so what is on screen always reflects a fresh read.
     *
     * @param presenter {@link SettingsPresenter} supplies what to show and carries out what is done
     * @return {@link Node} the settings pane
     */
    static Node pane(final SettingsPresenter presenter) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        refresh(container, presenter, null);

        final var scroll = new ScrollPane(container);
        scroll.getStyleClass().add("settings-scroll");
        scroll.setFitToWidth(true);
        // Left on the body so a refusal can reach it. The refusal is raised from a button deep in
        // the page, which knows the body it sits in and nothing about what scrolls it.
        container.getProperties().put(SCROLL, scroll);
        return scroll;
    }

    /**
     * Draws the screen from what the presenter says, replacing whatever was there.
     *
     * <p>A save rebuilds rather than patching, so every field shows what was actually stored. The
     * banner is how a message survives that: the label carrying it is one of the things replaced.
     *
     * @param container {@link VBox} the pane's own body
     * @param presenter {@link SettingsPresenter} supplies the state and takes the actions
     * @param banner what to say above the screen about what just happened, or null for nothing
     */
    private static void refresh(final VBox container, final SettingsPresenter presenter,
                                final @Nullable String banner) {
        final SettingsView view = presenter.view();
        final var heading = new Label("Settings");
        heading.getStyleClass().add("pane-heading");

        final var workingRoot = folderRow("Working root", "settings-working-root", view.workingRoot());
        final var libraryRoot = folderRow("Library root", "settings-library-root", view.libraryRoot());
        final var inbox = folderRow("Inbox", "settings-inbox", view.inbox());
        final var foldersCard = card("FOLDERS",
                "Working root is where Sluice works. It makes its own folders underneath for what it "
                        + "has sorted, what it wants you to look at, and the near-duplicates it set "
                        + "aside. Library root is where the photos you keep end up for good. Inbox is "
                        + "where new photos go in, and it usually sits inside the working root.",
                workingRoot.row(), libraryRoot.row(), inbox.row());

        final var providerBox = providerChoice(view);
        providerBox.setId("settings-provider");
        final var providerFields = providerFields(view);
        providerFields.setId("settings-provider-fields");
        final var watchRow = watchModeRow(view);
        watchRow.setId("settings-watch-mode");
        final var secretCard = new VBox();
        secretCard.setId("settings-api-key");
        secretCard.getStyleClass().add("settings-subsection");
        fillSecretCard(secretCard, presenter, providerBox, null);
        final var providerCard = card("VISION PROVIDER",
                "What actually looks at your photos and decides what is junk, a duplicate, or worth "
                        + "keeping. Sluice has no judgement of its own. It either calls a model you pay for, "
                        + "or waits for an agent you already run to do the looking. That agent writes its "
                        + "answers into a folder, so it has to be one that can work with files rather than "
                        + "only chat.",
                providerRow(providerBox, view.providerOverride(), view.providerUnrecognised()),
                providerFields, watchRow, secretCard);

        final var status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("settings-save-status");

        // Every provider shares one set of controls, so what the chosen one does not use is hidden
        // rather than rebuilt. A rebuild reads the saved settings back, wiping anything typed but
        // not yet saved. That is every field on the page, not only this card's.
        showOnlyWhatTheProviderUses(providerChoiceOf(providerBox).fields(), providerFields, watchRow, secretCard);
        providerBox.getSelectionModel().selectedItemProperty().addListener((_, _, chosen) -> {
            // A refusal answers one press of Save against one set of choices. Changing the provider
            // changes which fields exist and what they must hold. What the last one said is then
            // about a different screen.
            clearRefusal(status, workingRoot.violation(), libraryRoot.violation(), inbox.violation(),
                    controlsOf(providerFields).modelViolation());
            showOnlyWhatTheProviderUses(chosen.fields(), providerFields, watchRow, secretCard);
            // The one part that is rebuilt rather than toggled. A credential belongs to the provider
            // it authenticates, so what this block says and acts on has to change with the choice.
            fillSecretCard(secretCard, presenter, providerBox, null);
        });

        final var tileSize = numberField(view.tileSizeRange(), view.tileSize());
        tileSize.setId("settings-tile-size");
        final var tilesPerRow = numberField(view.tilesPerRowRange(), view.tilesPerRow());
        tilesPerRow.setId("settings-tiles-per-row");
        final var montageCard = card("PHOTO SHEETS",
                "Photos are not sent one at a time. Sluice tiles them into sheets, and whatever "
                        + "is doing the looking reads a sheet at a time. The defaults are a balance that "
                        + "works; both settings below trade something away.",
                explainedRow("Tile size (pixels)",
                        "How large each photo is drawn on the sheet. Bigger catches the faint junk that "
                                + "small tiles miss, like screenshots and photos of documents, and costs more "
                                + "per photo. What you pay follows this number, not how many fit on a sheet. "
                                + anythingFrom(view.tileSizeRange()),
                        tileSize, view.tileSizeOverride()),
                explainedRow("Photos per row",
                        "How many share one sheet. More means fewer sheets and a quicker, cheaper run, and "
                                + "draws every photo smaller, so more of the faint junk goes unnoticed. "
                                + anythingFrom(view.tilesPerRowRange()),
                        tilesPerRow, view.tilesPerRowOverride()));

        final var themeBox = themeChoices(view, presenter);
        themeBox.setId("settings-theme");
        final var appearanceCard = card("APPEARANCE", null,
                inlineLabeledRow("Theme", themeBox, view.themeOverride()));

        final var save = new Button("Save");
        save.setId("settings-save-button");
        save.setOnAction(_ -> onSave(presenter, workingRoot, libraryRoot, inbox,
                providerChoiceOf(providerBox), providerFields, watchAutomaticallyOf(watchRow), tileSize.getValue(),
                tilesPerRow.getValue(), themeChoiceOf(themeBox), status,
                said -> refresh(container, presenter, said)));

        // The secret card belongs to the provider card, not here. A node named in two parents lands
        // in whichever claimed it last, so adding it would quietly lift it out of the provider card.
        container.getChildren().setAll(heading, foldersCard, providerCard, montageCard,
                appearanceCard, save, status);
        if (banner != null) {
            container.getChildren().add(1, savedBanner(container, banner));
        }
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
    private static String anythingFrom(final SettingsView.NumberRange range) {
        return "Anything from " + range.least() + " to " + range.most() + ".";
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
     * null. So the formatter refuses what the converter cannot take, which is the reasoning
     * {@link #withinLimit} applies to the retry field.
     *
     * <p>Only the ceiling is enforced while typing. Every number passes through its own shorter
     * prefixes on the way to being typed, so refusing those would make anything above the floor
     * unreachable. The spinner clamps a value under the floor when it commits.
     *
     * @param range {@link SettingsView.NumberRange} what this field accepts
     * @param value int the value to open on
     * @return {@link Spinner} of {@link Integer} the field
     */
    private static Spinner<Integer> numberField(final SettingsView.NumberRange range, final int value) {
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
     * Hides every control the chosen provider has no use for.
     *
     * <p>Which those are is the chosen {@link SettingsView.ProviderChoice}'s own answer; this only
     * applies it. A hidden control is unmanaged too, or the layout keeps its gap and the card reads
     * as though something failed to draw.
     *
     * @param fields {@link SettingsView.ProviderFields} which settings the chosen provider uses
     * @param providerFields {@link VBox} the model, endpoint, thinking and retries rows
     * @param watchRow {@link VBox} the watch-mode row
     * @param secretCard {@link VBox} the credential card
     */
    private static void showOnlyWhatTheProviderUses(final SettingsView.ProviderFields fields,
                                                    final VBox providerFields, final VBox watchRow,
                                                    final VBox secretCard) {
        final ProviderFieldControls controls = controlsOf(providerFields);
        showIf(fields.model(), controls.model().getParent());
        showIf(fields.endpoint(), controls.endpoint().getParent());
        showIf(fields.thinking(), controls.thinking());
        showIf(fields.retries(), controls.maxRetries().getParent());
        showIf(fields.watchMode(), watchRow);
        showIf(fields.credential(), secretCard);
        showIf(fields.model() || fields.endpoint() || fields.thinking() || fields.retries(), providerFields);
    }

    private static void showIf(final boolean wanted, final Node node) {
        node.setVisible(wanted);
        node.setManaged(wanted);
    }

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
    private static VBox card(final String eyebrow, final @Nullable String description, final Node... rows) {
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
    private static Label subsectionHeading(final String text) {
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
    private static Label fieldLabel(final String text) {
        final var label = new Label(text);
        label.getStyleClass().add("settings-field-label");
        return label;
    }

    private record FolderRow(VBox row, TextField field, Label violation) {
    }

    private static FolderRow folderRow(final String label, final String id,
                                       final SettingsView.FolderField field) {
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
    private static void say(final Label violation, final @Nullable String message) {
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
    private static void markWhileSomethingIsWrong(final Node field, final Label violation) {
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

    private static ComboBox<SettingsView.ProviderChoice> providerChoice(final SettingsView view) {
        final var box = new ComboBox<SettingsView.ProviderChoice>();
        box.getItems().setAll(view.providers());
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(final SettingsView.@Nullable ProviderChoice choice) {
                return choice == null ? "" : choice.label();
            }

            @Override
            public SettingsView.ProviderChoice fromString(final String label) {
                throw new UnsupportedOperationException("this converter is display-only");
            }
        });
        // The presenter guarantees view.provider() names one of view.providers(); it resolves an
        // unrecognized configured id to a known one before this ever runs.
        box.getSelectionModel().select(view.providers().stream()
                .filter(choice -> choice.id().equals(view.provider())).findFirst().orElseThrow());
        return box;
    }

    /**
     * The provider dropdown, with whatever outranks it noted underneath.
     *
     * <p>No label of its own, where every other control has one. The panel is named for this
     * setting, so a second name above the control would read as a different one.
     *
     * @param box {@link ComboBox} of {@link SettingsView.ProviderChoice} the dropdown
     * @param overrideNote a note about what outranks this value, or null
     * @param unrecognisedNote a note that the configured provider is not one this install has, or
     *     null
     * @return {@link VBox} the row
     */
    private static VBox providerRow(final ComboBox<SettingsView.ProviderChoice> box,
                                    final @Nullable String overrideNote,
                                    final @Nullable String unrecognisedNote) {
        final var row = new VBox(box);
        // Above the precedence note. The two can both apply, and this one is the reason the
        // dropdown reads as it does.
        if (unrecognisedNote != null) {
            row.getChildren().add(cautionRow(unrecognisedNote));
        }
        if (overrideNote != null) {
            row.getChildren().add(overrideLabel(overrideNote));
        }
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * The look a user asked for, as one radio button per option, on a single line.
     *
     * <p>A dropdown hides every option but the chosen one. There are three, they are short, and
     * they are the kind of thing a user changes by comparing rather than by knowing.
     *
     * <p>On one line rather than stacked, which the watch-mode options cannot be: those are
     * sentences, and these are one or two words each.
     *
     * @param view {@link SettingsView} the state to draw
     * @return {@link HBox} the choices, each button carrying the option it stands for
     */
    private static HBox themeChoices(final SettingsView view, final SettingsPresenter presenter) {
        final var group = new ToggleGroup();
        final var choices = new HBox();
        choices.getStyleClass().add("settings-choices-inline");
        for (final SettingsView.ThemeOption option : view.themes()) {
            final var button = new RadioButton(option.label());
            button.setToggleGroup(group);
            button.setUserData(option.id());
            button.setSelected(option.id().equals(view.theme()));
            choices.getChildren().add(button);
        }
        // After the loop, so the initial selection above does not fire this as though it were a
        // click. A radio saves the instant it is picked, and the screen opening is not a pick.
        group.selectedToggleProperty().addListener((_, _, selected) -> {
            if (selected != null) {
                presenter.chooseTheme((String) selected.getUserData());
            }
        });
        return choices;
    }

    private static String themeChoiceOf(final HBox choices) {
        return (String) choices.getChildren().stream()
                .map(RadioButton.class::cast)
                .filter(RadioButton::isSelected)
                .findFirst()
                .orElseThrow()
                .getUserData();
    }

    private record ProviderFieldControls(TextField model, TextField endpoint, CheckBox thinking,
                                         TextField maxRetries, Label modelViolation) {
    }

    private static VBox providerFields(final SettingsView view) {
        final var model = new TextField(view.model() == null ? "" : view.model());
        model.setId("settings-model");
        final var endpoint = new TextField(view.endpoint() == null ? "" : view.endpoint());
        final var thinking = new CheckBox("Allow the model to reason before answering");
        thinking.setSelected(view.thinking());
        final var maxRetries = new TextField(view.maxRetries() == null ? "" : view.maxRetries().toString());
        withinLimit(maxRetries, view.maxRetriesLimit());

        // Built whether or not there is anything to say, so a refused save can fill it without
        // rebuilding the row. It takes no space while empty.
        final var modelViolation = violationLabel();
        markWhileSomethingIsWrong(model, modelViolation);

        final var modelRow = explainedRow("Model",
                "Which model reads your photos.",
                model, view.modelOverride());
        modelRow.getChildren().add(modelViolation);

        final var box = new VBox(
                modelRow,
                explainedRow("Endpoint",
                        "Leave empty unless you are pointing Sluice at something other than the provider's own "
                                + "service, such as a proxy on your network.", endpoint, view.endpointOverride()),
                noted(thinking, view.thinkingOverride()),
                explainedRow("Retries",
                        "How many times to try again when the connection fails, before Sluice gives up and tells "
                                + "you. Nothing to do with a model refusing a photo.", maxRetries,
                        view.maxRetriesOverride()));
        box.getStyleClass().add("settings-group");
        box.getProperties().put("controls",
                new ProviderFieldControls(model, endpoint, thinking, maxRetries, modelViolation));
        return box;
    }

    /**
     * Refuses anything the presenter's limit would not accept, keystroke by keystroke.
     *
     * <p>Which values are legal is the presenter's call and arrives as {@code limit}. This only
     * enforces it at the one place a user can type. Filtering rather than reporting on save is what
     * keeps a word from ever becoming a retry count downstream.
     *
     * @param field {@link TextField} the field to constrain
     * @param limit int the largest value this field accepts
     */
    private static void withinLimit(final TextField field, final int limit) {
        final int digits = String.valueOf(limit).length();
        field.setTextFormatter(new TextFormatter<>(change -> {
            final String proposed = change.getControlNewText();
            if (!proposed.matches("\\d{0," + digits + "}")) {
                return null;
            }
            return proposed.isEmpty() || Integer.parseInt(proposed) <= limit ? change : null;
        }));
    }

    private static ProviderFieldControls controlsOf(final VBox providerFields) {
        return (ProviderFieldControls) providerFields.getProperties().get("controls");
    }

    private static VBox watchModeRow(final SettingsView view) {
        final var group = new ToggleGroup();
        final var manual = new RadioButton("Wait for me before moving any photos");
        manual.setToggleGroup(group);
        final var watch = new RadioButton("Move the photos without asking me");
        watch.setToggleGroup(group);
        (view.watchAutomatically() ? watch : manual).setSelected(true);
        // The choices sit in their own box, so a note added below lands under the pair rather than
        // against the last option. At one spacing they read as a remark about that option alone.
        final var choices = new VBox(watch, manual);
        choices.getStyleClass().add("settings-choices");
        final var help = new Label("Culling only writes decisions down. Moving the photos happens "
                + "afterwards, and this is whether Sluice waits for you before it starts.");
        help.setWrapText(true);
        help.getStyleClass().add("settings-help");
        final var box = new VBox(fieldLabel("When an external agent has finished culling"), help, choices);
        box.getStyleClass().add("settings-row");
        if (view.watchModeOverride() != null) {
            box.getChildren().add(overrideLabel(view.watchModeOverride()));
        }
        box.getProperties().put("watchToggle", watch);
        return box;
    }

    private static boolean watchAutomaticallyOf(final VBox watchRow) {
        final var watchToggle = (RadioButton) watchRow.getProperties().get("watchToggle");
        return watchToggle.isSelected();
    }

    private static SettingsView.ProviderChoice providerChoiceOf(final ComboBox<SettingsView.ProviderChoice> box) {
        return box.getSelectionModel().getSelectedItem();
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
    private static VBox explainedRow(final String label, final String explanation, final Node field,
                                     final @Nullable String overrideNote) {
        final var row = labeledRow(label, field, overrideNote);
        final var help = new Label(explanation);
        help.setWrapText(true);
        help.getStyleClass().add("settings-help");
        row.getChildren().add(1, help);
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
    private static VBox inlineLabeledRow(final String label, final Node field,
                                         final @Nullable String overrideNote) {
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
     * A control that names itself, with whatever outranks it noted underneath.
     *
     * <p>For a checkbox, whose own text is its label, so a row that adds one above it would draw a
     * name twice.
     *
     * @param control {@link Node} the control
     * @param overrideNote a note about what outranks this value, or null
     * @return {@link Node} the control, wrapped only when there is a note to carry
     */
    private static Node noted(final Node control, final @Nullable String overrideNote) {
        if (overrideNote == null) {
            return control;
        }
        final var row = new VBox(control, overrideLabel(overrideNote));
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * A field's own violation message, built empty and taking no space until it has something to
     * say. A label that appears and disappears moves everything under it.
     *
     * @return {@link Label} the label a refusal fills
     */
    private static Label violationLabel() {
        final var violation = new Label();
        violation.setWrapText(true);
        violation.getStyleClass().add("settings-violation");
        violation.managedProperty().bind(violation.visibleProperty());
        violation.visibleProperty().bind(violation.textProperty().isNotEmpty());
        return violation;
    }

    /**
     * The control that shows a typed key and hides it again.
     *
     * <p>Carries its meaning as an icon, and its name for anything that cannot see one. A password
     * field's reveal is the one control where the picture is the convention and the word is not.
     *
     * @param hidden {@link PasswordField} the masked entry
     * @param shown {@link TextField} the plain entry bound to the same text
     * @return {@link Button} the reveal toggle
     */
    private static Button revealButton(final PasswordField hidden, final TextField shown) {
        final var eye = new SVGPath();
        eye.setContent(EYE);
        eye.getStyleClass().add("reveal-glyph");

        final var crossedOut = new SVGPath();
        crossedOut.setContent(CROSSED_OUT);
        crossedOut.getStyleClass().add("reveal-glyph");
        crossedOut.setVisible(false);

        final var button = new Button();
        button.setId("settings-key-reveal");
        button.setGraphic(new Group(eye, crossedOut));
        button.getStyleClass().add("button-quiet");
        button.setAccessibleText("Show the key");
        button.setOnAction(_ -> {
            final boolean wasShowing = shown.isVisible();
            shown.setVisible(!wasShowing);
            hidden.setVisible(wasShowing);
            crossedOut.setVisible(!wasShowing);
            button.setAccessibleText(wasShowing ? "Show the key" : "Hide the key");
        });
        return button;
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
    private static HBox cautionRow(final String text) {
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
     * than one path: an {@link SVGPath} is filled, so a ring drawn as one depends on two circles
     * cancelling by winding. A stroked {@link Circle} states the ring outright.
     *
     * @return {@link StackPane} the glyph
     */
    private static StackPane cautionGlyph() {
        final var ring = new Circle(7.5);
        ring.getStyleClass().add("caution-ring");
        final var stem = new Rectangle(2, 6);
        stem.setTranslateY(-1.5);
        stem.getStyleClass().add("caution-mark");
        final var dot = new Circle(1);
        dot.setTranslateY(4.5);
        dot.getStyleClass().add("caution-mark");
        final var glyph = new StackPane(ring, stem, dot);
        glyph.setMinSize(16, 16);
        glyph.setPrefSize(16, 16);
        glyph.setMaxSize(16, 16);
        return glyph;
    }

    /**
     * The strip that says a save landed, above the screen it saved.
     *
     * <p>At the top rather than beside the button, because a save rebuilds the page and returns the
     * reader to the start of it. A message left at the foot would be a confirmation nobody is
     * looking at.
     *
     * <p>The short confirmation leaves on its own after a few seconds, and can be dismissed before
     * that. A confirmation that stays is still on screen the next time something goes wrong, where
     * it reads as a claim about that. A longer report, a library move's say, stays until dismissed
     * instead: it holds counts and a path the reader has not seen elsewhere, and four seconds is
     * not enough to take those in.
     *
     * @param container {@link VBox} the pane's body, which the banner removes itself from
     * @param text {@link String} what to say
     * @return {@link HBox} the banner
     */
    private static HBox savedBanner(final VBox container, final String text) {
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
        banner.setId("settings-saved-banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.getStyleClass().add("settings-banner");

        final Runnable remove = () -> container.getChildren().remove(banner);
        dismiss.setOnAction(_ -> remove.run());

        if (SAVED.equals(text)) {
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
     * Fades a line out and empties it, four seconds after it appeared.
     *
     * <p>Emptied rather than left invisible, since the label stays in the card and is written to
     * again the next time something happens to the key. Its opacity is put back for the same
     * reason.
     *
     * @param line {@link Label} the line to take away
     */
    private static void takeAwayAfterFourSeconds(final Label line) {
        final var fade = new FadeTransition(Duration.millis(400), line);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setOnFinished(_ -> {
            line.setText("");
            line.setOpacity(1);
        });
        final var wait = new PauseTransition(Duration.seconds(4));
        wait.setOnFinished(_ -> fade.play());
        wait.play();
    }

    private static Label overrideLabel(final String text) {
        final var label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("settings-override-note");
        return label;
    }

    /**
     * Fills the credential block with what the chosen provider's credential looks like right now.
     *
     * <p>A key is stored the moment its own button is pressed, so only this block has anything to
     * redraw afterwards. Redrawing the page instead would read every other field back from what is
     * saved, throwing away a provider picked but not yet saved along with anything typed.
     *
     * <p>Which provider it is comes from the dropdown rather than from a caller. A stored key then
     * cannot land under a provider other than the one on screen.
     *
     * @param card {@link VBox} the block to fill, whatever it held before
     * @param presenter {@link SettingsPresenter} reads and writes that credential
     * @param providerBox {@link ComboBox} of {@link SettingsView.ProviderChoice} the chosen provider
     * @param said what just happened to the key, or null when nothing has
     */
    private static void fillSecretCard(final VBox card, final SettingsPresenter presenter,
                                       final ComboBox<SettingsView.ProviderChoice> providerBox,
                                       final @Nullable String said) {
        final String providerId = providerChoiceOf(providerBox).id();
        card.getChildren().setAll(secretCardContents(presenter, presenter.secretRow(providerId), providerId,
                said, message -> fillSecretCard(card, presenter, providerBox, message)));
        // The button that was pressed leaves the scene along with the rest of this block. Focus goes
        // to whatever the window finds next, and a scrolling pane travels to wherever focus lands.
        // A key saved half way down the page then shows the top of it. Putting focus back on the
        // button that replaced it keeps the reader where they were standing.
        final Node pressedAgain = card.lookup("#settings-api-key-save");
        if (said != null && pressedAgain != null) {
            pressedAgain.requestFocus();
        }
    }

    private static List<Node> secretCardContents(final SettingsPresenter presenter,
                                                 final SettingsView.SecretRow secret,
                                                 final String providerId, final @Nullable String said,
                                                 final Consumer<String> onChanged) {
        final var entry = new PasswordField();
        entry.setId("settings-api-key-entry");
        entry.setPromptText("Paste a new key to save or replace it");
        final var reveal = new TextField();
        reveal.setPromptText(entry.getPromptText());
        reveal.managedProperty().bind(reveal.visibleProperty());
        entry.managedProperty().bind(entry.visibleProperty());
        reveal.setVisible(false);
        reveal.textProperty().bindBidirectional(entry.textProperty());

        final var eye = revealButton(entry, reveal);

        final var saveButton = new Button(secret.hasStoredValue() ? "Replace" : "Save");
        saveButton.setId("settings-api-key-save");
        final var result = new Label();
        result.setWrapText(true);
        saveButton.setOnAction(_ -> {
            final String error = presenter.saveSecret(providerId, entry.getText());
            if (error != null) {
                result.setText(error);
                result.getStyleClass().setAll("settings-violation");
            } else {
                onChanged.accept("API key saved.");
            }
        });

        final var removeButton = new Button("Remove");
        removeButton.setId("settings-api-key-remove");
        removeButton.setDisable(!secret.hasStoredValue());
        removeButton.setOnAction(_ -> {
            final String error = presenter.removeSecret(providerId);
            if (error != null) {
                result.setText(error);
                result.getStyleClass().setAll("settings-violation");
            } else {
                onChanged.accept("API key removed.");
            }
        });

        // Beside the row it happened on rather than at the top of the page, which is not where the
        // reader is standing. Taken away again on its own, the way the page's own banner is.
        if (said != null) {
            result.setText(said);
            result.getStyleClass().setAll("settings-confirmation");
            takeAwayAfterFourSeconds(result);
        }

        final var entryRow = new HBox(entry, reveal, eye, saveButton, removeButton);
        entryRow.getStyleClass().add("settings-field-row");
        // Whichever of the two entries is showing takes the width the buttons leave. Both are told,
        // since which one is visible flips every time Show is pressed.
        HBox.setHgrow(entry, Priority.ALWAYS);
        HBox.setHgrow(reveal, Priority.ALWAYS);

        final var reassurance = new Label(secret.reassurance());
        reassurance.setWrapText(true);
        reassurance.getStyleClass().add("settings-reassurance");

        final var children = new ArrayList<Node>(List.of(subsectionHeading("API key")));
        // Above the entry rather than in place of it. A store that will not say what it holds can
        // still be written to. Taking the field away leaves a user who cannot read their key with
        // no way to set another one.
        if (secret.errorMessage() != null) {
            final var error = new Label(secret.errorMessage());
            error.setWrapText(true);
            error.getStyleClass().add("settings-violation-detail");
            children.add(error);
        }
        children.addAll(List.of(entryRow, reassurance));
        if (secret.environmentOverride() != null) {
            children.add(overrideLabel(secret.environmentOverride()));
        }
        if (secret.multiHolder() != null) {
            children.add(overrideLabel(secret.multiHolder()));
        }
        children.add(result);
        return children;
    }

    private static void onSave(final SettingsPresenter presenter, final FolderRow workingRoot,
                               final FolderRow libraryRoot, final FolderRow inbox,
                               final SettingsView.ProviderChoice provider, final VBox providerFields,
                               final boolean watchAutomatically, final int tileSize, final int tilesPerRow,
                               final String themeId, final Label status, final Consumer<String> showBanner) {
        final ProviderFieldControls controls = controlsOf(providerFields);
        final Integer maxRetries = valueOrUnset(controls.maxRetries().getText());
        final SaveOutcome outcome = presenter.save(workingRoot.field().getText(), libraryRoot.field().getText(),
                inbox.field().getText(), provider.id(), controls.model().getText(), controls.endpoint().getText(),
                controls.thinking().isSelected(), maxRetries, watchAutomatically, tileSize, tilesPerRow, themeId);
        switch (outcome) {
            case final SaveOutcome.Saved _ -> showBanner.accept(SAVED);
            case final SaveOutcome.Refused refused -> {
                say(workingRoot.violation(), refused.workingRoot());
                say(libraryRoot.violation(), refused.libraryRoot());
                say(inbox.violation(), refused.inbox());
                say(controls.modelViolation(), refused.model());
                showRefusal(status, refused.message(), workingRoot.violation(), libraryRoot.violation(),
                        inbox.violation(), controls.modelViolation());
            }
            case final SaveOutcome.NeedsLibraryRootResolution needsResolution ->
                    resolveLibraryRootMove(presenter, needsResolution, status, showBanner);
        }
    }

    private static void resolveLibraryRootMove(final SettingsPresenter presenter,
                                               final SaveOutcome.NeedsLibraryRootResolution needsResolution,
                                               final Label status, final Consumer<String> showBanner) {
        // Marked LEFT so the two real choices sit together on the left, apart from Cancel on the
        // right. Centred as one row they read as three equal options, and backing out is not one
        // of the choices.
        final var copyAndKeep = new ButtonType("Copy the old library across", ButtonBar.ButtonData.LEFT);
        final var startFresh = new ButtonType("Start the record fresh", ButtonBar.ButtonData.LEFT);
        final var alert = new Alert(AlertType.CONFIRMATION, needsResolution.message(), copyAndKeep, startFresh,
                ButtonType.CANCEL);
        alert.setHeaderText("Moving the library root");
        dress(alert);
        // Restored by hand, because the LEFT placement above takes the default-button role with
        // it. The copy is still the choice the dialog leads with, green fill and Enter both.
        if (alert.getDialogPane().lookupButton(copyAndKeep) instanceof final Button leading) {
            leading.setDefaultButton(true);
        }
        final ButtonType chosen = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (chosen == ButtonType.CANCEL) {
            return;
        }
        final boolean copying = chosen == copyAndKeep;
        status.setText("Moving the library...");
        status.getStyleClass().setAll("settings-save-status");
        final var task = new Task<SettingsPresenter.MoveOutcome>() {
            @Override
            protected SettingsPresenter.MoveOutcome call() {
                return copying ? presenter.moveLibraryRootCopyingTheIndex(needsResolution.newLibraryRoot())
                        : presenter.moveLibraryRootWithAFreshIndex(needsResolution.newLibraryRoot());
            }
        };
        task.setOnSucceeded(_ -> {
            final SettingsPresenter.MoveOutcome result = task.getValue();
            if (result.succeeded()) {
                // The outcome's own words, not the generic saved line. Each resolution says what it
                // did with the files and the record, and that is what the user chose between. The
                // saved line stands in only for a success with nothing of its own to report.
                final String said = result.message();
                showBanner.accept(said == null ? SAVED : said);
            } else {
                showRefusal(status, result.message());
            }
        });
        task.setOnFailed(_ -> {
            final Throwable failure = task.getException();
            showRefusal(status, failure == null ? "The library move failed." : failure.getMessage());
        });
        Thread.ofVirtual().start(task);
    }

    /**
     * Dresses a dialog in the look the app is wearing.
     *
     * <p>A dialog builds its own scene, so nothing this class dressed before covers it. What
     * appears instead is the platform's own confirmation look: a question-mark badge, a stock
     * title-bar icon, colours from no palette of ours. Read once rather than bound, since the
     * dialog is modal and gone before the look can change under it.
     *
     * @param alert {@link Alert} the dialog to dress
     */
    private static void dress(final Alert alert) {
        alert.setGraphic(null);
        alert.getDialogPane().getStylesheets().setAll(Stylesheet.sheetsInForce());
        // The window is sized off label widths measured before this app's own fonts apply. A long
        // choice then ends up wider than the room the skin reserved, and ellipsizes. Sizing hints
        // on the buttons themselves lost that fight twice, in both directions. A floor on the pane
        // ends it: wide enough for the three choices at their real widths, and still well inside
        // the window the dialog covers.
        for (final ButtonType type : alert.getDialogPane().getButtonTypes()) {
            if (alert.getDialogPane().lookupButton(type) instanceof final Button button) {
                ButtonBar.setButtonUniformSize(button, false);
                button.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        alert.getDialogPane().setMinWidth(600);
        // The pane's own content label wraps or fails to by sizing arithmetic that has shifted
        // under every width change above. A label of our own with the bound stated makes the wrap
        // a fact rather than an outcome. The bound goes on the PREFERRED width, because that is
        // what the window sizes itself to. A wrapping label still prefers its full single-line
        // width. A maximum only caps the node after the window has already grown around it.
        final var body = new Label(alert.getContentText());
        body.setWrapText(true);
        body.setPrefWidth(560);
        body.setMaxWidth(560);
        // The height half of the same fight: a wrapping label shorted on rows ellipsizes exactly
        // like an unwrapped one. Pinning the minimum to the preferred height makes the dialog grow
        // tall enough for every row the 560px wrap produces.
        body.setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setContent(body);
        alert.setOnShowing(_ -> {
            if (alert.getDialogPane().getScene().getWindow() instanceof final Stage stage) {
                stage.getIcons().setAll(BrandMark.icons());
            }
        });
    }

    /**
     * Puts a refusal at the foot, and takes the reader to the field that has to change.
     *
     * <p>Called once the rows are marked, since which field to go to is read off the marks
     * themselves.
     *
     * @param status {@link Label} the line under Save
     * @param message what was refused, or null where nothing was
     * @param marks the field marks this refusal just set, in any order and none where a refusal
     *              belongs to no field
     */
    private static void showRefusal(final Label status, final @Nullable String message,
                                    final Label... marks) {
        status.setText(message == null ? "" : message);
        status.getStyleClass().setAll("settings-save-status", "settings-violation");
        takeTheReaderToTheFault(status, marks);
    }

    /**
     * Takes a refusal back off the screen: its summary, and every mark it left on a field.
     *
     * @param status {@link Label} the line under Save
     * @param marks the field marks a refusal can set
     */
    private static void clearRefusal(final Label status, final Label... marks) {
        status.setText("");
        status.getStyleClass().setAll("settings-save-status");
        for (final Label mark : marks) {
            say(mark, null);
        }
    }

    /**
     * Scrolls to the topmost marked row, or to the summary where no row is at fault.
     *
     * <p>A refused save does not rebuild the screen, so nothing moves on its own. Save sits at the
     * foot, so the reader is already at the bottom when they press it. The field the refusal is
     * about is usually somewhere above. Left alone, a refusal marks a row nobody is looking at.
     *
     * <p>The summary is the destination only when nothing else is, because it says what needs
     * fixing is marked under the fields. Sending someone down to read that, when there is a mark
     * above, points them the wrong way.
     *
     * @param status {@link Label} the line under Save, and the last resort to scroll to
     * @param marks the field marks this refusal set
     */
    private static void takeTheReaderToTheFault(final Label status, final Label... marks) {
        final Parent body = status.getParent();
        if (body == null || !(body.getProperties().get(SCROLL) instanceof final ScrollPane scroll)) {
            return;
        }
        // A mark's own row has no height until the mark is measured, so every position below it
        // would be read off a page about to grow.
        scroll.applyCss();
        scroll.layout();
        final double scrollable =
                body.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
        if (scrollable <= 0) {
            return;
        }
        final Node target = topmostMarkedRow(marks).orElse(status);
        final double top = body.sceneToLocal(target.localToScene(target.getBoundsInLocal())).getMinY();
        scroll.setVvalue(Math.clamp(top / scrollable, 0, 1) * scroll.getVmax());
    }

    /**
     * The highest row on the page carrying one of these marks.
     *
     * <p>Ordered by where each one ended up rather than by the order they were checked in. A row
     * moving on the page then cannot leave this pointing at the wrong one. A mark with nothing to
     * say is invisible, which is what keeps a cleared row out of the answer.
     *
     * <p>Only the marks handed in are candidates. Other things on this screen say what is wrong in
     * the same words and the same red. A stale one of those is not where a refused save should send
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
     * Reads a field {@link #withinLimit} constrains, where empty means the setting is unset.
     *
     * <p>No parse failure to handle. That formatter refuses every keystroke leaving anything but
     * digits within the limit, so this reads a field holding one of those or nothing.
     *
     * @param text {@link String} the field's current text
     * @return {@link Integer} the value, or null when the field is empty
     */
    private static @Nullable Integer valueOrUnset(final String text) {
        return text.isEmpty() ? null : Integer.valueOf(text);
    }
}
