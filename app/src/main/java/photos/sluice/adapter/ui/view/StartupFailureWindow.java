package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.StartupFailurePresenter;

/**
 * What the app shows when its own startup failed. It stands in for the real window rather than
 * appearing beside it, since nothing behind it came up.
 *
 * <p>It opens on the product's own mark and name. That is the one thing this window can still say
 * with certainty. It is also the whole of what somebody sees on a first run that fails.
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
        final var root = new VBox(card(presenter));
        root.getStyleClass().addAll("screen", "failure-screen");
        return Stylesheet.applyTo(new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
    }

    /**
     * Builds the panel the screen centres: who is speaking, then what happened.
     *
     * @param presenter {@link StartupFailurePresenter} supplies the headline and the detail
     * @return {@link VBox} the card
     */
    private static VBox card(final StartupFailurePresenter presenter) {
        final var eyebrow = new Label("STARTUP");
        eyebrow.getStyleClass().add("eyebrow");

        final var headline = new Label(presenter.headline());
        headline.getStyleClass().add("failure-headline");

        final var detail = new Label(presenter.detail());
        detail.getStyleClass().add("failure-detail");

        final var rule = new Separator();
        rule.getStyleClass().add("card-rule");

        final var card = new VBox(brand(), rule, eyebrow, headline, detail);
        card.getStyleClass().addAll("card", "failure-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    /**
     * Builds the mark and the name.
     *
     * @return {@link HBox} the brand row
     */
    private static HBox brand() {
        final var mark = BrandMark.styled();

        final var name = new Label("Sluice");
        name.getStyleClass().add("brand-name");

        final var brand = new HBox(mark, name);
        brand.getStyleClass().add("brand");
        return brand;
    }
}
