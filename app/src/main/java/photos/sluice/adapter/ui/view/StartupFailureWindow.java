package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.StartupFailurePresenter;

/**
 * What the app shows when its own startup failed. It stands in for the real window rather than
 * appearing beside it, since nothing behind it came up.
 */
final class StartupFailureWindow {

    /**
     * Prevents instantiation of this static factory class.
     */
    private StartupFailureWindow() {
    }

    /**
     * Builds the failure scene from what the presenter decided to say.
     *
     * @param presenter {@link StartupFailurePresenter} supplies the headline and the detail
     * @return {@link Scene} the failure scene, styled by the base stylesheet
     */
    static Scene scene(final StartupFailurePresenter presenter) {
        final var headline = new Label(presenter.headline());
        headline.getStyleClass().add("failure-headline");

        final var detail = new Label(presenter.detail());
        detail.getStyleClass().add("failure-detail");

        final var root = new VBox(headline, detail);
        root.getStyleClass().addAll("screen", "failure-screen");
        return Stylesheet.applyTo(new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
    }
}
