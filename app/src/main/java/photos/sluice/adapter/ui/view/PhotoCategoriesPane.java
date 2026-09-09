package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesView;
import photos.sluice.adapter.ui.PhotoCategoriesView.CardRefusal;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryEdit;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;
import photos.sluice.adapter.ui.PhotoCategoriesView.SaveOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The Photo categories screen: the categories photos are sifted into, and what each one is for.
 *
 * <p>Every value it shows and every sentence on it comes from {@link PhotoCategoriesPresenter}. This
 * class lays those out and hands back what was typed. It never decides what a card means or whether
 * one is allowed.
 *
 * <p>Reached from the Settings screen rather than the sidebar, and it takes the whole content area
 * while it is up. Back returns to Settings.
 */
final class PhotoCategoriesPane {

    private static final String SAVED = "Photo categories saved.";

    private PhotoCategoriesPane() {}

    /**
     * Builds the pane, ready to sit in the shell's content area.
     *
     * @param presenter {@link PhotoCategoriesPresenter} supplies what to show and carries out a save
     * @param onBack {@link Runnable} returns to the screen this was opened from
     * @return {@link Mounted} the pane, and the way to ask whether leaving would lose anything
     */
    static Mounted pane(final PhotoCategoriesPresenter presenter, final Runnable onBack) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        // The header survives a save, which rebuilds the body under it.
        final PageHeader.Result header =
                PageHeader.build("Photo categories", "photo-categories-save", backButton(onBack));
        // Replaced on every draw, since a save rebuilds every card. Whatever asks about unsaved work
        // has to read the cards standing now rather than the ones this page opened with.
        final var onScreen = new AtomicReference<List<CategoryCard.Result>>(List.of());
        refresh(container, header, presenter, onBack, onScreen);
        return new Mounted(PageHeader.pinnedOver(header, container),
                () -> presenter.hasUnsavedEdits(editsOf(onScreen.get())));
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
     * Draws the screen from what the presenter says, replacing whatever was there.
     *
     * <p>A save rebuilds, so every field shows what was actually stored, and a card the save
     * normalised comes back normalised. A refusal does not: what the reader typed is what they have
     * to fix, and rebuilding would take it away from them.
     *
     * @param container {@link VBox} the pane's own body
     * @param header {@link PageHeader.Result} the pinned bar, which outlives every redraw
     * @param presenter {@link PhotoCategoriesPresenter} supplies the state and takes the actions
     * @param onBack {@link Runnable} returns to the screen this was opened from
     * @param onScreen an {@link AtomicReference} to the cards now drawn, replaced by this draw
     */
    private static void refresh(final VBox container, final PageHeader.Result header,
                                final PhotoCategoriesPresenter presenter,
                                final Runnable onBack,
                                final AtomicReference<List<CategoryCard.Result>> onScreen) {
        final PhotoCategoriesView view = presenter.view();
        header.clearStatus();
        container.getChildren().clear();
        final TextArea summary = header.status();
        summary.setId("photo-categories-summary");

        final var intro = SettingsRows.helpLine(view.disposition());
        intro.setId("photo-categories-disposition");
        final var rule = SettingsRows.helpLine(view.nameRule());
        rule.setId("photo-categories-name-rule");
        container.getChildren().addAll(intro, rule);

        final var cards = new VBox();
        cards.getStyleClass().add("category-cards");
        final var built = new ArrayList<CategoryCard.Result>();
        final var add = new Button("Add a category");
        add.setId("photo-categories-add");
        for (final CategoryRow row : view.categories()) {
            add(presenter, cards, built, row, view.limits(), add);
        }
        add.setOnAction(_ -> {
            add(presenter, cards, built, presenter.blankCard(), view.limits(), add);
            SettingsRows.bringIntoView(built.getLast().card());
        });
        // The summary lives in the pinned header beside Save, where the Settings screen puts its own.
        // A refused save reads the same way on both, and it is next to the button that produced it.
        header.save().setOnAction(_ -> onSave(presenter, container, header, onBack, built, summary, onScreen));
        container.getChildren().addAll(cards, actions(add));
        onScreen.set(built);
    }

    /**
     * The cards on screen, as the values a save would be handed.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @return a {@link List} of {@link CategoryEdit} what each card holds right now
     */
    private static List<CategoryEdit> editsOf(final List<CategoryCard.Result> built) {
        return built.stream()
                .map(card -> new CategoryEdit(card.name().getText(), card.description().getText(),
                        CategoryCard.linesOf(card.examples()), card.enabled().isSelected()))
                .toList();
    }

