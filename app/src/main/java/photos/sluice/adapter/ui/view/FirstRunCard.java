package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter.SaveOutcome;
import photos.sluice.adapter.ui.SettingsView;

import java.util.function.Consumer;

/**
 * The card a fresh install opens on: the three folders Sluice needs, and what should look at the
 * photos.
 *
 * <p>The same fields Settings holds, drawn where somebody meets them for the first time. Both
 * screens build their rows from {@link FolderRootRows} and save through the same seam, so a value
 * reads and stores the same way whichever of the two it was typed into.
 *
 * <p>It stays up until all three folders are set. A save filling in one of them redraws this card
 * with the rest still to go. Moving somebody on at that point would hand them a screen they cannot
 * use yet.
 */
final class FirstRunCard {

    private FirstRunCard() {
    }

    /**
     * Builds the card, ready to sit in the shell's content area.
     *
     * @param presenter {@link SettingsPresenter} supplies the fields and carries out the save
     * @param firstRun {@link FirstRunPresenter} says whether the card has done its job yet
     * @param onFinished {@link Consumer} of {@link String} shows the Dashboard once all three
     *     folders are set, carrying anything the save had to report, or null for nothing
     * @return {@link Node} the card in the pane that scrolls it
     */
    static Node pane(final SettingsPresenter presenter, final FirstRunPresenter firstRun,
                     final Consumer<@Nullable String> onFinished) {
        final var container = new VBox();
        container.getStyleClass().add("first-run-pane");
        final PageHeader.Result header = PageHeader.build("Welcome", "first-run-save-button", null);
        refresh(container, header, presenter, firstRun, onFinished, null);
        return PageHeader.pinnedPage(header, container);
    }

    /**
     * Draws the card from what the presenter says, replacing whatever was there.
     *
     * <p>A save redraws rather than patching, so every field shows what was actually stored. The
     * banner is how a message survives that: the label carrying it is one of the things replaced.
     *
     * @param container {@link VBox} the card's own body
     * @param presenter {@link SettingsPresenter} supplies the state and takes the action
     * @param firstRun {@link FirstRunPresenter} says whether the card has done its job yet
     * @param onFinished {@link Consumer} of {@link String} shows the Dashboard once all three
     *     folders are set
     * @param banner what to say about the save that just landed, or null for nothing
     */
    private static void refresh(final VBox container, final PageHeader.Result header,
                                final SettingsPresenter presenter,
                                final FirstRunPresenter firstRun, final Consumer<@Nullable String> onFinished,
                                final FirstRunPresenter.@Nullable IncompleteSave banner) {
        header.clearStatus();
        final SettingsView view = presenter.view();
        final SettingsRows.FolderRow workingRoot = FolderRootRows.workingRoot(view);
        final SettingsRows.FolderRow libraryRoot = FolderRootRows.libraryRoot(view);
        final SettingsRows.FolderRow inbox = FolderRootRows.inbox(view);
        final ComboBox<SettingsView.ProviderChoice> provider = VisionProviderCard.providerChoice(view);
        provider.setId("first-run-provider");

        final TextArea status = header.status();
        // A save that lands redraws the card, so its own banner replaces whatever was there. A
        // refused one does not redraw. The last save's "Saved." would otherwise stand above this
        // one's refusal, where it reads as a claim about that.
        final Runnable dismissBanner = () -> SettingsRows.clearReport(container);
        header.save().setOnAction(_ -> onSave(presenter, firstRun, onFinished, workingRoot, libraryRoot,
                inbox, VisionProviderCard.providerChoiceOf(provider), container, status, dismissBanner,
                incompleteSave -> refresh(container, header, presenter, firstRun, onFinished, incompleteSave)));

        final var card = new VBox(headline(), opening(firstRun.opening()),
                workingRoot.row(), libraryRoot.row(), inbox.row(),
                SettingsRows.requiredLegend(), FolderRootsHelp.panel(), providerBlock(provider));
        card.getStyleClass().addAll("card", "first-run-card");
        card.setAlignment(Pos.CENTER_LEFT);

        container.getChildren().setAll(card);
        if (banner != null) {
            // Above the card rather than inside it, which is where the reader is carried to.
            SettingsRows.report(container, banner.anythingWasStored() ? null : "settings-banner-caution",
                    banner.message(), false);
            SettingsRows.travelToTop(container);
        }
    }

