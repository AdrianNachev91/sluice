package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.adapter.ui.DashboardMark;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.LeavingUnsaved;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.ReviewPresenter;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.adapter.ui.ScreenFailure;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.TroubleshootPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The app's main window: a sidebar over three destinations, and whichever one is showing.
 */
final class MainWindow {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private static final String DASHBOARD = "Dashboard";
    private static final String SETTINGS = "Settings";
    private static final String REVIEW = "Review";
    private static final String RUNS = "Runs";
    private static final String PHOTO_CATEGORIES = "Photo categories";
    private static final String TROUBLESHOOT = "Troubleshoot";

    private static final String FAILED = "screen-failed";

    // What the count on the Runs entry means, for a reader who meets a number beside a word and no
    // explanation. The screen it leads to says the same thing per run.
    private static final String RUNS_COUNT_MEANS =
            "Sifts you have started that have not finished. Open Runs to carry them on or throw "
                    + "them away.";

    // What the mark on the Dashboard entry means, for a reader who has left that screen. The two
    // read the same way round: the first needs nothing, the second is waiting on them.
    private static final String DASHBOARD_RUNNING = "Something is running on the Dashboard.";

    private static final String DASHBOARD_FINISHED = "Something on the Dashboard has finished, and "
            + "is waiting for you to close it.";

    /**
     * Prevents instantiation of this static factory class.
     */
    private MainWindow() {
    }