    /**
     * Adds one card to the page, and gives its Delete button the only thing it needs to know: which
     * card it belongs to.
     *
     * <p>Deleting works on the live lists rather than rebuilding the page, so every other card keeps
     * whatever is typed into it. A rebuild here would read from the saved settings and quietly throw
     * that away.
     *
     * @param presenter {@link PhotoCategoriesPresenter} says when a card is the last one still on
     * @param cards {@link VBox} the container the cards sit in
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param row {@link CategoryRow} the card to draw
     * @param limits {@link PhotoCategoriesView.Limits} how much each of its fields may hold
     * @param add {@link Button} the Add control, withdrawn once the set is full
     */
    private static void add(final PhotoCategoriesPresenter presenter, final VBox cards,
                            final List<CategoryCard.Result> built, final CategoryRow row,
                            final PhotoCategoriesView.Limits limits, final Button add) {
        // Delete has to name the card it belongs to, and the card does not exist until build()
        // returns. Holding it and removing by identity keeps Delete right once a card above it
        // has gone. A position captured here would be somebody else's by then.
        final var self = new CategoryCard.Result[1];
        self[0] = CategoryCard.build(row, limits, () -> {
            cards.getChildren().remove(self[0].card());
            built.remove(self[0]);
            add.setDisable(presenter.isTheSetFull(built.size()));
        });
        cards.getChildren().add(self[0].card());
        built.add(self[0]);
        add.setDisable(presenter.isTheSetFull(built.size()));
    }

    /**
     * The page's own action: add a card. Save is in the pinned header above.
     *
     * @param add {@link Button} the Add control, already wired and already knowing whether it fits
     * @return {@link HBox} the action row
     */
    private static HBox actions(final Button add) {
        final var row = new HBox(add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-actions");
        return row;
    }

    /**
     * Hands the cards to the presenter and shows what came back.
     *
     * @param presenter {@link PhotoCategoriesPresenter} carries out the save
     * @param container {@link VBox} the pane's own body, redrawn once a save takes
     * @param onBack {@link Runnable} returns to the screen this was opened from
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link TextArea} the bar's own line, where a refusal lands
     * @param onScreen an {@link AtomicReference} to the cards now drawn, replaced by a save that took
     */
    private static void onSave(final PhotoCategoriesPresenter presenter, final VBox container,
                               final PageHeader.Result header,
                               final Runnable onBack, final List<CategoryCard.Result> built,
                               final TextArea summary,
                               final AtomicReference<List<CategoryCard.Result>> onScreen) {
        final List<CategoryEdit> edits = editsOf(built);
        clearRefusal(built, summary);
        switch (presenter.save(edits)) {
            case SaveOutcome.Saved _ -> {
                refresh(container, header, presenter, onBack, onScreen);
                SettingsRows.report(container, null, SAVED, true);
                // Save is this page's default button, so Enter fires it with the caret still in a
                // card. The rebuild takes that field out of the scene, focus goes to whatever the
                // window finds next, and a scrolling pane travels to wherever it lands. Handing it
                // to Save instead keeps focus outside what scrolls, so the only movement is the one
                // below.
                header.save().requestFocus();
                SettingsRows.travelToTop(container);
            }
            case final SaveOutcome.Refused refused -> showRefusal(container, built, summary, refused);
        }
    }

    /**
     * Marks every card the save found something wrong with.
     *
     * <p>The refusal list runs parallel to what was submitted, which is what makes marking by
     * position right rather than convenient. A name is one of the things a save can refuse.
     * Matching a message back to its card by name would fail on exactly the cards that need it.
     *
     * <p>The page travels to the first card at fault, since the summary says a card below is marked
     * and the marks are what the reader has to act on. The cursor is not taken there: focus would
     * scroll the page itself, and a card at fault has three fields that could hold it.
     *
     * @param container {@link VBox} the page, which carries the banner
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link TextArea} the page-level message
     * @param refused {@link SaveOutcome.Refused} what came back
     */
    private static void showRefusal(final VBox container, final List<CategoryCard.Result> built,
                                    final TextArea summary, final SaveOutcome.Refused refused) {
        summary.setText("");
        SelectableText.dressAs(summary, "settings-save-status");
        SettingsRows.report(container, "settings-banner-violation", refused.summary(), false);
        CategoryCard.Result firstAtFault = null;
        for (int i = 0; i < built.size() && i < refused.cards().size(); i++) {
            final CardRefusal refusal = refused.cards().get(i);
            final CategoryCard.Result card = built.get(i);
            SettingsRows.say(card.nameViolation(), refusal.name());
            SettingsRows.say(card.descriptionViolation(), refusal.description());
            SettingsRows.say(card.examplesViolation(), refusal.examples());
            if (firstAtFault == null && refusal.isAtFault()) {
                firstAtFault = card;
            }
        }
        if (firstAtFault != null) {
            SettingsRows.bringIntoView(firstAtFault.card());
        }
    }

    /**
     * Takes away every message the last refusal left, so a card the reader has since fixed does not
     * keep the mark it earned two saves ago.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link TextArea} the page-level message
     */
    private static void clearRefusal(final List<CategoryCard.Result> built, final TextArea summary) {
        // The class goes with the text. The bar is built once and outlives every rebuild, so a
        // refusal's red would otherwise stay on it under whatever the next press has to say.
        summary.setText("");
        SelectableText.dressAs(summary, "settings-save-status");
        for (final CategoryCard.Result card : built) {
            SettingsRows.say(card.nameViolation(), null);
            SettingsRows.say(card.descriptionViolation(), null);
            SettingsRows.say(card.examplesViolation(), null);
        }
    }

    /**
     * The way back to the screen this was opened from, for the pinned header to carry.
     *
     * @param onBack {@link Runnable} returns to that screen
     * @return {@link Button} the way back
     */
    private static Button backButton(final Runnable onBack) {
        return WayBack.to("photo-categories-back", "Back to Settings", onBack);
    }

}
