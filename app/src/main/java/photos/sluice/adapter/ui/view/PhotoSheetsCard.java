package photos.sluice.adapter.ui.view;

import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The PHOTO SHEETS card: how large a montage tile is, and how many share one sheet.
 */
final class PhotoSheetsCard {

    private PhotoSheetsCard() {}

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
                        + "works; both settings below trade something away.",
                SettingsRows.explainedRow("Tile size (pixels)",
                        "How large each photo is drawn on the sheet. Bigger catches the faint junk that "
                                + "small tiles miss, like screenshots and photos of documents, and costs more "
                                + "per photo. What you pay follows this number, not how many fit on a sheet. "
                                + SettingsRows.anythingFrom(view.tileSizeRange()),
                        tileSize, view.tileSizeOverride()),
                SettingsRows.explainedRow("Photos per row",
                        "How many share one sheet. More means fewer sheets and a quicker, cheaper run, and "
                                + "draws every photo smaller, so more of the faint junk goes unnoticed. "
                                + SettingsRows.anythingFrom(view.tilesPerRowRange()),
                        tilesPerRow, view.tilesPerRowOverride()));
        return new Result(card, tileSize, tilesPerRow);
    }
}