    /**
     * Takes a press on Save: hands the three folders and the provider over, then shows what the
     * presenter made of them.
     *
     * @param presenter {@link SettingsPresenter} judges the values and stores them
     * @param firstRun {@link FirstRunPresenter} says whether the card has done its job yet
     * @param onFinished {@link Consumer} of {@link String} shows the Dashboard once all three
     *     folders are set
     * @param workingRoot {@link SettingsRows.FolderRow} the working root field and its mark
     * @param libraryRoot {@link SettingsRows.FolderRow} the library root field and its mark
     * @param inbox {@link SettingsRows.FolderRow} the inbox field and its mark
     * @param provider {@link SettingsView.ProviderChoice} the provider the dropdown is on
     * @param container {@link VBox} the card's own body, which a report is put at the top of
     * @param status {@link TextArea} the header's own line
     * @param dismissBanner {@link Runnable} takes the last save's report off the page
     * @param redraw {@link Consumer} of {@link FirstRunPresenter.IncompleteSave} draws this card
     *     again, with a line saying what is still needed
     */
    private static void onSave(final SettingsPresenter presenter, final FirstRunPresenter firstRun,
                               final Consumer<@Nullable String> onFinished, final SettingsRows.FolderRow workingRoot,
                               final SettingsRows.FolderRow libraryRoot, final SettingsRows.FolderRow inbox,
                               final SettingsView.ProviderChoice provider, final VBox container,
                               final TextArea status, final Runnable dismissBanner,
                               final Consumer<FirstRunPresenter.IncompleteSave> redraw) {
        final SaveOutcome outcome = presenter.saveFolderRootsAndProvider(workingRoot.field().getText(),
                libraryRoot.field().getText(), inbox.field().getText(), provider.id());
        switch (outcome) {
            case final SaveOutcome.Saved _ -> settled(firstRun, onFinished, redraw, null);
            case final SaveOutcome.Refused refused -> {
                dismissBanner.run();
                SettingsRows.say(workingRoot.violation(), refused.workingRoot());
                SettingsRows.say(libraryRoot.violation(), refused.libraryRoot());
                SettingsRows.say(inbox.violation(), refused.inbox());
                showRefusal(container, status, refused.message(), refused.warning());
            }
            // Answered here rather than by the dialog Settings opens, since this card has none. A
            // move asked for while a folder is still empty never reaches this: the save seam stores
            // the folders and keeps the library where it is, and answers as a refusal instead.
            case final SaveOutcome.NeedsLibraryRootResolution _ -> {
                dismissBanner.run();
                showRefusal(container, status, firstRun.libraryRootMoveRefusal(), false);
            }
        }
    }

    /**
     * What follows a save that landed: the Dashboard once every folder is set, this card again
     * while any is still missing.
     *
     * @param firstRun {@link FirstRunPresenter} answers which of the two this is
     * @param onFinished {@link Consumer} of {@link String} shows the Dashboard
     * @param redraw {@link Consumer} of {@link String} draws this card again, with a line
     *     saying what is still needed
     * @param reported what the save had to say for itself, or null where it had nothing
     */
    private static void settled(final FirstRunPresenter firstRun, final Consumer<@Nullable String> onFinished,
                                final Consumer<FirstRunPresenter.IncompleteSave> redraw, final @Nullable String reported) {
        final FirstRunPresenter.IncompleteSave incompleteSave = firstRun.savedWhileStillIncomplete(reported);
        if (incompleteSave == null) {
            onFinished.accept(reported);
        } else {
            redraw.accept(incompleteSave);
        }
    }

    private static TextArea headline() {
        final TextArea headline = SelectableText.prose("Sluice needs to know where your photos live.");
        headline.getStyleClass().add("first-run-headline");
        return headline;
    }

    private static TextArea opening(final String text) {
        final TextArea opening = SelectableText.prose(text);
        opening.getStyleClass().add("first-run-opening");
        return opening;
    }

    /**
     * The provider dropdown with the one sentence a first-time reader needs about it.
     *
     * <p>What each provider needs beyond this choice is Settings' business. Saying it here would
     * put a credential and a model in front of somebody who has not chosen a folder yet.
     *
     * @param provider {@link ComboBox} of {@link SettingsView.ProviderChoice} the dropdown
     * @return {@link VBox} the block
     */
    private static VBox providerBlock(final ComboBox<SettingsView.ProviderChoice> provider) {
        final var block = new VBox(SettingsRows.subsectionHeading("What looks at your photos"),
                SettingsRows.helpLine("Sluice does not handle the judgement by itself. It either calls a model you pay "
                        + "for, or waits for an agent you already run to do the looking. Calling a model "
                        + "needs an API key, which you add in Settings."),
                provider);
        block.getStyleClass().add("settings-subsection");
        return block;
    }


    /**
     * Puts a refusal in the header, beside the button that produced it.
     *
     * <p>The page is not moved. Save is pinned, so the reader is already looking at the line this
     * fills, and taking them somewhere else would move them away from the control they just used.
     * Which field is at fault is said by the mark on that field.
     *
     * @param container {@link VBox} the card's own body, which the report is put at the top of
     * @param status {@link TextArea} the header's own line
     * @param message what was refused, or null where nothing was
     * @param warning boolean true where this is a caution rather than a refusal
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
}
