package photos.sluice.adapter.ui.view;

import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;
import photos.sluice.adapter.ui.SettingsView;
import photos.sluice.adapter.ui.VisionProviderPresenter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
     * @return {@link Mounted} the pane, and the way to ask whether leaving would lose anything
     */
    static Mounted pane(final SettingsPresenter presenter, final VisionProviderPresenter visionProvider,
                        final Runnable onOpenPhotoCategories) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        final PageHeader.Result header = PageHeader.build("Settings", "settings-save-button", null);
        // Replaced on every draw, since a save rebuilds every card. Whatever asks about unsaved work
        // has to read the controls standing now rather than the ones this page opened with.
        final var onScreen = new AtomicReference<@Nullable OnScreen>(null);
        refresh(container, header, presenter, visionProvider, onOpenPhotoCategories, null, onScreen);

        return new Mounted(PageHeader.pinnedOver(header, container),
                () -> hasUnsavedEdits(presenter, onScreen.get()));
    }

    /**
     * The pane, and the one question anything outside it needs to ask.
     *
     * @param node {@link Node} the pane itself
     * @param hasUnsavedEdits {@link BooleanSupplier} whether leaving would lose what was typed
     */
    record Mounted(Node node, BooleanSupplier hasUnsavedEdits) {
    }

    /**
     * The controls a draw put up, held so the unsaved-work question can read them later.
     *
     * @param folders {@link FoldersCard.Result} the three path fields
     * @param provider {@link VisionProviderCard.Result} the provider choice and its own fields
     * @param montage {@link PhotoSheetsCard.Result} the sheet's two numbers
     */
    private record OnScreen(FoldersCard.Result folders, VisionProviderCard.Result provider,
                            PhotoSheetsCard.Result montage) {
    }

    /**
     * Whether leaving would lose something typed or picked here.
     *
     * <p>Answers false before the first draw has put any control up, which is the only state where
     * there is nothing to read and nothing could have been typed either.
     *
     * @param presenter {@link SettingsPresenter} compares what is on screen against what is stored
     * @param onScreen {@link OnScreen} the controls the last draw put up, or null before it ran
     * @return boolean true where leaving would lose something
     */
    private static boolean hasUnsavedEdits(final SettingsPresenter presenter,
                                           final @Nullable OnScreen onScreen) {
        if (onScreen == null) {
            return false;
        }
        final VisionProviderCard.ProviderFieldControls controls =
                VisionProviderCard.controlsOf(onScreen.provider().providerFields());
        return presenter.hasUnsavedEdits(new SettingsPresenter.SettingsEdits(
                onScreen.folders().workingRoot().field().getText(),
                onScreen.folders().libraryRoot().field().getText(),
                onScreen.folders().inbox().field().getText(),
                VisionProviderCard.providerChoiceOf(onScreen.provider().providerBox()).id(),
                VisionProviderCard.selectedModelId(controls.model()),
                controls.endpoint().getText(),
                onScreen.montage().tileSize().getValue(),
                onScreen.montage().tilesPerRow().getValue()));
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
     * @param onScreen an {@link AtomicReference} the controls this draw builds are published to,
     *     so the unsaved-work question reads the ones standing rather than an earlier draw's
     */
    private static void refresh(final VBox container, final PageHeader.Result header,
                                final SettingsPresenter presenter, final VisionProviderPresenter visionProvider,
                                final Runnable onOpenPhotoCategories,
                                final @Nullable String banner,
                                final AtomicReference<@Nullable OnScreen> onScreen) {
        header.clearStatus();
        final SettingsView view = presenter.view();
        final FoldersCard.Result folders = FoldersCard.build(view);
        final VisionProviderCard.Result provider = VisionProviderCard.build(view, visionProvider);
        final PhotoSheetsCard.Result montage = PhotoSheetsCard.build(view);
        final TextArea status = header.status();
        // The same bar a refused Save writes to. A theme saves on its own press, so its refusal has
        // no Save button to sit under. This is the one place the page reports anything.
        final AppearanceCard.Result appearance = AppearanceCard.build(view, presenter,
                refused -> showRefusal(container, status, refused, false));
        onScreen.set(new OnScreen(folders, provider, montage));

        provider.providerBox().getSelectionModel().selectedItemProperty().addListener((_, _, chosen) -> {
            // A refusal answers one press of Save against one set of choices. Changing the provider
            // changes which fields exist and what they must hold. What the last one said is then
            // about a different screen.
            clearRefusal(status, folders.workingRoot().violation(), folders.libraryRoot().violation(),
                    folders.inbox().violation(),
                    VisionProviderCard.controlsOf(provider.providerFields()).modelViolation());
            VisionProviderCard.showOnlyWhatTheProviderUses(chosen.fields(), provider.providerFields(),
                    provider.secretCard());
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
                montage.tileSize().getValue(),
                montage.tilesPerRow().getValue(), AppearanceCard.themeChoiceOf(appearance.themeBox()), status,
                said -> refresh(container, header, presenter, visionProvider, onOpenPhotoCategories, said,
                        onScreen)));

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
                               final int tileSize, final int tilesPerRow,
                               final String themeId, final TextArea status,
                               final Consumer<String> showBanner) {
        final VisionProviderCard.ProviderFieldControls controls = VisionProviderCard.controlsOf(providerFields);
        final SaveOutcome outcome = presenter.save(workingRoot.field().getText(), libraryRoot.field().getText(),
                inbox.field().getText(), provider.id(), VisionProviderCard.selectedModelId(controls.model()),
                controls.endpoint().getText(), tileSize, tilesPerRow, themeId);
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
                    LibraryRootMoveDialog.resolve(container, presenter, needsResolution,
                            said -> working(status, said),
                            moveOutcome -> reportTheMove(container, moveOutcome, status, showBanner));
        }
    }

    /**
     * Puts a refusal on the bar, beside the button that produced it.
     *
     * <p>The page travels to the bar, which is where a refusal has to be read from. Which field is
     * at fault is said by the mark on that field, so the reader goes back down to it.
     *
     * @param status {@link TextArea} the line beside Save
     * @param message what was refused, or null where nothing was
     */
    private static void showRefusal(final VBox container, final TextArea status,
                                    final @Nullable String message, final boolean warning) {
        status.setText("");
        SelectableText.dressAs(status, "settings-save-status");
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
     * @param status {@link TextArea} the line beside Save
     * @param showBanner {@link Consumer} of {@link String} redraws the page and says what happened
     */
    private static void reportTheMove(final VBox container, final SettingsPresenter.MoveOutcome outcome,
                                      final TextArea status,
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
     * @param status {@link TextArea} the line beside Save
     * @param said {@link String} what is happening, or nothing at all
     */
    private static void working(final TextArea status, final String said) {
        status.setText(said);
        SelectableText.dressAs(status, "settings-save-status");
    }

    /**
     * Takes a refusal back off the screen: its summary, and every mark it left on a field.
     *
     * @param status {@link TextArea} the line under Save
     * @param marks the field marks a refusal can set
     */
    private static void clearRefusal(final TextArea status, final TextArea... marks) {
        status.setText("");
        SelectableText.dressAs(status, "settings-save-status");
        for (final TextArea mark : marks) {
            SettingsRows.say(mark, null);
        }
    }

}
