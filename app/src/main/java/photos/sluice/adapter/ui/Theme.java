package photos.sluice.adapter.ui;

import javafx.application.ColorScheme;

import java.util.List;

/**
 * The two looks the app has, and the stylesheets each one is built from. Which look a window wears
 * is a decision, so it is made here rather than in a window.
 *
 * <p>Dark is the base sheet plus an overlay that redefines colours and nothing else. Every rule
 * about spacing, size and shape is therefore written once. Neither look can drift from the other
 * over anything but colour.
 */
public enum Theme {

    /** The default look. */
    LIGHT,

    /** The look for a desktop set to a dark colour scheme. */
    DARK;

    private static final String BASE_SHEET = "/ui/sluice.css";
    private static final String DARK_SHEET = "/ui/sluice-dark.css";

    /**
     * The look matching a desktop's colour scheme.
     *
     * @param scheme {@link ColorScheme} the scheme the desktop reports
     * @return {@link Theme} the look to wear
     */
    public static Theme matching(final ColorScheme scheme) {
        return scheme == ColorScheme.DARK ? DARK : LIGHT;
    }

    /**
     * The stylesheets this look is built from, in the order they have to be applied. The base comes
     * first in both, since the overlay only has an effect on top of it.
     *
     * @return a {@link List} of {@link String} classpath resource paths
     */
    public List<String> sheets() {
        return this == DARK ? List.of(BASE_SHEET, DARK_SHEET) : List.of(BASE_SHEET);
    }
}
