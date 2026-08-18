package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.ShellPresenter;

import java.util.function.Supplier;

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
     * @param settingsPresenter {@link SettingsPresenter} supplies and drives the Settings pane
     * @return {@link Scene} the shell scene, styled by the base stylesheet
     */
    static Scene scene(final ShellPresenter presenter, final SettingsPresenter settingsPresenter) {
        final var group = new ToggleGroup();
        final var dashboard = navEntry(group, "nav-dashboard", DASHBOARD);
        final var settings = navEntry(group, "nav-settings", SETTINGS);
        final var review = navEntry(group, "nav-review", REVIEW);
        // Firing the entry rather than swapping the content directly, so the sidebar ends up
        // showing Settings as current. Arriving there with Dashboard still marked would leave the
        // two disagreeing about which screen this is.
        final Runnable openSettings = settings::fire;

        final Node opening = dashboardPane(presenter, openSettings);
        opening.setId(DASHBOARD);
        final var content = new VBox(opening);
        content.getStyleClass().add("shell-content");
        // A ToggleGroup lets its own selected toggle be clicked back to unselected, unlike a radio
        // group. Clicking the active nav entry would otherwise leave the sidebar showing none of
        // the three as current. Its content pane would still be the one on screen.
        group.selectedToggleProperty().addListener((_, previous, current) -> {
            if (current == null) {
                group.selectToggle(previous);
            }
        });
        dashboard.setSelected(true);

        dashboard.setOnAction(_ -> show(content, DASHBOARD, () -> dashboardPane(presenter, openSettings)));
        settings.setOnAction(_ -> show(content, SETTINGS,
                () -> filling(SettingsPane.pane(settingsPresenter))));
        review.setOnAction(_ -> show(content, REVIEW, () -> headingPane(REVIEW)));

        final var sidebar = new VBox(dashboard, settings, review);
        sidebar.getStyleClass().add("sidebar");

        final var root = new BorderPane(content);
        root.setLeft(sidebar);
        root.getStyleClass().add("shell");
        final Scene scene = Stylesheet.applyTo(
                new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
        ScreenWarmUp.afterFirstFrame(root);
        return scene;
    }

    /**
     * Shows a screen, unless it is the one already showing.
     *
     * <p>A nav entry fires whether or not it was already the current one, and drawing again builds
     * the screen from what is saved. On Settings that discards anything typed and not yet saved,
     * along with any marks a refused save left on the fields. A click that lands where you already
     * are should cost you nothing.
     *
     * <p>Which screen is showing is read off the screen itself rather than tracked beside it. A
     * second record of that would be a thing to keep in step.
     *
     * @param content {@link VBox} the content area, holding exactly the screen on show
     * @param screen {@link String} the name of the screen being asked for
     * @param draw {@link Supplier} of {@link Node} builds it, called only if it is not up already
     */
    private static void show(final VBox content, final String screen, final Supplier<Node> draw) {
        final Node current = content.getChildren().isEmpty() ? null : content.getChildren().getFirst();
        if (current != null && screen.equals(current.getId())) {
            return;
        }
        final Node next = draw.get();
        next.setId(screen);
        content.getChildren().setAll(next);
    }

    /**
     * Marks a screen as one that takes the height the window has, rather than the height it asks
     * for.
     *
     * <p>A screen that scrolls has to fill, or a taller window adds empty ground beneath it instead
     * of showing more of the page. A card is the opposite case, which is why this is decided per
     * screen. Stretching one gives the same card with the window's ground under it.
     *
     * @param pane {@link Node} the screen about to be shown
     * @return {@link Node} that same screen
     */
    private static Node filling(final Node pane) {
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    /**
     * The Dashboard pane: the welcome card while nothing is configured, an empty heading pane once
     * it is.
     *
     * @param presenter {@link ShellPresenter} says which of the two this is
     * @return a pane ready to sit in the content area
     */
    private static Parent dashboardPane(final ShellPresenter presenter, final Runnable openSettings) {
        return presenter.unconfigured() ? welcomeCard(openSettings) : headingPane(DASHBOARD);
    }

    /**
     * The card a fresh install opens on: nothing is configured yet, and this app cannot do
     * anything until it is.
     *
     * @param openSettings {@link Runnable} shows the Settings screen, as the sidebar would
     * @return {@link VBox} the welcome card
     */
    private static VBox welcomeCard(final Runnable openSettings) {
        final var eyebrow = new Label("WELCOME");
        eyebrow.getStyleClass().addAll("eyebrow", "welcome-eyebrow");

        final var headline = new Label("Sluice needs to know where your photos live.");
        headline.getStyleClass().add("welcome-headline");

        final var card = new VBox(eyebrow, headline, welcomeDetail(openSettings));
        card.getStyleClass().addAll("card", "welcome-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    /**
     * The welcome card's own sentence, with Settings as a link to the screen it names.
     *
     * <p>A {@link Hyperlink} rather than coloured text. The word is the one instruction this card
     * gives, and a reader who tried to click it was right to. It is also the control a keyboard
     * reaches and a screen reader announces as a link, which styled text is not.
     *
     * @param openSettings {@link Runnable} shows the Settings screen, as the sidebar would
     * @return {@link TextFlow} the detail line, wrapping like a label would
     */
    private static TextFlow welcomeDetail(final Runnable openSettings) {
        final var before = new Text("Nothing is configured yet. Set your folders in ");
        before.getStyleClass().add("welcome-detail-text");

        final var settings = new Hyperlink(SETTINGS);
        settings.setId("welcome-settings-link");
        settings.getStyleClass().add("welcome-link");
        settings.setOnAction(_ -> openSettings.run());

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
