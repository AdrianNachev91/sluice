package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import java.util.stream.Stream;

/**
 * The Photo categories screen: the categories photos are organised into, and what each one is for.
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
        refresh(container, presenter, onBack, null);

        // The header sits outside what scrolls, so the way back stays reachable from anywhere on a
        // page that is taller than the window. It also survives a save, which rebuilds the body.
        final ScrollPane scroll = SettingsRows.scrolling(container);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        final var page = new VBox(header(onBack), scroll);
        page.getStyleClass().add("photo-categories-page");
        return page;
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
     * @param banner what to say above the screen about what just happened, or null for nothing
     */
    private static void refresh(final VBox container, final PhotoCategoriesPresenter presenter,
                                final Runnable onBack, final @Nullable String banner) {
        final PhotoCategoriesView view = presenter.view();
        container.getChildren().clear();
        if (banner != null) {
            container.getChildren().add(savedBanner(container, banner));
        }
        final var summary = SettingsRows.violationLabel();
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
        // The summary sits under the buttons, where the Settings screen puts its own. A refused save
        // then reads the same way on both, and a reader who pressed Save is looking at that spot.
        container.getChildren().addAll(cards,
                actions(presenter, container, onBack, built, summary, add), summary);
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
     * The page's own actions: add a card, and save the lot.
     *
     * @param presenter {@link PhotoCategoriesPresenter} carries out the save
     * @param container {@link VBox} the pane's own body, redrawn after a save takes
     * @param onBack {@link Runnable} returns to the screen this was opened from
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link Label} what a refusal says above the buttons
     * @param add {@link Button} the Add control, already wired and already knowing whether it fits
     * @return {@link HBox} the action row
     */
    private static HBox actions(final PhotoCategoriesPresenter presenter, final VBox container,
                                final Runnable onBack, final List<CategoryCard.Result> built,
                                final Label summary, final Button add) {
        final var save = new Button("Save");
        save.setId("photo-categories-save");
        save.setDefaultButton(true);
        save.setOnAction(_ -> onSave(presenter, container, onBack, built, summary));

        final var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final var row = new HBox(add, spacer, save);
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
     * @param summary {@link Label} what a refusal says above the buttons
     */
    private static void onSave(final PhotoCategoriesPresenter presenter, final VBox container,
                               final Runnable onBack, final List<CategoryCard.Result> built,
                               final Label summary) {
        final List<CategoryEdit> edits = built.stream()
                .map(card -> new CategoryEdit(card.name().getText(), card.description().getText(),
                        CategoryCard.linesOf(card.examples()), card.enabled().isSelected()))
                .toList();
        clearRefusal(built, summary);
        switch (presenter.save(edits)) {
            case SaveOutcome.Saved _ -> {
                refresh(container, presenter, onBack, SAVED);
                SettingsRows.backToTop(container);
            }
            case final SaveOutcome.Refused refused -> showRefusal(built, summary, refused);
        }
    }

    /**
     * Marks every card the save found something wrong with, and sends the reader to the topmost one.
     *
     * <p>The refusal list runs parallel to what was submitted, which is what makes marking by
     * position right rather than convenient. A name is one of the things a save can refuse.
     * Matching a message back to its card by name would fail on exactly the cards that need it.
     *
     * <p>The reader lands on the field that is actually at fault, not on the card's first field.
     * A description refused on the built-in card would otherwise put the cursor in a name nobody
     * can type into. That sits two rows above the box that needs the edit.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link Label} the page-level message
     * @param refused {@link SaveOutcome.Refused} what came back
     */
    private static void showRefusal(final List<CategoryCard.Result> built, final Label summary,
                                    final SaveOutcome.Refused refused) {
        SettingsRows.say(summary, refused.summary());
        Node takeTheReaderThere = null;
        for (int i = 0; i < built.size() && i < refused.cards().size(); i++) {
            final CardRefusal refusal = refused.cards().get(i);
            final CategoryCard.Result card = built.get(i);
            SettingsRows.say(card.nameViolation(), refusal.name());
            SettingsRows.say(card.descriptionViolation(), refusal.description());
            SettingsRows.say(card.examplesViolation(), refusal.examples());
            if (takeTheReaderThere == null && refusal.isAtFault()) {
                takeTheReaderThere = firstAtFault(refusal, card);
            }
        }
        if (takeTheReaderThere != null) {
            takeTheReaderThere.requestFocus();
        }
        SettingsRows.takeTheReaderToTheFault(summary, marks(built));
    }

    /**
     * Every card's marks, in the order the cards are drawn.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @return {@link Label}[] the name and description marks of every card
     */
    private static Label[] marks(final List<CategoryCard.Result> built) {
        return built.stream()
                .flatMap(card -> Stream.of(card.nameViolation(), card.descriptionViolation(),
                        card.examplesViolation()))
                .toArray(Label[]::new);
    }

    /**
     * The topmost field on a card that the save had something to say about.
     *
     * <p>The reader lands on the field actually at fault rather than on the card's first one. A card
     * whose only problem is its examples would otherwise put the cursor in a name that is fine.
     *
     * @param refusal {@link CardRefusal} what the save said about this card
     * @param card {@link CategoryCard.Result} the controls behind it
     * @return {@link Node} the field to take the reader to
     */
    private static Node firstAtFault(final CardRefusal refusal, final CategoryCard.Result card) {
        if (refusal.name() != null) {
            return card.name();
        }
        return refusal.description() != null ? card.description() : card.examples();
    }

    /**
     * Takes away every message the last refusal left, so a card the reader has since fixed does not
     * keep the mark it earned two saves ago.
     *
     * @param built a {@link List} of {@link CategoryCard.Result} the controls behind each card
     * @param summary {@link Label} the page-level message
     */
    private static void clearRefusal(final List<CategoryCard.Result> built, final Label summary) {
        SettingsRows.say(summary, null);
        for (final CategoryCard.Result card : built) {
            SettingsRows.say(card.nameViolation(), null);
            SettingsRows.say(card.descriptionViolation(), null);
            SettingsRows.say(card.examplesViolation(), null);
        }
    }

    /**
     * The page's heading, and the way back to where it was opened from.
     *
     * @param onBack {@link Runnable} returns to that screen
     * @return {@link VBox} the header block
     */
    private static VBox header(final Runnable onBack) {
        final var back = new Button("Back to Settings");
        back.setId("photo-categories-back");
        back.getStyleClass().add("button-quiet");
        back.setGraphic(uTurnGlyph());
        back.setOnAction(_ -> onBack.run());
        final var heading = new Label("Photo categories");
        heading.getStyleClass().add("pane-heading");
        final var block = new VBox(back, heading);
        block.getStyleClass().add("pane-header");
        return block;
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

    /**
     * The banner saying a save took, built the same way as the one on the screen this is reached
     * from. It leaves on its own, because the reader stays on this page and keeps editing. One
     * left standing would still be up the next time a save is refused, above a refusal that says
     * the opposite.
     *
     * @param container {@link VBox} the pane's body, which the banner removes itself from
     * @param message {@link String} what to say
     * @return {@link HBox} the banner
     */
    private static HBox savedBanner(final VBox container, final String message) {
        return SettingsRows.banner(container, "photo-categories-banner", message, true);
    }
}
