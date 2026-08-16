package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import photos.sluice.adapter.ui.ShellPresenter;

/**
 * The app's main window: a sidebar over three destinations, and whichever one is showing.
 */
final class MainWindow {

    private static final String DASHBOARD = "Dashboard";
    private static final String SETTINGS = "Settings";
    private static final String REVIEW = "Review";

    /**
     * Prevents instantiation of this static factory class.
     */
    private MainWindow() {
    }

    /**
     * Builds the shell: the sidebar on the left, Dashboard showing on the right.
     *
     * @param presenter {@link ShellPresenter} says whether the dashboard opens on the welcome card
     * @return {@link Scene} the shell scene, styled by the base stylesheet
     */
    static Scene scene(final ShellPresenter presenter) {
        final var content = new VBox(dashboardPane(presenter));
        content.getStyleClass().add("shell-content");

        final var group = new ToggleGroup();
        final var dashboard = navEntry(group, "nav-dashboard", DASHBOARD);
        final var settings = navEntry(group, "nav-settings", SETTINGS);
        final var review = navEntry(group, "nav-review", REVIEW);
        // A ToggleGroup lets its own selected toggle be clicked back to unselected, unlike a radio
        // group. Clicking the active nav entry would otherwise leave the sidebar showing none of
        // the three as current. Its content pane would still be the one on screen.
        group.selectedToggleProperty().addListener((_, previous, current) -> {
            if (current == null) {
                group.selectToggle(previous);
            }
        });
        dashboard.setSelected(true);

        dashboard.setOnAction(_ -> content.getChildren().setAll(dashboardPane(presenter)));
        settings.setOnAction(_ -> content.getChildren().setAll(headingPane(SETTINGS)));
        review.setOnAction(_ -> content.getChildren().setAll(headingPane(REVIEW)));

        final var sidebar = new VBox(dashboard, settings, review);
        sidebar.getStyleClass().add("sidebar");

        final var root = new BorderPane(content);
        root.setLeft(sidebar);
        root.getStyleClass().add("shell");
        return Stylesheet.applyTo(new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
    }

    /**
     * The Dashboard pane: the welcome card while nothing is configured, an empty heading pane once
     * it is.
     *
     * @param presenter {@link ShellPresenter} says which of the two this is
     * @return a pane ready to sit in the content area
     */
    private static Parent dashboardPane(final ShellPresenter presenter) {
        return presenter.unconfigured() ? welcomeCard() : headingPane(DASHBOARD);
    }

    /**
     * The card a fresh install opens on: nothing is configured yet, and this app cannot do
     * anything until it is.
     *
     * @return {@link VBox} the welcome card
     */
    private static VBox welcomeCard() {
        final var eyebrow = new Label("WELCOME");
        eyebrow.getStyleClass().addAll("eyebrow", "welcome-eyebrow");

        final var headline = new Label("Sluice needs to know where your photos live.");
        headline.getStyleClass().add("welcome-headline");

        final var card = new VBox(eyebrow, headline, welcomeDetail());
        card.getStyleClass().addAll("card", "welcome-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    /**
     * The welcome card's own sentence, with Settings picked out as the one word in it that names
     * a real destination.
     *
     * @return {@link TextFlow} the detail line, wrapping like a label would
     */
    private static TextFlow welcomeDetail() {
        final var before = new Text("Nothing is configured yet. Set your folders in ");
        before.getStyleClass().add("welcome-detail-text");

        final var settings = new Text(SETTINGS);
        settings.getStyleClass().add("welcome-detail-highlight");

        final var after = new Text(" to get started.");
        after.getStyleClass().add("welcome-detail-text");

        final var flow = new TextFlow(before, settings, after);
        flow.getStyleClass().add("welcome-detail");
        return flow;
    }

    /**
     * A pane that says only which screen this is.
     *
     * @param heading {@link String} the screen's name
     * @return {@link VBox} the placeholder pane
     */
    private static VBox headingPane(final String heading) {
        final var label = new Label(heading);
        label.getStyleClass().add("pane-heading");

        final var pane = new VBox(label);
        pane.getStyleClass().add("placeholder-pane");
        return pane;
    }

    private static ToggleButton navEntry(final ToggleGroup group, final String id, final String label) {
        final var entry = new ToggleButton(label);
        entry.setId(id);
        entry.setToggleGroup(group);
        entry.getStyleClass().add("nav-item");
        return entry;
    }
}
