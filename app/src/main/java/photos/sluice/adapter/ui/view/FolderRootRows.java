package photos.sluice.adapter.ui.view;

import photos.sluice.adapter.ui.PathRoleLabels;
import photos.sluice.adapter.ui.SettingsView;

/**
 * The three folder-root rows, built once for the two screens that show them.
 *
 * <p>First run is where a user chooses these folders and Settings is where they change them, so
 * both draw the same three fields. Built here rather than on each screen, because a name, a
 * sentence or an id that differed between the two would be a difference nobody decided on.
 */
final class FolderRootRows {

    private FolderRootRows() {
    }

    static SettingsRows.FolderRow workingRoot(final SettingsView view) {
        return SettingsRows.folderRow(PathRoleLabels.WORKING_ROOT,
                "Where Sluice does its work. It makes folders here for what it has sorted, what it wants "
                        + "you to look at, and the near-copies it found.",
                "settings-working-root", view.workingRoot(), view.rootLimit());
    }

    static SettingsRows.FolderRow libraryRoot(final SettingsView view) {
        return SettingsRows.folderRow(PathRoleLabels.LIBRARY_ROOT,
                "Where the photos you keep end up for good.",
                "settings-library-root", view.libraryRoot(), view.rootLimit());
    }

    static SettingsRows.FolderRow inbox(final SettingsView view) {
        return SettingsRows.folderRow(PathRoleLabels.INBOX,
                "Where new photos go in, for Sluice to sort.",
                "settings-inbox", view.inbox(), view.rootLimit());
    }
}
