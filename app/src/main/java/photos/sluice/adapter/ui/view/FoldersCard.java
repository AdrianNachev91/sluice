package photos.sluice.adapter.ui.view;

import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The FOLDERS card: working root, library root, and inbox.
 */
final class FoldersCard {

    private FoldersCard() {}

    record Result(VBox card, SettingsRows.FolderRow workingRoot, SettingsRows.FolderRow libraryRoot,
                  SettingsRows.FolderRow inbox) {
    }

    static Result build(final SettingsView view) {
        final var workingRoot = SettingsRows.folderRow("Working root", "settings-working-root", view.workingRoot());
        final var libraryRoot = SettingsRows.folderRow("Library root", "settings-library-root", view.libraryRoot());
        final var inbox = SettingsRows.folderRow("Inbox", "settings-inbox", view.inbox());
        final var card = SettingsRows.card("FOLDERS",
                "Working root is where Sluice works. It makes its own folders underneath for what it "
                        + "has sorted, what it wants you to look at, and the near-duplicates it set "
                        + "aside. Library root is where the photos you keep end up for good. Inbox is "
                        + "where new photos go in, and it usually sits inside the working root.",
                workingRoot.row(), libraryRoot.row(), inbox.row());
        return new Result(card, workingRoot, libraryRoot, inbox);
    }
}
