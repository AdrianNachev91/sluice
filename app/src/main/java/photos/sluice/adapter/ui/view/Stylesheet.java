package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;

/**
 * The look every Sluice window starts from: the base stylesheet, and the size a window opens at.
 * One place for both, so no window carries the resource path or picks its own opening size.
 */
final class Stylesheet {

    /** The width the window opens at. */
    static final int INITIAL_WIDTH = 1024;

    /** The height the window opens at. */
    static final int INITIAL_HEIGHT = 700;

    private static final String BASE_SHEET = "/ui/sluice.css";

    /**
     * Prevents instantiation of this static utility class.
     */
    private Stylesheet() {
    }

    /**
     * Adds the base stylesheet to a scene.
     *
     * @param scene {@link Scene} the scene to style
     * @return {@link Scene} that same scene, styled
     */
    static Scene applyTo(final Scene scene) {
        scene.getStylesheets().add(url());
        return scene;
    }

    /**
     * Resolves the base stylesheet on the classpath. A missing sheet is a packaging fault rather
     * than a runtime condition, so it fails here instead of leaving every window unstyled with no
     * word about why.
     *
     * @return {@link String} the stylesheet's external form
     */
    private static String url() {
        final var resource = Stylesheet.class.getResource(BASE_SHEET);
        if (resource == null) {
            throw new IllegalStateException("The base stylesheet " + BASE_SHEET + " is missing from the build.");
        }
        return resource.toExternalForm();
    }
}
