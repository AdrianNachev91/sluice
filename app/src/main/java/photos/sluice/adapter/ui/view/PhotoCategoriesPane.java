package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesView;
import photos.sluice.adapter.ui.PhotoCategoriesView.CardRefusal;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryEdit;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;
import photos.sluice.adapter.ui.PhotoCategoriesView.SaveOutcome;

import java.util.ArrayList;
import java.util.List;

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
     * @return {@link Node} the photo categories pane
     */
    static Node pane(final PhotoCategoriesPresenter presenter, final Runnable onBack) {
        final var container = new VBox();
        container.getStyleClass().add("settings-pane");
        // The header sits outside what scrolls, so the way back and Save both stay reachable from
        // anywhere on a page that is taller than the window. It also survives a save, which rebuilds
        // the body.
        final PageHeader.Result header =
                PageHeader.build("Photo categories", "photo-categories-save", backButton(onBack));
        refresh(container, header, presenter, onBack);
        return PageHeader.pinnedOver(header, container);
    }

    /**
     * Draws the screen from what the presenter says, replacing whatever was there.
     *
     * <p>A save rebuilds, so every field shows what was actually stored, and a card the save
     * normalised comes back normalised. A refusal does not: what the reader typed is what they have
     * to fix, and rebuilding would take it away from them.
     *
     * @param container {@link VBox} the pane's own body
     * @param presenter {@link PhotoCategoriesPresenter} supplies the state and takes the actions
     * @param onBack {@link Runnable} returns to the screen this was opened from
     */
    private static void refresh(final VBox container, final PageHeader.Result header,
                                final PhotoCategoriesPresenter presenter,
                                final Runnable onBack) {
        final PhotoCategoriesView view = presenter.view();
        header.clearStatus();
        container.getChildren().clear();
        final Label summary = header.status();
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
        header.save().setOnAction(_ -> onSave(presenter, container, header, onBack, built, summary));
        container.getChildren().addAll(cards, actions(add));
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
            keepOneSwitchedOn(presenter, built);
            add.setDisable(presenter.isTheSetFull(built.size()));
        });
        cards.getChildren().add(self[0].card());
        built.add(self[0]);
        self[0].enabled().selectedProperty()
                .addListener((_, _, _) -> keepOneSwitchedOn(presenter, built));
        keepOneSwitchedOn(presenter, built);
        add.setDisable(presenter.isTheSetFull(built.size()));
    }

    /**
     * Takes away every control that would leave the page with nothing switched on.
     *
     * <p>One rule over two controls. Switching the last card off and deleting it reach the same
     * state, so the card holding the set above the floor offers neither. Both come back as soon as a
     * second card is on. A card already off keeps its Delete: it is contributing nothing to the
     * count.
     *
     * <p>Re-run on every toggle, add and delete, since all three move the count. The count is read
     * off the controls rather than off saved settings. What it answers for is the page as it
     * stands, unsaved edits included.
     *
     * @param presenter {@link PhotoCategoriesPresenter} owns the floor and judges each card against it
     * @param built a {@link List} of {@link CategoryCard.Result} every card on the page
     */
    private static void keepOneSwitchedOn(final PhotoCategoriesPresenter presenter,
                                          final List<CategoryCard.Result> built) {
        final int on = (int) built.stream().filter(card -> card.enabled().isSelected()).count();
        for (final CategoryCard.Result card : built) {
            final boolean holdingItUp = presenter.isTheLastOneOn(on, card.enabled().isSelected());
            card.enabled().setDisable(holdingItUp);
            if (card.delete() != null) {
                card.delete().setDisable(holdingItUp);
            }
        }
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
     * @param summary {@link Label} the bar's own line, where a refusal lands
     */
    private static void onSave(final PhotoCategoriesPresenter presenter, final VBox container,
                               final PageHeader.Result header,
                               final Runnable onBack, final List<CategoryCard.Result> built,
                               final Label summary) {
        final List<CategoryEdit> edits = built.stream()
                .map(card -> new CategoryEdit(card.name().getText(), card.description().getText(),
                        CategoryCard.linesOf(card.examples()), card.enabled().isSelected()))
                .toList();
        clearRefusal(built, summary);
        switch (presenter.save(edits)) {
            case SaveOutcome.Saved _ -> {
                refresh(container, header, presenter, onBack);
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
     * <p>The page is not moved and the cursor is not taken anywhere. Save is pinned, so the reader
     * is already looking at the summary this fills. Focusing the field at fault would scroll the
     * page to it, since a scrolling pane travels to whatever holds focus.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link Label} the page-level message
     * @param refused {@link SaveOutcome.Refused} what came back
     */
    private static void showRefusal(final VBox container, final List<CategoryCard.Result> built,
                                    final Label summary, final SaveOutcome.Refused refused) {
        summary.setText("");
        summary.getStyleClass().setAll("settings-save-status");
        SettingsRows.report(container, "settings-banner-violation", refused.summary(), false);
        for (int i = 0; i < built.size() && i < refused.cards().size(); i++) {
            final CardRefusal refusal = refused.cards().get(i);
            final CategoryCard.Result card = built.get(i);
            SettingsRows.say(card.nameViolation(), refusal.name());
            SettingsRows.say(card.descriptionViolation(), refusal.description());
            SettingsRows.say(card.examplesViolation(), refusal.examples());
        }
    }

    /**
     * Takes away every message the last refusal left, so a card the reader has since fixed does not
     * keep the mark it earned two saves ago.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link Label} the page-level message
     */
    private static void clearRefusal(final List<CategoryCard.Result> built, final Label summary) {
        // The class goes with the text. The bar is built once and outlives every rebuild, so a
        // refusal's red would otherwise stay on it under whatever the next press has to say.
        summary.setText("");
        summary.getStyleClass().setAll("settings-save-status");
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
        final var back = new Button("Back to Settings");
        back.setId("photo-categories-back");
        back.getStyleClass().add("button-quiet");
        back.setGraphic(uTurnGlyph());
        back.setOnAction(_ -> onBack.run());
        return back;
    }

    /**
     * The arrow on the way back: a band turning through 180 degrees, with the head pointing down at
     * the far end.
     *
     * <p>Drawn as one filled outline rather than a stroked line, because a stroke on an
     * {@link SVGPath} contributes nothing: the shape is filled. Its own coordinates run 0 to 16, and
     * the stylesheet scales it to the size a button's text sits at.
     *
     * @return {@link SVGPath} the glyph
     */
    private static SVGPath uTurnGlyph() {
        final var glyph = new SVGPath();
        glyph.setContent("M12.2 14 L12.2 7 A3.2 3.2 0 0 0 5.8 7 L5.8 9 L8.2 9 L4.9 14 L1.6 9 "
                + "L4 9 L4 7 A5 5 0 0 1 14 7 L14 14 Z");
        glyph.getStyleClass().add("u-turn-glyph");
        return glyph;
    }

}
