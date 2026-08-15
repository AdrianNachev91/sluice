package photos.sluice.adapter.ui.view;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Scene;
import photos.sluice.adapter.ui.Theme;

/**
 * The look every Sluice window starts from: the stylesheets it carries, and the size it opens at.
 * One place for both, so no window carries a resource path or picks its own opening size.
 */
final class Stylesheet {

    /** The width the window opens at. */
    static final int INITIAL_WIDTH = 1024;

    /** The height the window opens at. */
    static final int INITIAL_HEIGHT = 700;

    // Keys the scene's own property map, which is where the restyle listener's strong reference
    // lives. An identity of its own rather than a string, so nothing else can name it by accident.
    private static final Object RESTYLE_LISTENER = new Object();

    /**
     * Prevents instantiation of this static utility class.
     */
    private Stylesheet() {
    }

    /**
     * Dresses a scene in the desktop's current look, and keeps it there. The scheme arrives as a
     * property rather than a value read once at startup. So a desktop switched between light and
     * dark while the app is open restyles what is already on screen.
     *
     * @param scene {@link Scene} the scene to style
     * @return {@link Scene} that same scene, styled
     */
    static Scene applyTo(final Scene scene) {
        final var colorScheme = Platform.getPreferences().colorSchemeProperty();
        wear(scene, Theme.matching(colorScheme.get()));

        final ChangeListener<ColorScheme> restyle = (_, _, current) -> wear(scene, Theme.matching(current));
        // The desktop's preferences belong to the process and outlive every window in it. A listener
        // registered straight onto them holds the scene it restyles, so each one ever dressed would
        // be kept until the process ended. The strong reference lives on the scene instead, so the
        // two are collectable together. The weak wrapper stays on the property until the next scheme
        // change finds its referent gone, which costs a few words per scene ever dressed.
        scene.getProperties().put(RESTYLE_LISTENER, restyle);
        colorScheme.addListener(new WeakChangeListener<>(restyle));
        return scene;
    }

    /**
     * Puts one look on a scene, in place of whatever it was wearing.
     *
     * <p>This class owns the scene's stylesheet list from the moment {@link #applyTo} runs. A sheet
     * added to a scene elsewhere survives until the desktop's scheme next changes, and then it is
     * gone. A window wanting its own sheet needs this method to know about it.
     *
     * @param scene {@link Scene} the scene to style
     * @param theme {@link Theme} the look to put on it
     */
    private static void wear(final Scene scene, final Theme theme) {
        scene.getStylesheets().setAll(theme.sheets().stream().map(Stylesheet::url).toList());
    }

    /**
     * Resolves a stylesheet on the classpath. A missing sheet is a packaging fault rather than a
     * runtime condition, so it fails here instead of leaving every window unstyled with no word
     * about why.
     *
     * @param sheet {@link String} the classpath resource path
     * @return {@link String} the stylesheet's external form
     */
    private static String url(final String sheet) {
        final var resource = Stylesheet.class.getResource(sheet);
        if (resource == null) {
            throw new IllegalStateException("The stylesheet " + sheet + " is missing from the build.");
        }
        return resource.toExternalForm();
    }
}