    /**
     * Builds the shell: the sidebar on the left, Dashboard showing on the right.
     *
     * <p>Building the shell also starts the configured provider's model check, so a caller that
     * only wants a scene is still spending a call to that provider.
     *
     * @param presenter {@link FirstRunPresenter} says whether the dashboard opens on the first-run card
     * @param settingsPresenter {@link SettingsPresenter} supplies and drives the Settings pane
     * @param visionProviderPresenter {@link VisionProviderPresenter} the VISION PROVIDER card's
     *         credential, model catalogue and connection check
     * @param photoCategoriesPresenter {@link PhotoCategoriesPresenter} supplies and drives the photo categories pane
     * @param runLauncherPresenter {@link RunLauncherPresenter} supplies and drives the run launcher
     * @param runsPresenter {@link RunsPresenter} supplies and drives the runs screen, and answers
     *         the count its sidebar entry carries
     * @param troubleshootPresenter {@link TroubleshootPresenter} supplies and drives the
     *         troubleshoot screen one run's card opens
     * @param reviewPresenter {@link ReviewPresenter} supplies and drives the review screen
     * @param leavingLosesWork an {@link AtomicReference} to whether the screen on show holds work
     *         nobody has saved. Written here as each screen is drawn. Held by the caller so the
     *         quit question can ask it too, since closing the window leaves a screen as surely as
     *         pressing a sidebar entry does
     * @return {@link Scene} the shell scene, styled by the base stylesheet
     */
    static Scene scene(final FirstRunPresenter presenter, final SettingsPresenter settingsPresenter,
                       final VisionProviderPresenter visionProviderPresenter,
                       final PhotoCategoriesPresenter photoCategoriesPresenter,
                       final RunLauncherPresenter runLauncherPresenter,
                       final RunsPresenter runsPresenter,
                       final TroubleshootPresenter troubleshootPresenter,
                       final ReviewPresenter reviewPresenter,
                       final AtomicReference<BooleanSupplier> leavingLosesWork) {
        final var group = new ToggleGroup();
        final var dashboardMark = markBadge();
        final var dashboard = countedNavEntry(group, "nav-dashboard", DASHBOARD, dashboardMark);
        final var settings = navEntry(group, "nav-settings", SETTINGS);
        final var runsCount = countBadge();
        final var runs = countedNavEntry(group, "nav-runs", RUNS, runsCount);
        final var review = navEntry(group, "nav-review", REVIEW);
        // Firing the entry rather than swapping the content directly, so the sidebar ends up
        // showing Settings as current. Arriving there with Dashboard still marked would leave the
        // two disagreeing about which screen this is.
        final Runnable openSettings = settings::fire;
        final ScreenNavigation navigation = location -> {
            switch (location) {
                case SETTINGS -> openSettings.run();
                case RUNS -> runs.fire();
            }
        };

        final var content = new VBox();
        content.getStyleClass().add("shell-content");
        final var leaving = new Leaving(leavingLosesWork);
        drawDashboard(content, presenter, settingsPresenter, runLauncherPresenter, null, navigation);
        // A ToggleGroup lets its own selected toggle be clicked back to unselected, unlike a radio
        // group. Clicking the active nav entry would otherwise leave the sidebar showing none of
        // the three as current. Its content pane would still be the one on screen.
        group.selectedToggleProperty().addListener((_, previous, current) -> {
            if (current == null) {
                group.selectToggle(previous);
            }
        });
        dashboard.setSelected(true);

        // Settings is the entry marked while Photo categories is up, and nothing else on the sidebar
        // can be current then. So that is where the mark goes back to when a reader asked about
        // unsaved work chooses to stay.
        final Runnable markStaysWhereItWas = () -> settings.setSelected(true);
        dashboard.setOnAction(_ -> {
            if (!show(content, DASHBOARD, leaving,
                    () -> dashboardPane(content, presenter, settingsPresenter, runLauncherPresenter, null, navigation))) {
                markStaysWhereItWas.run();
            }
        });
        // A screen without a sidebar entry. Settings stays the destination it was reached from and
        // stays marked as current, and Back is what leaves it.
        final Runnable openPhotoCategories = () -> show(content, PHOTO_CATEGORIES, leaving, () -> {
            final PhotoCategoriesPane.Mounted mounted =
                    PhotoCategoriesPane.pane(photoCategoriesPresenter, openSettings);
            leaving.losesWork().set(mounted.hasUnsavedEdits());
            return filling(mounted.node());
        });
        settings.setOnAction(_ -> show(content, SETTINGS, leaving, () -> {
            final SettingsPane.Mounted mounted =
                    SettingsPane.pane(settingsPresenter, visionProviderPresenter, openPhotoCategories);
            leaving.losesWork().set(mounted.hasUnsavedEdits());
            return filling(mounted.node());
        }));
        review.setOnAction(_ -> {
            if (!show(content, REVIEW, leaving, () -> filling(ReviewPane.pane(reviewPresenter, navigation)))) {
                markStaysWhereItWas.run();
            }
        });
        // Moving a folder into the library is a job like any other, so it reports on the dashboard.
        reviewPresenter.setOpenDashboard(dashboard::fire);

        // Two of them, and the difference is who has already read the folder. The runs screen reads
        // it as it draws, so its own recount only has to put that number on the badge. Every other
        // nav press has nothing fresh to draw from and goes back to disk.
        //
        // Read on a press rather than on a timer of its own, plus once more whenever a run moves
        // with nobody pressing anything. Almost everything that changes the count is done by a
        // reader who is on a screen they will leave. A watch finishing a run is the exception, and
        // it can land while they are anywhere.
        drawMark(dashboardMark, runLauncherPresenter.dashboardMark());
        // Marshalled here rather than by the presenter, which reports from whichever thread moved
        // the job.
        runLauncherPresenter.setShellMark(() -> {
            final DashboardMark mark = runLauncherPresenter.dashboardMark();
            Platform.runLater(() -> drawMark(dashboardMark, mark));
        });
        final Runnable drawCount = () -> runsCount.setText(countOf(runsPresenter));
        final Runnable readThenCount = () -> countInTheBackground(runsPresenter, runsCount);
        // The badge alone, from a reading the presenter has already taken. Marshalled here because
        // a run moving on its own is announced from whatever thread caused it.
        runsPresenter.setRedrawCount(() -> {
            final String outstanding = countOf(runsPresenter);
            Platform.runLater(() -> runsCount.setText(outstanding));
        });
        runs.setOnAction(_ -> {
            if (!show(content, RUNS, leaving, () -> filling(RunsPane.pane(runsPresenter, drawCount, navigation)))) {
                markStaysWhereItWas.run();
            }
        });
        // A screen without a sidebar entry. Runs stays the destination it was reached from and
        // stays marked as current, and Back is what leaves it. Pointed at its run
        // before it is shown, so the screen draws the one the reader pressed rather than the one
        // before it.
        runsPresenter.setOpenTroubleshoot((prepDir, scope) -> {
            troubleshootPresenter.open(prepDir, scope);
            show(content, TROUBLESHOOT, leaving, () -> filling(TroubleshootPane.pane(troubleshootPresenter, navigation)));
        });
        // Hopped, unlike the wirings around it. A discard that works hands the reader back from the
        // job's own completion callback, which is not the thread that paints. Firing a sidebar
        // button from there leaves the page it was meant to close still standing.
        troubleshootPresenter.setOpenRuns(() -> Platform.runLater(runs::fire));
        troubleshootPresenter.setOpenDashboard(dashboard::fire);
        // The launcher's own way out of a timeframe whose unfinished sift it cannot carry on. Set on
        // the presenter rather than passed to the pane, so the several places that redraw the
        // Dashboard need know nothing about it.
        runLauncherPresenter.setOpenRuns(runs::fire);
        runLauncherPresenter.setOpenReview(review::fire);
        runsPresenter.setOpenDashboard(dashboard::fire);
        dashboard.addEventHandler(ActionEvent.ACTION, _ -> readThenCount.run());
        settings.addEventHandler(ActionEvent.ACTION, _ -> readThenCount.run());
        review.addEventHandler(ActionEvent.ACTION, _ -> readThenCount.run());

        final var sidebar = new VBox(dashboard, runs, settings, review);
        sidebar.getStyleClass().add("sidebar");

        final var root = new BorderPane(content);
        root.setLeft(sidebar);
        root.getStyleClass().add("shell");
        final Scene scene = Stylesheet.applyTo(
                new Scene(root, Stylesheet.INITIAL_WIDTH, Stylesheet.INITIAL_HEIGHT));
        ScreenWarmUp.afterFirstFrame(root);
        checkTheConfiguredProviderOncePainted(settingsPresenter);
        // Counted once as the app opens, because nothing else does. Selecting the Dashboard above
        // raises no action event. Without this, a reader with unfinished sifts comes back to a
        // sidebar saying nothing until they happen to press a nav entry.
        AfterFirstFrame.run(readThenCount);
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
     * <p>A screen holding work nobody has saved asks before it is replaced. Refusing there leaves
     * the content area exactly as it was, and the caller puts the sidebar's mark back.
     *
     * @param content {@link VBox} the content area, holding exactly the screen on show
     * @param screen {@link String} the name of the screen being asked for
     * @param leaving {@link Leaving} what has to be answered before the screen on show is replaced
     * @param draw {@link Supplier} of {@link Node} builds it, called only if it is not up already
     * @return boolean false where the reader chose to stay where they were
     */
    private static boolean show(final VBox content, final String screen, final Leaving leaving,
                                final Supplier<Node> draw) {
        final Node current = content.getChildren().isEmpty() ? null : content.getChildren().getFirst();
        // A panel saying the screen would not open carries that screen's own id. A second press on
        // the same entry would be read as already being there. Pressing again is the only way out
        // of that panel, so it has to reach the build.
        if (current != null && screen.equals(current.getId())
                && !current.getStyleClass().contains(FAILED)) {
            return true;
        }
        if (leaving.losesWork().get().getAsBoolean()
                && !Dialogs.agreed(content, LeavingUnsaved.question())) {
            return false;
        }
        // Cleared before the draw and set again by it, so the guard belongs to the screen going up
        // rather than to the one coming down.
        leaving.losesWork().set(() -> false);
        final Node next = buildOrSayItFailed(screen, draw);
        next.setId(screen);
        content.getChildren().setAll(next);
        return true;
    }

    /**
     * What has to be answered before the screen on show is replaced.
     *
     * <p>Cleared as each screen is drawn, and set again by those that can lose work. A screen added
     * later therefore inherits no guard it never asked for.
     *
     * @param losesWork an {@link AtomicReference} to whether the screen up now holds unsaved work
     */
    private record Leaving(AtomicReference<BooleanSupplier> losesWork) {
    }

    /**
     * Builds a screen, answering with a panel saying so where building it threw.
     *
     * <p>Catches every runtime failure rather than a named list. What can throw here is whatever a
     * screen's construction reaches, which is most of the app, and a list would go stale on the
     * next screen added.
     *
     * <p>The failure is folded away rather than shown. Its frames name paths from the reader's own
     * machine, and revealing those is their press to make.
     *
     * @param screen {@link String} the screen being asked for, named in the log
     * @param draw {@link Supplier} of {@link Node} builds it
     * @return {@link Node} the screen, or a panel saying it would not open
     */
    private static Node buildOrSayItFailed(final String screen, final Supplier<Node> draw) {
        try {
            return draw.get();
        } catch (final RuntimeException e) {
            log.error("The {} screen could not be built", screen, e);
            // Headed by the screen that was asked for, so a reader who pressed Runs is not left
            // working out which press this answers.
            final var panel = headingPane(screen);
            panel.getStyleClass().add(FAILED);
            final TextArea message = SelectableText.prose(ScreenFailure.wouldNotOpenSentence());
            panel.getChildren().addAll(message, CopyableTrace.fold("screen-failure",
                    ScreenFailure.showDetailsLabel(), ScreenFailure.copyLabel(), ScreenFailure.copiedLabel(),
                    ScreenFailure.trace(e)));
            return panel;
        }
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
     * @param navigation {@link ScreenNavigation} how a screen it holds opens another
     * @return {@link Node} a pane ready to sit in the content area
     */
    private static Node dashboardPane(final VBox content, final FirstRunPresenter presenter,
                                      final SettingsPresenter settingsPresenter,
                                      final RunLauncherPresenter runLauncherPresenter,
                                      final @Nullable String said, final ScreenNavigation navigation) {
        if (presenter.unfinished()) {
            return filling(FirstRunCard.pane(settingsPresenter, presenter,
                    reported -> drawDashboard(content, presenter, settingsPresenter, runLauncherPresenter,
                            reported, navigation)));
        }
        final Node launcher = RunLauncherPane.pane(runLauncherPresenter, navigation);
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
     * move finishes, which can be minutes after the button was pressed. By then the sidebar can be
     * showing a different screen, and the sidebar and the content have to agree on which screen
     * this is. The report is dropped with it, which is the lesser loss of the two.
     *
     * @param content {@link VBox} the content area, holding exactly the screen on show
     * @param presenter {@link FirstRunPresenter} says which state the Dashboard is in
     * @param settingsPresenter {@link SettingsPresenter} supplies and saves the first-run fields
     * @param runLauncherPresenter {@link RunLauncherPresenter} supplies and drives the launcher
     * @param said what the save that led here had to report, or null where nothing did
     * @param navigation {@link ScreenNavigation} how a screen it holds opens another
     */
    private static void drawDashboard(final VBox content, final FirstRunPresenter presenter,
                                      final SettingsPresenter settingsPresenter,
                                      final RunLauncherPresenter runLauncherPresenter,
                                      final @Nullable String said, final ScreenNavigation navigation) {
        final Node current = content.getChildren().isEmpty() ? null : content.getChildren().getFirst();
        if (current != null && !DASHBOARD.equals(current.getId())) {
            return;
        }
        final Node pane = buildOrSayItFailed(DASHBOARD,
                () -> dashboardPane(content, presenter, settingsPresenter, runLauncherPresenter, said,
                        navigation));
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
        final TextField label = SelectableText.line(heading);
        label.getStyleClass().add("pane-heading");

        final var pane = new VBox(label);
        pane.getStyleClass().add("placeholder-pane");
        return pane;
    }

    /**
     * The count of unfinished runs, drawn beside the entry that leads to them.
     *
     * <p>Takes no room at zero, so a reader with nothing outstanding sees a plain sidebar.
     *
     * @return {@link Label} the badge
     */
    private static Label countBadge() {
        final var badge = new Label();
        badge.setId("nav-runs-count");
        badge.getStyleClass().add("nav-count");
        badge.setTooltip(new Tooltip(RUNS_COUNT_MEANS));
        SettingsRows.showWhileItSaysSomething(badge);
        return badge;
    }

    /**
     * The mark on the Dashboard entry, saying what is happening on a screen the reader has left.
     *
     * <p>The same shape as the Runs count, which is the sidebar's one established way of carrying
     * state. A dot rather than a number, since there is only ever one dashboard.
     *
     * @return {@link Label} the mark, empty until something is happening
     */
    private static Label markBadge() {
        final var badge = new Label();
        badge.setId("nav-dashboard-mark");
        badge.getStyleClass().add("nav-mark");
        SettingsRows.showWhileItSaysSomething(badge);
        return badge;
    }

    /**
     * Puts the Dashboard's own state onto its mark.
     *
     * <p>The tooltip carries the difference the two colours cannot. A dot on its own says something
     * is there, and a reader still has to be told whether it wants anything from them.
     *
     * @param badge {@link Label} the mark to fill in
     * @param mark {@link DashboardMark} what is happening on the dashboard
     */
    private static void drawMark(final Label badge, final DashboardMark mark) {
        badge.setText(mark == DashboardMark.NONE ? "" : "●");
        badge.getStyleClass().setAll("nav-mark",
                mark == DashboardMark.FINISHED ? "nav-mark-finished" : "nav-mark-running");
        badge.setTooltip(mark == DashboardMark.NONE ? null : new Tooltip(
                mark == DashboardMark.FINISHED ? DASHBOARD_FINISHED : DASHBOARD_RUNNING));
    }

    /**
     * Reads how many runs are unfinished, away from the thread that paints, then draws the number.
     *
     * <p>The read walks every run in the folder and opens every sidecar and shard of each. That is
     * long enough that a sidebar press has to stay responsive through it.
     *
     * @param presenter {@link RunsPresenter} does the reading
     * @param badge {@link Label} the number to fill in once it lands
     */
    private static void countInTheBackground(final RunsPresenter presenter, final Label badge) {
        Thread.ofVirtual().start(() -> {
            presenter.refresh();
            final String outstanding = countOf(presenter);
            Platform.runLater(() -> badge.setText(outstanding));
        });
    }

    /**
     * What the badge says about the reading the presenter already holds.
     *
     * <p>Empty at none, so a reader with nothing outstanding meets a plain sidebar. A badge reading
     * zero is a thing to read every time the app opens, saying nothing.
     *
     * @param presenter {@link RunsPresenter} holds the last reading
     * @return {@link String} the number, or empty
     */
    private static String countOf(final RunsPresenter presenter) {
        final int outstanding = presenter.unfinishedRuns();
        return outstanding == 0 ? "" : String.valueOf(outstanding);
    }

    /**
     * A sidebar entry carrying a count to the right of its name.
     *
     * @param group {@link ToggleGroup} the group every entry shares
     * @param id {@link String} the control's id
     * @param label {@link String} what the entry says
     * @param count {@link Label} the badge that sits after it
     * @return {@link ToggleButton} the entry
     */
    private static ToggleButton countedNavEntry(final ToggleGroup group, final String id,
                                                final String label, final Label count) {
        final var entry = navEntry(group, id, label);
        final var name = new Label(label);
        final var gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        final var inside = new HBox(name, gap, count);
        inside.setAlignment(Pos.CENTER_LEFT);
        inside.getStyleClass().add("nav-item-inside");
        // The graphic carries the name, so the button's own text stays empty.
        entry.setText("");
        entry.setGraphic(inside);
        entry.setMaxWidth(Double.MAX_VALUE);
        return entry;
    }

    private static ToggleButton navEntry(final ToggleGroup group, final String id, final String label) {
        final var entry = new ToggleButton(label);
        entry.setId(id);
        entry.setToggleGroup(group);
        entry.getStyleClass().add("nav-item");
        return entry;
    }
}
