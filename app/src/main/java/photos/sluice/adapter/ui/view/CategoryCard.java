package photos.sluice.adapter.ui.view;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.PhotoCategoriesView;
import photos.sluice.adapter.ui.PhotoCategoriesView.CategoryRow;

import java.util.List;

/**
 * One category card on the Photo categories screen. What it is called, what belongs in it, and the
 * examples that go to a vision model beside that. Its switch says whether a cull routes to it.
 */
final class CategoryCard {

    // Enough of a long description to read as prose rather than a slot, and short enough that
    // several cards still fit a screen. The box scrolls past it, since no row count that also fits
    // the page shows a paragraph whole.
    private static final int DESCRIPTION_ROWS = 4;
    private static final int EXAMPLES_ROWS = 2;

    private CategoryCard() {}

    /**
     * The controls one card is made of, so the pane can read back what was typed into them.
     *
     * @param card {@link VBox} the whole card, ready to sit on the page
     * @param name {@link TextField} the name field, uneditable on the built-in card
     * @param description {@link TextArea} the "what belongs here" box
     * @param examples {@link TextArea} the examples box, one per line
     * @param enabled {@link CheckBox} the card's own switch
     * @param delete {@link Button} its Delete, or null on the built-in card, which has none
     * @param nameViolation {@link TextArea} what is wrong with the name, empty when nothing is
     * @param descriptionViolation {@link TextArea} what is wrong with the description
     * @param examplesViolation {@link TextArea} what is wrong with the examples
     */
    record Result(VBox card, TextField name, TextArea description, TextArea examples, CheckBox enabled,
                  @Nullable Button delete, TextArea nameViolation, TextArea descriptionViolation,
                  TextArea examplesViolation) {
    }

    /**
     * Builds one card.
     *
     * @param row {@link CategoryRow} what to draw, including whether this card is the fixed one
     * @param limits {@link PhotoCategoriesView.Limits} how much each of its fields may hold
     * @param onDelete {@link Runnable} takes this card off the page, never called for a fixed card
     * @return {@link Result} the card and the controls behind it
     */
    static Result build(final CategoryRow row, final PhotoCategoriesView.Limits limits,
                        final Runnable onDelete) {
        final var name = new TextField(row.name());
        name.setId("category-name");
        SettingsRows.holdTo(name, limits.name());
        final var nameViolation = SettingsRows.violationLabel();
        SettingsRows.markWhileRefused(name, nameViolation);

        final TextArea description = box(row.description(), DESCRIPTION_ROWS, "category-description");
        SettingsRows.holdTo(description, limits.description());
        final var descriptionViolation = SettingsRows.violationLabel();
        SettingsRows.markWhileRefused(description, descriptionViolation);

        final TextArea examples = box(String.join("\n", row.examples()), EXAMPLES_ROWS, "category-examples");
        // One ceiling over the whole box, because the box is the control. What a card is judged by
        // counts the entries and measures each, so a box the reader was allowed to fill can still
        // be refused. The presenter says so under the box rather than letting the value type throw.
        SettingsRows.holdTo(examples, limits.examples());
        final var examplesViolation = SettingsRows.violationLabel();
        SettingsRows.markWhileRefused(examples, examplesViolation);

        final var enabled = new CheckBox();
        enabled.setId("category-enabled");
        enabled.setSelected(row.enabled());
        // The label says which state the card is in rather than what the box does. A tick and the
        // word then agree, and a reader skimming a page of cards can read the words alone.
        enabled.textProperty().bind(
                Bindings.when(enabled.selectedProperty()).then("Enabled").otherwise("Disabled"));

        final Button delete = row.fixedReason() == null ? deleteButton(onDelete) : null;
        final var card = new VBox(header(enabled, delete), nameRow(name), nameViolation);
        card.getStyleClass().addAll("card", "category-card");
        if (row.fixedReason() != null) {
            name.setEditable(false);
            name.getStyleClass().add("field-fixed");
            name.setFocusTraversable(false);
            card.getChildren().add(SettingsRows.helpLine(row.fixedReason()));
        }
        card.getChildren().addAll(
                fieldRow("What belongs here", description, descriptionViolation),
                fieldRow("Examples (optional, one per line)", examples, examplesViolation));
        return new Result(card, name, description, examples, enabled, delete, nameViolation,
                descriptionViolation, examplesViolation);
    }

