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
        final var workingRoot = SettingsRows.folderRow("Working root", "settings-working-root",
                view.workingRoot(), view.rootLimit());
        final var libraryRoot = SettingsRows.folderRow("Library root", "settings-library-root",
                view.libraryRoot(), view.rootLimit());
        final var inbox = SettingsRows.folderRow("Inbox", "settings-inbox", view.inbox(), view.rootLimit());
        final var card = SettingsRows.card("FOLDERS",
                "Working root is where Sluice works. It makes its own folders underneath for what it "
                        + "has sorted, what it wants you to look at, and the near-duplicates it set "
                        + "aside. Library root is where the photos you keep end up for good. Inbox is "
                        + "where new photos go in, and it usually sits inside the working root.",
                workingRoot.row(), libraryRoot.row(), inbox.row());
        return new Result(card, workingRoot, libraryRoot, inbox);
    }
}
