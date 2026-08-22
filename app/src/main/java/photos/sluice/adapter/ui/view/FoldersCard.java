package photos.sluice.adapter.ui.view;

import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The FOLDERS card: working root, library root, and inbox.
 */
final class FoldersCard {

    private FoldersCard() {}

    /**
     * The built card, and each root's row, so a save can read the three values and mark the one at
     * fault.
     *
     * @param card {@link VBox} the card itself, for the page to lay out
     * @param workingRoot {@link SettingsRows.FolderRow} the working-root row
     * @param libraryRoot {@link SettingsRows.FolderRow} the library-root row
     * @param inbox {@link SettingsRows.FolderRow} the inbox row
     */
    record Result(VBox card, SettingsRows.FolderRow workingRoot, SettingsRows.FolderRow libraryRoot,
                  SettingsRows.FolderRow inbox) {
    }

    static Result build(final SettingsView view) {
        final SettingsRows.FolderRow workingRoot = FolderRootRows.workingRoot(view);
        final SettingsRows.FolderRow libraryRoot = FolderRootRows.libraryRoot(view);
        final SettingsRows.FolderRow inbox = FolderRootRows.inbox(view);
        final var card = SettingsRows.card("FOLDERS", null, workingRoot.row(), libraryRoot.row(), inbox.row(),
                SettingsRows.requiredLegend(), FolderRootsHelp.panel());
        return new Result(card, workingRoot, libraryRoot, inbox);
    }
}