    /**
     * What was typed into the examples box, as the lines a card carries.
     *
     * <p>Split and handed over as typed. What counts as an example, and what a blank line among
     * them means, is the card value's own rule. It applies that to every source, not only to
     * this one.
     *
     * @param examples {@link TextArea} the box
     * @return a {@link List} of {@link String} its lines
     */
    static List<String> linesOf(final TextArea examples) {
        return examples.getText().lines().toList();
    }

    /**
     * The name field under its own label, the first of the card's fields.
     *
     * <p>What a name may be is said once for the page rather than under every field. It is the
     * same sentence on every card. Repeating it made each card taller than the prose it holds.
     *
     * @param name {@link TextField} the name field
     * @return {@link VBox} the row
     */
    private static VBox nameRow(final TextField name) {
        final var label = SettingsRows.fieldLabel("Name");
        final var group = new VBox(label, name);
        group.getStyleClass().add("settings-row");
        return group;
    }

    /**
     * A multi-line field under its own label, with room for a message beneath it.
     *
     * @param label {@link String} what the field is called
     * @param field {@link TextArea} the box
     * @param violation {@link TextArea} what is wrong with it, empty while nothing is
     * @return {@link VBox} the row
     */
    private static VBox fieldRow(final String label, final TextArea field,
                                 final TextArea violation) {
        final var row = new VBox(SettingsRows.fieldLabel(label), field, violation);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /**
     * The card's first line: its switch on the left, and Delete pushed to the right. Whether a card
     * counts at all is what a reader decides before reading its fields, and Delete sits furthest
     * from the fields it would take.
     *
     * @param enabled {@link CheckBox} the card's own switch
     * @param delete {@link Button} its Delete, or null for a card that has none
     * @return {@link HBox} the line
     */
    private static HBox header(final CheckBox enabled, final @Nullable Button delete) {
        final var line = new HBox(enabled, SettingsRows.spacer());
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("category-header");
        if (delete != null) {
            line.getChildren().add(delete);
        }
        return line;
    }

    /**
     * The card's own Delete.
     *
     * @param onDelete {@link Runnable} takes this card off the page
     * @return {@link Button} the button
     */
    private static Button deleteButton(final Runnable onDelete) {
        final var delete = new Button();
        delete.setId("category-delete");
        delete.getStyleClass().add("category-delete");
        delete.setGraphic(binGlyph());
        // The only name this control has, since it carries no text. A screen reader and every
        // automated check read it; nothing on screen does.
        delete.setAccessibleText("Delete this category");
        delete.setOnAction(_ -> onDelete.run());
        return delete;
    }

    /**
     * A waste bin, drawn from shapes. A font that happens to carry a symbol on one platform carries
     * a blank box on another, and this app ships to three.
     *
     * <p>The body is a polygon rather than a rectangle, because a bin that does not narrow reads as
     * a cup.
     *
     * @return {@link Group} the glyph, 14 wide and 15 tall
     */
    private static Group binGlyph() {
        final var handle = new Rectangle(5, 1.6, 4, 1.4);
        final var lid = new Rectangle(2, 3, 10, 1.6);
        final var body = new Polygon(3.2, 5.2, 10.8, 5.2, 10, 14, 4, 14);
        final var left = new Rectangle(5.4, 7, 1, 5);
        final var right = new Rectangle(7.6, 7, 1, 5);
        final var glyph = new Group(handle, lid, body, left, right);
        glyph.getChildren().forEach(piece -> piece.getStyleClass().add("bin-glyph"));
        return glyph;
    }

    /**
     * A wrapping multi-line field of a fixed height, which scrolls rather than growing.
     *
     * @param text {@link String} what it opens holding
     * @param rows int how many lines of it are visible
     * @param id {@link String} the node id the box carries
     * @return {@link TextArea} the box
     */
    private static TextArea box(final String text, final int rows, final String id) {
        final var area = new TextArea(text);
        area.setId(id);
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        return area;
    }
}
