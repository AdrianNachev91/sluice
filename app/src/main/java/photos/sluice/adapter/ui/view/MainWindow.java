package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;

import java.util.function.Supplier;

/**
 * The app's main window: a sidebar over three destinations, and whichever one is showing.
 */
final class MainWindow {

    private static final String DASHBOARD = "Dashboard";
    private static final String SETTINGS = "Settings";
    private static final String REVIEW = "Review";
    private static final String PHOTO_CATEGORIES = "Photo categories";

    /**
     * Prevents instantiation of this static factory class.
     */
    private MainWindow() {
    }

    /**
     * Builds the shell: the sidebar on the left, Dashboard showing on the right.
     *
     * <p>Building the shell also starts the check below, so a caller that only wants a scene is
     * still spending a call to whichever provider is configured.
     *
     * @param presenter {@link FirstRunPresenter} says whether the dashboard opens on the first-run card
     * @param settingsPresenter {@link SettingsPresenter} supplies and drives the Settings pane
     * @param visionProviderPresenter {@link VisionProviderPresenter} the VISION PROVIDER card's
     *         credential, model catalogue and connection check
     * @param photoCategoriesPresenter {@link PhotoCategoriesPresenter} supplies and drives the photo categories pane
     * @param runLauncherPresenter {@link RunLauncherPresenter} supplies and drives the run launcher
     * @return {@link Scene} the shell scene, styled by the base stylesheet
     */
    static Scene scene(final FirstRunPresenter presenter, final SettingsPresenter settingsPresenter,
                       final VisionProviderPresenter visionProviderPresenter,
                       final PhotoCategoriesPresenter photoCategoriesPresenter,
                       final RunLauncherPresenter runLauncherPresenter) {
        final var group = new ToggleGroup();
        final var dashboard = navEntry(group, "nav-dashboard", DASHBOARD);
        final var settings = navEntry(group, "nav-settings", SETTINGS);
        final var review = navEntry(group, "nav-review", REVIEW);
        // Firing the entry rather than swapping the content directly, so the sidebar ends up
        // showing Settings as current. Arriving there with Dashboard still marked would leave the
        // two disagreeing about which screen this is.
        final Runnable openSettings = settings::fire;

        final var content = new VBox();
        content.getStyleClass().add("shell-content");
        drawDashboard(content, presenter, settingsPresenter, runLauncherPresenter, null);
        // A ToggleGroup lets its own selected toggle be clicked back to unselected, unlike a radio
        // group. Clicking the active nav entry would otherwise leave the sidebar showing none of
        // the three as current. Its content pane would still be the one on screen.
        group.selectedToggleProperty().addListener((_, previous, current) -> {
            if (current == null) {
                group.selectToggle(previous);
            }
        });
        dashboard.setSelected(true);

        dashboard.setOnAction(_ -> show(content, DASHBOARD,
                () -> dashboardPane(content, presenter, settingsPresenter, runLauncherPresenter, null)));
        // A screen without a sidebar entry. Settings stays the destination it was reached from and
        // stays marked as current, and Back is what leaves it.
        final Runnable openPhotoCategories = () -> show(content, PHOTO_CATEGORIES,
                () -> filling(PhotoCategoriesPane.pane(photoCategoriesPresenter, openSettings)));
        settings.setOnAction(_ -> show(content, SETTINGS,
                () -> filling(SettingsPane.pane(settingsPresenter, visionProviderPresenter, openPhotoCategories))));
        review.setOnAction(_ -> show(content, REVIEW, () -> headingPane(REVIEW)));

        final var sidebar = new VBox(dashboard, settings, review);
        sidebar.getStyleClass().add("sidebar");

        final var root = new BorderPane(content);
        root.setLeft(sidebar);
        root.getStyleClass().add("shell");
        final Scene scene = Stylesheet.applyTo(
                new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
        ScreenWarmUp.afterFirstFrame(root);
        checkTheConfiguredProviderOncePainted(settingsPresenter);
        return scene;
    }

    /**
     * Asks the configured provider what this account can run, once the window is up.
     *
     * <p>The thread of its own is what keeps the network call off the paint. The wait for the first
     * frame is a second guard rather than the working one. Nothing here touches the scene today, so
     * taking it away would change nothing. Anything added here later that does touch the scene
     * would hold the opening paint back, and this is what stops it. Kept deliberately, so it does
     * not read as a wait nobody needed.
     *
     * <p>Without it, a provider already holding a key is described on the Settings screen by that
     * provider's own guess at what it offers.
     *
     * @param settingsPresenter {@link SettingsPresenter} runs the check and keeps its answer
     */
    private static void checkTheConfiguredProviderOncePainted(final SettingsPresenter settingsPresenter) {
        AfterFirstFrame.run(() -> Thread.ofVirtual().start(settingsPresenter::refreshModelsAtStartup));
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
     * The Dashboard pane: the first-run card while any folder root is unset, the run launcher once
     * all three are set.
     *
     * @param content {@link VBox} the content area, so a finished first run can redraw it
     * @param presenter {@link FirstRunPresenter} says which of the two this is
     * @param settingsPresenter {@link SettingsPresenter} supplies and saves the first-run fields
     * @param runLauncherPresenter {@link RunLauncherPresenter} supplies and drives the launcher
     * @param said what the save that led here had to report, or null where nothing did
     * @return {@link Node} a pane ready to sit in the content area
     */
    private static Node dashboardPane(final VBox content, final FirstRunPresenter presenter,
                                      final SettingsPresenter settingsPresenter,
                                      final RunLauncherPresenter runLauncherPresenter,
                                      final @Nullable String said) {
        if (presenter.unfinished()) {
            return filling(FirstRunCard.pane(settingsPresenter, presenter,
                    reported -> drawDashboard(content, presenter, settingsPresenter, runLauncherPresenter,
                            reported)));
        }
        final Node launcher = RunLauncherPane.pane(runLauncherPresenter);
        if (said == null) {
            return filling(launcher);
        }
        // A report the save could not leave on the first-run card, because finishing took that card
        // off the screen. It stays until it is dismissed. A library move says what it did with the
        // old folder, and four seconds is not long enough to take that in.
        final var pane = new VBox(launcher);
        VBox.setVgrow(launcher, Priority.ALWAYS);
        pane.getChildren().addFirst(SettingsRows.banner(pane, "dashboard-banner", said, false));
        return filling(pane);
    }

    /**
     * Puts the Dashboard in the content area, whichever of its two states is due.
     *
     * <p>Drawn rather than shown, because the two states share the Dashboard's own name. {@link
     * #show} would read the card already there as the screen being asked for and leave it up.
     *
     * <p>A reader who has moved on is left where they are. This is called back into once a library
     * move finishes, which can be minutes after the button was pressed, and by then the sidebar can
     * be showing a different screen. Replacing it would leave the sidebar and the content
     * disagreeing about which screen this is. The report is dropped with it, which is the lesser
     * loss of the two.
     *
     * @param content {@link VBox} the content area, holding exactly the screen on show
     * @param presenter {@link FirstRunPresenter} says which state the Dashboard is in
     * @param settingsPresenter {@link SettingsPresenter} supplies and saves the first-run fields
     * @param runLauncherPresenter {@link RunLauncherPresenter} supplies and drives the launcher
     * @param said what the save that led here had to report, or null where nothing did
     */
    private static void drawDashboard(final VBox content, final FirstRunPresenter presenter,
                                      final SettingsPresenter settingsPresenter,
                                      final RunLauncherPresenter runLauncherPresenter,
                                      final @Nullable String said) {
        final Node current = content.getChildren().isEmpty() ? null : content.getChildren().getFirst();
        if (current != null && !DASHBOARD.equals(current.getId())) {
            return;
        }
        final Node pane = dashboardPane(content, presenter, settingsPresenter, runLauncherPresenter, said);
        pane.setId(DASHBOARD);
        content.getChildren().setAll(pane);
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
