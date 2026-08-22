package photos.sluice.adapter.ui.view;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;
import photos.sluice.adapter.ui.SettingsView;
import photos.sluice.adapter.ui.VisionProviderPresenter;

import java.util.function.Consumer;

/**
 * The Settings screen: folder roots, the vision provider and its credential, and the montage grid.
 *
 * <p>Every value it shows and every note under a field comes from {@link SettingsPresenter} or, for
 * the credential and model catalogue, {@link VisionProviderPresenter}. This class lays those out
 * and forwards a click or an edit back to whichever one owns it. It never decides on its own what a
 * field means or whether it is valid.
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
     * @param visionProvider {@link VisionProviderPresenter} the VISION PROVIDER card's credential,
     *         model catalogue and connection check
     * @param onOpenPhotoCategories {@link Runnable} opens the photo categories screen
     * @return {@link Node} the settings pane
     */
    static Node pane(final SettingsPresenter presenter, final VisionProviderPresenter visionProvider,
                     final Runnable onOpenPhotoCategories) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        final PageHeader.Result header = PageHeader.build("Settings", "settings-save-button", null);
        refresh(container, header, presenter, visionProvider, onOpenPhotoCategories, null);

        return PageHeader.pinnedOver(header, container);
    }

    /**
     * Draws the screen from what the presenter says, replacing whatever was there.
     *
     * <p>A save rebuilds rather than patching, so every field shows what was actually stored. The
     * banner is how a message survives that: the label carrying it is one of the things replaced.
     *
     * @param container {@link VBox} the pane's own body
     * @param presenter {@link SettingsPresenter} supplies the state and takes the actions
     * @param visionProvider {@link VisionProviderPresenter} the VISION PROVIDER card's credential,
     *         model catalogue and connection check
     * @param onOpenPhotoCategories {@link Runnable} opens the photo categories screen
     * @param banner what to say above the screen about what just happened, or null for nothing
     */
    private static void refresh(final VBox container, final PageHeader.Result header,
                                final SettingsPresenter presenter, final VisionProviderPresenter visionProvider,
                                final Runnable onOpenPhotoCategories,
                                final @Nullable String banner) {
        header.clearStatus();
        final SettingsView view = presenter.view();
        final FoldersCard.Result folders = FoldersCard.build(view);
        final VisionProviderCard.Result provider = VisionProviderCard.build(view, visionProvider);
        final PhotoSheetsCard.Result montage = PhotoSheetsCard.build(view);
        final AppearanceCard.Result appearance = AppearanceCard.build(view, presenter);

        final Label status = header.status();

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
            VisionProviderCard.fillSecretCard(provider.secretCard(), visionProvider, provider.providerBox(),
                    provider.providerFields(), null, view.keyLimit());
            VisionProviderCard.selectModelPickerFor(VisionProviderCard.controlsOf(provider.providerFields()).model(),
                    VisionProviderCard.controlsOf(provider.providerFields()).modelInfo(), visionProvider, chosen.id(),
                    provider.providerBox());
            // A test answered for the provider that was chosen when it ran.
            VisionProviderCard.controlsOf(provider.providerFields()).testResult().setText("");
            // The endpoint field is shared across every provider. A switch has to hand it the newly
            // chosen provider's own default, not leave the old one's showing.
            VisionProviderCard.controlsOf(provider.providerFields()).endpoint()
                    .setPromptText(chosen.defaultEndpoint());
        });

        header.save().setOnAction(_ -> onSave(container, presenter, visionProvider, folders.workingRoot(),
                folders.libraryRoot(), folders.inbox(),
                VisionProviderCard.providerChoiceOf(provider.providerBox()), provider.providerFields(),
                VisionProviderCard.watchAutomaticallyOf(provider.watchRow()), montage.tileSize().getValue(),
                montage.tilesPerRow().getValue(), AppearanceCard.themeChoiceOf(appearance.themeBox()), status,
                said -> refresh(container, header, presenter, visionProvider, onOpenPhotoCategories, said)));

        // The secret card belongs to the provider card, not here. A node named in two parents lands
        // in whichever claimed it last, so adding it would quietly lift it out of the provider card.
        container.getChildren().setAll(folders.card(), provider.card(),
                PhotoCategoriesCard.build(onOpenPhotoCategories), montage.card(), appearance.card());
        if (banner != null) {
            SettingsRows.report(container, null, banner, SAVED.equals(banner));
        }
        if (banner != null) {
            SettingsRows.travelToTop(container);
        }
    }


    private static void onSave(final VBox container, final SettingsPresenter presenter,
                               final VisionProviderPresenter visionProvider,
                               final SettingsRows.FolderRow workingRoot,
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
                working(status,
                        visionProvider.secretRow(provider.id()).hasStoredValue() ? "Checking your key..." : "");
                final var task = new Task<Void>() {
                    @Override
                    protected Void call() {
                        visionProvider.refreshModels(provider.id());
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
                showRefusal(container, status, refused.message(), refused.warning());
            }
            case final SaveOutcome.NeedsLibraryRootResolution needsResolution ->
                    LibraryRootMoveDialog.resolve(presenter, needsResolution,
                            said -> working(status, said),
                            moveOutcome -> reportTheMove(container, moveOutcome, status, showBanner));
        }
    }

    /**
     * Puts a refusal on the bar, beside the button that produced it.
     *
     * <p>The page is not moved. Save is pinned, so the reader is already looking at this line, and
     * which field is at fault is said by the mark on that field.
     *
     * @param status {@link Label} the line beside Save
     * @param message what was refused, or null where nothing was
     */
    private static void showRefusal(final VBox container, final Label status,
                                    final @Nullable String message, final boolean warning) {
        status.setText("");
        status.getStyleClass().setAll("settings-save-status");
        if (message != null) {
            SettingsRows.report(container, warning ? "settings-banner-caution" : "settings-banner-violation",
                    message, false);
        }
    }

    /**
     * Puts a finished library move on the screen, in the terms that state deserves.
     *
     * <p>Only a move that landed rebuilds the page. The other two leave every field exactly as the
     * user typed it, because the save they were carrying never happened and a rebuild would read
     * back the values on disk over the top of it.
     *
     * @param outcome {@link SettingsPresenter.MoveOutcome} what the move reported
     * @param status {@link Label} the line beside Save
     * @param showBanner {@link Consumer} of {@link String} redraws the page and says what happened
     */
    private static void reportTheMove(final VBox container, final SettingsPresenter.MoveOutcome outcome,
                                      final Label status,
                                      final Consumer<String> showBanner) {
        switch (outcome) {
            case final SettingsPresenter.MoveOutcome.Moved moved -> showBanner.accept(moved.message());
            case final SettingsPresenter.MoveOutcome.NothingChanged nothing -> working(status, nothing.message());
            case final SettingsPresenter.MoveOutcome.Failed failed ->
                    showRefusal(container, status, failed.message(), false);
        }
    }

    /**
     * Says what the screen is busy with, in the line beside Save.
     *
     * @param status {@link Label} the line beside Save
     * @param said {@link String} what is happening, or nothing at all
     */
    private static void working(final Label status, final String said) {
        status.setText(said);
        status.getStyleClass().setAll("settings-save-status");
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
