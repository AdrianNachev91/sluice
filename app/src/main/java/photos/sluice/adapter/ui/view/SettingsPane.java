package photos.sluice.adapter.ui.view;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;
import photos.sluice.adapter.ui.SettingsView;

import java.util.function.Consumer;

/**
 * The Settings screen: folder roots, the vision provider and its credential, and the montage grid.
 *
 * <p>Every value it shows and every note under a field comes from {@link SettingsPresenter}. This
 * class lays those out and forwards a click or an edit back to the presenter. It never decides on
 * its own what a field means or whether it is valid.
 *
 * <p>Assembles the cards ({@link FoldersCard}, {@link VisionProviderCard},
 * {@link PhotoCategoriesCard}, {@link PhotoSheetsCard}, {@link AppearanceCard}) and wires Save, off
 * the shared row vocabulary in {@link SettingsRows}.
 */
final class SettingsPane {

    private static final String SAVED = "Settings saved.";
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
     * @param onOpenPhotoCategories {@link Runnable} opens the photo categories screen
     * @return {@link Node} the settings pane
     */
    static Node pane(final SettingsPresenter presenter, final Runnable onOpenPhotoCategories) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        refresh(container, presenter, onOpenPhotoCategories, null);

        return SettingsRows.scrolling(container);
    }

    /**
     * Draws the screen from what the presenter says, replacing whatever was there.
     *
     * <p>A save rebuilds rather than patching, so every field shows what was actually stored. The
     * banner is how a message survives that: the label carrying it is one of the things replaced.
     *
     * @param container {@link VBox} the pane's own body
     * @param presenter {@link SettingsPresenter} supplies the state and takes the actions
     * @param onOpenPhotoCategories {@link Runnable} opens the photo categories screen
     * @param banner what to say above the screen about what just happened, or null for nothing
     */
    private static void refresh(final VBox container, final SettingsPresenter presenter,
                                final Runnable onOpenPhotoCategories,
                                final @Nullable String banner) {
        final SettingsView view = presenter.view();
        final var heading = new Label("Settings");
        heading.getStyleClass().add("pane-heading");

        final FoldersCard.Result folders = FoldersCard.build(view);
        final VisionProviderCard.Result provider = VisionProviderCard.build(view, presenter);
        final PhotoSheetsCard.Result montage = PhotoSheetsCard.build(view);
        final AppearanceCard.Result appearance = AppearanceCard.build(view, presenter);

        final var status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("settings-save-status");

        provider.providerBox().getSelectionModel().selectedItemProperty().addListener((_, _, chosen) -> {
            // A refusal answers one press of Save against one set of choices. Changing the provider
            // changes which fields exist and what they must hold. What the last one said is then
            // about a different screen.
            clearRefusal(status, folders.workingRoot().violation(), folders.libraryRoot().violation(),
                    folders.inbox().violation(),
                    VisionProviderCard.controlsOf(provider.providerFields()).modelViolation());
            VisionProviderCard.showOnlyWhatTheProviderUses(chosen.fields(), provider.providerFields(),
                    provider.watchRow(), provider.secretCard());
            // Both rebuilt rather than toggled. A credential and a model catalog each belong to the
            // provider that owns them, so what these say and offer has to change with the choice.
            //
            // fillSecretCard leaves Test's own enabled state current as part of that. A credential
            // is exactly what that state depends on.
            VisionProviderCard.fillSecretCard(provider.secretCard(), presenter, provider.providerBox(),
                    provider.providerFields(), null, view.keyLimit());
            VisionProviderCard.selectModelPickerFor(VisionProviderCard.controlsOf(provider.providerFields()).model(),
                    VisionProviderCard.controlsOf(provider.providerFields()).modelInfo(), presenter, chosen.id(),
                    provider.providerBox());
            // A test answered for the provider that was chosen when it ran.
            VisionProviderCard.controlsOf(provider.providerFields()).testResult().setText("");
            // The endpoint field is shared across every provider. A switch has to hand it the newly
            // chosen provider's own default, not leave the old one's showing.
            VisionProviderCard.controlsOf(provider.providerFields()).endpoint()
                    .setPromptText(chosen.defaultEndpoint());
        });

        final var save = new Button("Save");
        save.setId("settings-save-button");
        save.setOnAction(_ -> onSave(presenter, folders.workingRoot(), folders.libraryRoot(), folders.inbox(),
                VisionProviderCard.providerChoiceOf(provider.providerBox()), provider.providerFields(),
                VisionProviderCard.watchAutomaticallyOf(provider.watchRow()), montage.tileSize().getValue(),
                montage.tilesPerRow().getValue(), AppearanceCard.themeChoiceOf(appearance.themeBox()), status,
                said -> refresh(container, presenter, onOpenPhotoCategories, said)));

        // The secret card belongs to the provider card, not here. A node named in two parents lands
        // in whichever claimed it last, so adding it would quietly lift it out of the provider card.
        container.getChildren().setAll(heading, folders.card(), provider.card(),
                PhotoCategoriesCard.build(onOpenPhotoCategories), montage.card(),
                appearance.card(), save, status);
        if (banner != null) {
            container.getChildren().add(1, savedBanner(container, banner));
        }
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
        return SettingsRows.banner(container, "settings-saved-banner", text, SAVED.equals(text));
    }

    private static void onSave(final SettingsPresenter presenter, final SettingsRows.FolderRow workingRoot,
                               final SettingsRows.FolderRow libraryRoot, final SettingsRows.FolderRow inbox,
                               final SettingsView.ProviderChoice provider, final VBox providerFields,
                               final boolean watchAutomatically, final int tileSize, final int tilesPerRow,
                               final String themeId, final Label status, final Consumer<String> showBanner) {
        final VisionProviderCard.ProviderFieldControls controls = VisionProviderCard.controlsOf(providerFields);
        final SaveOutcome outcome = presenter.save(workingRoot.field().getText(), libraryRoot.field().getText(),
                inbox.field().getText(), provider.id(), VisionProviderCard.selectedModelId(controls.model()),
                controls.endpoint().getText(), watchAutomatically, tileSize, tilesPerRow, themeId);
        switch (outcome) {
            case final SaveOutcome.Saved _ -> {
                // Behind the status line rather than a blocking dialog. The save itself has already
                // landed, so what the user is waiting on is only this account's real model list.
                //
                // Said only where there is a key to check. A provider that takes none, and one with
                // nothing saved yet, would otherwise be told Sluice is checking something it has
                // not got. The line still clears either way, so a refusal from a previous press
                // cannot sit under a save that worked.
                status.setText(presenter.secretRow(provider.id()).hasStoredValue() ? "Checking your key..." : "");
                status.getStyleClass().setAll("settings-save-status");
                final var task = new Task<Void>() {
                    @Override
                    protected Void call() {
                        presenter.refreshModels(provider.id());
                        return null;
                    }
                };
                task.setOnSucceeded(_ -> showBanner.accept(SAVED));
                task.setOnFailed(_ -> showBanner.accept(SAVED));
                Thread.ofVirtual().start(task);
            }
            case final SaveOutcome.Refused refused -> {
                SettingsRows.say(workingRoot.violation(), refused.workingRoot());
                SettingsRows.say(libraryRoot.violation(), refused.libraryRoot());
                SettingsRows.say(inbox.violation(), refused.inbox());
                SettingsRows.say(controls.modelViolation(), refused.model());
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
        SettingsRows.takeTheReaderToTheFault(status, marks);
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
            SettingsRows.say(mark, null);
        }
    }

}
