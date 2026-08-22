package photos.sluice.adapter.ui;

import photos.sluice.domain.paths.PathRole;

/**
 * What a screen calls each of the three folder roots.
 *
 * <p>One name per root, for every surface that says one out loud. A row's own label, a refusal
 * naming the folder it overlaps with, and a line saying which are still needed all come from here.
 * Two of them wording a root differently would read as two different settings.
 */
public final class PathRoleLabels {

    /**
     * The working root, as a screen names it.
     */
    public static final String WORKING_ROOT = "Working root";

    /**
     * The library root, as a screen names it.
     */
    public static final String LIBRARY_ROOT = "Library root";

    /**
     * The inbox, as a screen names it.
     */
    public static final String INBOX = "Inbox";

    /**
     * Prevents instantiation of this constants holder.
     */
    private PathRoleLabels() {
    }

    /**
     * The name for one root. A switch, so a fourth {@link PathRole} fails to compile here rather
     * than reaching a user as a constant name.
     *
     * @param role {@link PathRole} the root to name
     * @return {@link String} what a screen calls it
     */
    public static String of(final PathRole role) {
        return switch (role) {
            case WORKING_ROOT -> WORKING_ROOT;
            case LIBRARY_ROOT -> LIBRARY_ROOT;
            case INBOX -> INBOX;
        };
    }
}
