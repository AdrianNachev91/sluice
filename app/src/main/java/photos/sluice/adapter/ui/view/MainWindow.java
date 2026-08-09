package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;

/**
 * The app's main window. Empty for now: the sidebar and the screens that fill it arrive with the
 * shell.
 */
final class MainWindow {

    /**
     * Prevents instantiation of this static factory class.
     */
    private MainWindow() {
    }

    /**
     * Builds the main scene.
     *
     * @return {@link Scene} the main scene, styled by the base stylesheet
     */
    static Scene scene() {
        final var root = new BorderPane();
        root.getStyleClass().add("screen");
        // The size the window opens at, which the user is free to change. Distinct from sizing a
        // control to a fixed width, which the stylesheet's own note rules out.
        return Stylesheet.applyTo(new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
    }
}
