package photos.sluice.adapter.ui.view;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import photos.sluice.adapter.ui.Theme;
import photos.sluice.adapter.ui.ThemeSelection;

import java.util.List;

/**
 * The look every Sluice window starts from: the stylesheets it carries, and the size it opens at.
 * One place for both, so no window carries a resource path or picks its own opening size.
 */
final class Stylesheet {

    /** The width the screens are laid out against, and the most a window opens showing. */
    static final int INITIAL_WIDTH = 1100;

    /** The height the screens are laid out against, and the most a window opens showing. */
    static final int INITIAL_HEIGHT = 760;

    // Keys the scene's own property map, which is where the restyle listener's strong reference
    // lives. An identity of its own rather than a string, so nothing else can name it by accident.
    private static final Object RESTYLE_LISTENER = new Object();

    private Stylesheet() {
    }

    /**
     * Dresses a scene in the look in force, and keeps it there. Both sources arrive as properties
     * rather than values read once at startup. So a desktop switched between light and dark, or a
     * theme saved in Settings, restyles what is already on screen.
     *
     * @param scene {@link Scene} the scene to style
     * @return {@link Scene} that same scene, styled
     */
    static Scene applyTo(final Scene scene) {
        final var look = ThemeSelection.effectiveTheme();
        wear(scene, look.getValue());

        final ChangeListener<Theme> restyle = (_, _, current) -> wear(scene, current);
        // The look in force belongs to the process and outlives every window in it. A listener
        // registered straight onto it holds the scene it restyles, so each one ever dressed would be
        // kept until the process ended. The strong reference lives on the scene instead, so the two
        // are collectable together. The weak wrapper stays on the property until the next change
        // finds its referent gone, which costs a few words per scene ever dressed.
        scene.getProperties().put(RESTYLE_LISTENER, restyle);
        look.addListener(new WeakChangeListener<>(restyle));
        return scene;
    }

    /**
     * Holds a window that would open bigger than its display down to what the display can show.
     *
     * <p>A window taller than the desktop puts its own bottom edge, and whatever sits on it, out of
     * reach.
     *
     * <p>A maximum rather than a size, because the two are different quantities. A window's size
     * counts its frame, and a scene is laid out in the area inside that frame. Setting a size here
     * would cost the scene whatever the platform's title bar and borders take, and the frame is not
     * measurable before the window is shown.
     *
     * <p>The maximum is lifted once the window is up. It exists to shape the opening only, and a
     * reader who moves the window to a roomier display can then resize into it.
     *
     * <p>No position either. Where the window lands is JavaFX's, which places an unpositioned one
     * inside the usable area of the screen it picks.
     *
     * <p>An area reporting no usable width or height, NaN included, leaves the window alone rather
     * than capping it to nothing.
     *
     * @param stage {@link Stage} the window about to be shown
     * @param area {@link Rectangle2D} the display's usable area
     */
    static void openNoLargerThan(final Stage stage, final Rectangle2D area) {
        if (!(area.getWidth() > 0) || !(area.getHeight() > 0)) {
            return;
        }
        stage.setMaxWidth(area.getWidth());
        stage.setMaxHeight(area.getHeight());
        // Additive, where setOnShown is a single slot another handler would displace.
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> {
            stage.setMaxWidth(Double.MAX_VALUE);
            stage.setMaxHeight(Double.MAX_VALUE);
        });
    }

    /**
     * The stylesheet URLs of the look in force right now, for a container this class cannot dress.
     *
     * <p>A dialog owns its own scene and is gone in seconds, so it reads the look once instead of
     * carrying the restyle listener a long-lived window needs.
     *
     * @return a {@link List} of {@link String} the sheets to wear, in layering order
     */
    static List<String> sheetsInForce() {
        return ThemeSelection.effectiveTheme().getValue().sheets().stream().map(Stylesheet::url).toList();
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
