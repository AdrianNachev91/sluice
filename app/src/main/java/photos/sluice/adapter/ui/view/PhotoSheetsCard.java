package photos.sluice.adapter.ui.view;

import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The PHOTO SHEETS card: how large a montage tile is, and how many share one sheet.
 */
final class PhotoSheetsCard {

    private PhotoSheetsCard() {}

    /**
     * The built card, and the two fields a save has to read its numbers back off.
     *
     * @param card {@link VBox} the card itself, for the page to lay out
     * @param tileSize {@link Spinner} of {@link Integer} how large each photo is drawn
     * @param tilesPerRow {@link Spinner} of {@link Integer} how many share one sheet
     */
    record Result(VBox card, Spinner<Integer> tileSize, Spinner<Integer> tilesPerRow) {
    }

    static Result build(final SettingsView view) {
        final var tileSize = SettingsRows.numberField(view.tileSizeRange(), view.tileSize());
        tileSize.setId("settings-tile-size");
        final var tilesPerRow = SettingsRows.numberField(view.tilesPerRowRange(), view.tilesPerRow());
        tilesPerRow.setId("settings-tiles-per-row");
        final var card = SettingsRows.card("PHOTO SHEETS",
                "Photos are not sent one at a time. Sluice tiles them into sheets, and whatever "
                        + "is doing the looking reads a sheet at a time. The defaults are a balance that "
                        + "works. Both settings below trade something away.",
                SettingsRows.explainedRow("Tile size (pixels)",
                        "How large each photo is drawn on the sheet. Bigger is easier to judge, so fewer "
                                + "photos end up in the wrong place, and costs more per photo. What you pay "
                                + "follows this number, not how many fit on a sheet. "
                                + SettingsRows.rangeSentence(view.tileSizeRange()),
                        tileSize, view.tileSizeOverride()),
                SettingsRows.explainedRow("Photos per row",
                        "How many share one sheet. More means fewer sheets and a quicker, cheaper run, and "
                                + "draws every photo smaller, so more of them end up in the wrong place. "
                                + SettingsRows.rangeSentence(view.tilesPerRowRange()),
                        tilesPerRow, view.tilesPerRowOverride()));
        return new Result(card, tileSize, tilesPerRow);
    }
}
