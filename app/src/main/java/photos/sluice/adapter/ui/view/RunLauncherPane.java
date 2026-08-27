package photos.sluice.adapter.ui.view;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunLauncherView;
import photos.sluice.adapter.ui.RunLauncherView.Cost;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;
import photos.sluice.adapter.ui.RunProgressView;
import photos.sluice.adapter.ui.RunResultView;
import photos.sluice.adapter.ui.RunSetupPresenter;
import photos.sluice.adapter.ui.RunStage;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The dashboard's working state: what to do, what to do it to, and the button that starts it.
 *
 * <p>Every control is built once and filled in afterwards. A button's own press asks the presenter
 * and then fills the screen again. A fill that replaced a control would be destroying the one the
 * user is still pressing, so a fill writes onto controls and never replaces one.
 *
 * <p>The year rows are the exception, since how many there are is not known until the counts land.
 * They are replaced by {@code drawCounts} alone, which runs when the screen is built and when a
 * finished run has changed what is staged.
 *
 * <p>The counts come from walking two folder trees, which takes long enough on a full Inbox to be
 * seen. That read runs on a thread of its own once the window is painted, and the Inbox card says
 * it is counting until the answer arrives.
 */
final class RunLauncherPane {

    /**
     * How long a year takes to show or hide its months. Long enough to be seen as movement, short
     * enough that a second press is never waiting on the first.
     */
    private static final Duration FOLD_TRAVEL = Duration.millis(160);

    // The glyph a marked timeline row carries. The legend explaining it is written by the
    // presenter, which spells the same glyph, and RunLauncherPaneTest pins the two together.
    private static final String UNFINISHED_MARK = "*";

    private RunLauncherPane() {
    }

    /**
     * Builds the launcher, ready to sit in the shell's content area.
     *
     * @param presenter {@link RunLauncherPresenter} says which face is up, and holds the one that
     *     decides what the launcher shows
     * @return {@link Node} the launcher
     */
    static Node pane(final RunLauncherPresenter presenter) {
        final RunSetupPresenter setup = presenter.setup();
        final var heading = new Label("Dashboard");
        heading.getStyleClass().add("pane-heading");

        final var modeRow = new HBox();
        modeRow.getStyleClass().add("run-mode-row");
        final Label modeHint = SettingsRows.emptyHelpLine("run-mode-hint");
        modeHint.getStyleClass().add("run-mode-hint");

        final var inboxHeadline = new Label();
        inboxHeadline.setId("run-inbox-headline");
        inboxHeadline.getStyleClass().add("run-card-headline");
        final Label inboxDetail = SettingsRows.emptyHelpLine("run-inbox-detail");
        final var importPhotos = new Button();
        importPhotos.setId("run-import");
        importPhotos.getStyleClass().add("button-quiet");
        // Left-aligned in a row of its own. The start button is the one control on this screen that
        // sits to the right, and a second right-aligned button would read as its equal.
        final var importRow = new HBox(importPhotos);
        importRow.getStyleClass().add("run-import-row");
        final Label importHint = SettingsRows.emptyHelpLine("run-import-hint");
        final VBox inboxCard = SettingsRows.card("INBOX", null, inboxHeadline, inboxDetail, importRow,
                importHint);

        final var yearRows = new VBox();
        yearRows.getStyleClass().add("run-year-rows");
        final Label nothingStaged = SettingsRows.emptyHelpLine("run-nothing-staged");
        final Hyperlink openRuns = SettingsRows.inAppLink("run-scope-legend-link", presenter::showRuns);
        final SettingsRows.LinkedLine scopeLegend =
                SettingsRows.linkedHelpLine("run-scope-legend", openRuns);
        final HBox scopeLegendRow = SettingsRows.markedHelpLine(scopeLegend.flow(), UNFINISHED_MARK,
                "run-unfinished-mark", "run-legend-mark");
        final VBox sortedCard = SettingsRows.card("SORTED", null, yearRows, nothingStaged,
                scopeLegendRow);

        final var body = new VBox(inboxCard, sortedCard);
        body.getStyleClass().add("run-launcher-body");

        final var scopeLabel = new Label();
        scopeLabel.getStyleClass().add("settings-field-label");
        final var scopeField = new TextField();
        scopeField.setId("run-scope-field");
        scopeField.getStyleClass().add("run-scope-field");
        final var start = new Button();
        start.setId("run-start");
        start.getStyleClass().add("run-start");
        final var startRow = new HBox(start);
        startRow.getStyleClass().add("run-start-row");
        startRow.setAlignment(Pos.CENTER_RIGHT);

        final Label hint = SettingsRows.emptyHelpLine("run-scope-hint");
        final var refusal = new Label();
        refusal.setId("run-scope-refusal");
        SettingsRows.wrapping(refusal);
        // The class that draws a refusal at length rather than the one that draws it in a phrase.
        // What this says of a gapped month list runs to a short paragraph. The bold weight that
        // suits a single line under a field turns a paragraph into shouting.
        refusal.getStyleClass().add("settings-violation-detail");
        SettingsRows.showWhileItSaysSomething(refusal);

        final var figure = new Label();
        figure.setId("run-estimate-figure");
        figure.getStyleClass().add("run-estimate-figure");
        final Label disclaimer = SettingsRows.emptyHelpLine("run-estimate-disclaimer");
        final Label withoutHistory = SettingsRows.emptyHelpLine("run-estimate-without-history");
        final var estimate = new VBox(figure, disclaimer, withoutHistory);
        estimate.setId("run-estimate");
        estimate.getStyleClass().add("run-estimate");
        estimate.managedProperty().bind(estimate.visibleProperty());
        estimate.visibleProperty().bind(figure.textProperty().isNotEmpty());

        // The same box Settings puts a standing fact in, for the same reason. This one stays on the
        // page rather than fading, and it warns about nothing.
        final var freeHeadline = new Label();
        freeHeadline.setId("run-free-headline");
        SettingsRows.wrapping(freeHeadline);
        freeHeadline.getStyleClass().add("run-free-headline");
        final Label freeDetail = SettingsRows.emptyHelpLine("run-free-detail");
        final var freeBox = new VBox(freeHeadline, freeDetail);
        final Node free = SettingsRows.badgedCallout(freeBox);
        free.setId("run-free");
        free.managedProperty().bind(free.visibleProperty());
        free.visibleProperty().bind(freeHeadline.textProperty().isNotEmpty());

        final var message = new Label();
        message.setId("run-message");
        SettingsRows.wrapping(message);
        SettingsRows.showWhileItSaysSomething(message);

        final var action = new VBox(scopeLabel, scopeField, hint, refusal, estimate, free, message,
                startRow);
        action.getStyleClass().add("run-action");

        // No grow priority, and a floor of nothing. With the cards short the pane takes only their
        // height, so the scope field sits under them rather than across a gap. With more years than
        // fit, the pane is the one thing the column can shrink, so it scrolls and the field stays
        // where it is. Growing it instead would strand the field at the foot of every short page.
        final var scroll = SettingsRows.scrolling(body);
        scroll.setMinHeight(0);

        final var controls = new Controls(modeRow, modeHint, inboxHeadline, inboxDetail, importPhotos,
                importHint, yearRows, nothingStaged, scopeLegend, openRuns,
                scopeLabel, scopeField, start, hint, refusal, figure, disclaimer, withoutHistory,
                freeHeadline, freeDetail, message, scroll, new HashSet<>(), new HashMap<>());
        controls.buildModeRow(setup);
        scopeField.textProperty().addListener((_, _, typed) -> {
            setup.setScope(typed);
            controls.fillFrom(setup.view());
        });
        presenter.setRecount(() -> recount(setup, controls));

        final var launcher = new VBox(heading, modeRow, modeHint, scroll, action);
        launcher.setId("run-launcher");
        launcher.getStyleClass().add("run-launcher");

        // Each face needs the draw and the draw needs every face, so one of the two is handed over
        // after the other is built.
        final var redraw = new Redraw();
        final RunProgressPane.Mounted progress = RunProgressPane.mount(presenter, redraw);
        final RunResultPane.Mounted result = RunResultPane.mount(presenter, redraw);

        // No id of its own. The shell names whichever screen it puts in the content area, so one set
        // here would be overwritten. A test finding it would be finding the shell's name.
        final var dashboard = new StackPane(launcher, progress.node(), result.node());
        final Runnable draw = () -> show(presenter, setup, controls, launcher, progress, result);
        redraw.becomes(draw);
        start.setOnAction(_ -> onStart(presenter, draw));
        importPhotos.setOnAction(_ -> onImportPressed(presenter, importPhotos, draw));
        acceptDroppedFolders(launcher, presenter, draw);

        // Two routes in, and they differ by the thread they arrive on. A job reports its ending from
        // whatever thread it ran on, so that one hops. Progress arrives already marshalled by the
        // port, and hopping again would put a second draw behind every one it had folded together.
        presenter.setRepaint(() -> Platform.runLater(draw));
        presenter.setProgressRepaint(draw);
        controls.drawCounts(setup);
        draw.run();
        countInTheBackground(setup, controls);
        return dashboard;
    }

    /**
     * Shows whichever of the dashboard's three faces the presenter says is up, and fills it in.
     *
     * <p>Only the one being shown is filled. Writing onto a face nobody can see costs a rebuild of
     * its rows on every progress event, and a run reports one per file.
     *
     * @param presenter {@link RunLauncherPresenter} decides which face is up
     * @param setup {@link RunSetupPresenter} decides what the launcher shows
     * @param controls {@link Controls} the launcher's own controls
     * @param launcher {@link Node} the launcher
     * @param progress {@link RunProgressPane.Mounted} the progress area
     * @param result {@link RunResultPane.Mounted} the result card
     */
    private static void show(final RunLauncherPresenter presenter, final RunSetupPresenter setup,
                             final Controls controls, final Node launcher,
                             final RunProgressPane.Mounted progress,
                             final RunResultPane.Mounted result) {
        switch (presenter.stage()) {
            case RunStage.Setup _ -> {
                only(launcher, launcher, progress.node(), result.node());
                controls.fillFrom(setup.view());
            }
            case RunStage.Running(final RunProgressView showing) -> {
                only(progress.node(), launcher, progress.node(), result.node());
                progress.fill().accept(showing);
            }
            case final RunStage.Finished ended -> {
                only(result.node(), launcher, progress.node(), result.node());
                result.fill().accept(ended);
            }
        }
    }

    /**
     * Leaves one of the faces showing and takes the room back from the others.
     *
     * <p>Unmanaged as well as hidden. A stack sizes itself to its widest and tallest child whether
     * or not that child can be seen. A merely hidden face would still be setting the page's size.
     *
     * @param shown {@link Node} the face to show
     * @param faces {@link Node}[] every face, the shown one included
     */
    private static void only(final Node shown, final Node... faces) {
        for (final Node face : faces) {
            face.setVisible(face == shown);
            face.setManaged(face == shown);
        }
    }

    /**
     * Starts the work, asking first where the presenter says a question is owed.
     *
     * <p>The dialog blocks, so nothing else happens while it is open. Backing out of it leaves the
     * screen exactly as it was.
     *
     * <p>Drawn rather than filled afterwards, because a press that starts something takes the whole
     * page onto the progress area. A press the facade refuses leaves it on the launcher, and the
     * same draw puts the refusal on it.
     *
     * @param presenter {@link RunLauncherPresenter} decides everything this screen shows
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     */
    private static void onStart(final RunLauncherPresenter presenter, final Runnable redraw) {
        final RunSetupPresenter.Confirmation asked = presenter.setup().confirmationNeeded();
        if (asked != null && Dialogs.ask(asked.heading(), asked.question(),
                new Dialogs.Choice(asked.goAhead(), Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.LOUD),
                new Dialogs.Choice(asked.cancel(), Dialogs.Role.CANCEL, Dialogs.Emphasis.QUIET)).isEmpty()) {
            return;
        }
        presenter.press(presenter.setup().view().startAction());
        redraw.run();
    }

    /**
     * Takes a press on the button that brings photos in.
     *
     * <p>Folders only, since a native dialog picks one or the other and what people import is a
     * card, a phone folder or an export.
     *
     * @param presenter {@link RunLauncherPresenter} starts the import
     * @param owner {@link Node} the button the dialog opens over
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     */
    private static void onImportPressed(final RunLauncherPresenter presenter, final Node owner,
                                        final Runnable redraw) {
        final File chosen = new DirectoryChooser().showDialog(owner.getScene().getWindow());
        if (chosen != null) {
            askThenImport(presenter, List.of(chosen.toPath()), redraw);
        }
    }

    /**
     * Lets folders and files be dropped onto the launcher.
     *
     * <p>Onto the launcher rather than the window, so a drop cannot land on the progress area or on
     * a finished run's card.
     *
     * <p>What is dropped is accepted whatever it is, and refused afterwards by name where it cannot
     * be imported.
     *
     * @param launcher {@link Node} the face that takes the drop
     * @param presenter {@link RunLauncherPresenter} starts the import
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     */
    private static void acceptDroppedFolders(final Node launcher, final RunLauncherPresenter presenter,
                                             final Runnable redraw) {
        launcher.setOnDragOver(event -> {
            // Nothing this app started.
            if (event.getGestureSource() == null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        launcher.setOnDragEntered(event -> {
            if (event.getGestureSource() == null && event.getDragboard().hasFiles()) {
                launcher.getStyleClass().add("run-launcher-taking-a-drop");
            }
            event.consume();
        });
        launcher.setOnDragExited(event -> {
            launcher.getStyleClass().remove("run-launcher-taking-a-drop");
            event.consume();
        });
        launcher.setOnDragDropped(event -> {
            launcher.getStyleClass().remove("run-launcher-taking-a-drop");
            final boolean carriedFiles = event.getDragboard().hasFiles();
            final List<Path> dropped = carriedFiles ? pathsOf(event.getDragboard()) : List.of();
            // Answered and released before the question is put. The drag is still in flight until
            // this handler returns, and the application dragged from is waiting on it. A modal
            // opened from inside here would leave that window frozen behind ours until somebody
            // answered. The paths are read out first, because the dragboard does not outlive the
            // gesture.
            event.setDropCompleted(carriedFiles);
            event.consume();
            if (carriedFiles) {
                Platform.runLater(() -> askThenImport(presenter, dropped, redraw));
            }
        });
    }

    /**
     * What a drag is carrying, as paths.
     *
     * @param board {@link Dragboard}
     * @return a {@link List} of {@link Path}
     */
    private static List<Path> pathsOf(final Dragboard board) {
        return board.getFiles().stream().map(File::toPath).toList();
    }

    /**
     * Asks whether to copy or move, and starts the import unless the question is backed out of.
     *
     * <p>Copy is the loud answer. It is the one of the two that cannot lose anything, and the one
     * almost every import wants.
     *
     * @param presenter {@link RunLauncherPresenter} starts the import
     * @param sources a {@link List} of {@link Path} the folders and files chosen
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     */
    private static void askThenImport(final RunLauncherPresenter presenter, final List<Path> sources,
                                      final Runnable redraw) {
        final RunSetupPresenter.ImportQuestion asked = presenter.setup().importQuestion(sources);
        final var copy = new Dialogs.Choice(asked.copy(), Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.LOUD);
        final var move = new Dialogs.Choice(asked.move(), Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.QUIET);
        final Optional<Dialogs.Choice> taken = Dialogs.ask(asked.heading(), asked.question(), copy, move,
                new Dialogs.Choice(asked.cancel(), Dialogs.Role.CANCEL, Dialogs.Emphasis.QUIET));
        if (taken.isEmpty()) {
            return;
        }
        if (taken.get().equals(move)) {
            presenter.startImportMoving(sources);
        } else {
            presenter.startImportCopying(sources);
        }
        redraw.run();
    }

    /**
     * Reads the two folder trees away from the thread that paints, then fills the cards in.
     *
     * <p>Held back until the window has been painted, so the walk cannot delay the screen
     * appearing. A virtual thread rather than the common pool: the work is a long wait on a disk
     * rather than arithmetic, and nothing else should queue behind it.
     *
     * @param setup {@link RunSetupPresenter} does the reading
     * @param controls {@link Controls} the controls to fill in once it lands
     */
    private static void countInTheBackground(final RunSetupPresenter setup, final Controls controls) {
        AfterFirstFrame.run(() -> recount(setup, controls));
    }

    /**
     * Reads both folder trees again and redraws from what they now hold.
     *
     * <p>What a finished run changed is on disk, not in the presenter. Redrawing without reading
     * again would put the numbers from before the run back on the cards. It would also refuse a
     * sift over the year that run had just created.
     *
     * @param setup {@link RunSetupPresenter} does the reading
     * @param controls {@link Controls} the controls to fill in once it lands
     */
    private static void recount(final RunSetupPresenter setup, final Controls controls) {
        Thread.ofVirtual().start(() -> {
            setup.refreshCounts();
            Platform.runLater(() -> controls.drawCounts(setup));
        });
    }

    /**
     * Every control the screen fills in after a change.
     *
     * @param modeRow {@link HBox} the row of mode buttons
     * @param modeHint {@link Label} what the chosen mode does
     * @param inboxHeadline {@link Label} the Inbox card's first line
     * @param inboxDetail {@link Label} the Inbox card's second line
     * @param importPhotos {@link Button}
     * @param importHint {@link Label} the line naming the drop as the other way in
     * @param yearRows {@link VBox} the Sorted card's rows
     * @param nothingStaged {@link Label} what the Sorted card says with no rows to show
     * @param scopeLegend {@link SettingsRows.LinkedLine} what a mark on one of those rows means
     * @param openRuns {@link Hyperlink} the word inside it that opens the runs screen
     * @param scopeLabel {@link Label} the label above the scope field
     * @param scopeField {@link TextField} the scope field
     * @param start {@link Button} the start button
     * @param hint {@link Label} what the field accepts
     * @param refusal {@link Label} what is wrong with what was typed
     * @param figure {@link Label} the estimated cost
     * @param disclaimer {@link Label} what that figure is worth
     * @param withoutHistory {@link Label} the extra line where nothing backs the figure yet
     * @param freeHeadline {@link Label} the first line of the box saying this provider spends nothing
     * @param freeDetail {@link Label} why that box has no figure in it
     * @param message {@link Label} what the screen has to report
     * @param scroll {@link ScrollPane} the pane the cards sit in
     * @param unfolded a {@link Set} of {@link VBox} the month boxes now showing
     * @param folding a {@link Map} of {@link VBox} to {@link Timeline} the folds still running
     */
    private record Controls(HBox modeRow, Label modeHint, Label inboxHeadline, Label inboxDetail,
                            Button importPhotos, Label importHint, VBox yearRows, Label nothingStaged,
                            SettingsRows.LinkedLine scopeLegend, Hyperlink openRuns,
                            Label scopeLabel, TextField scopeField,
                            Button start, Label hint, Label refusal, Label figure, Label disclaimer,
                            Label withoutHistory, Label freeHeadline, Label freeDetail, Label message,
                            ScrollPane scroll, Set<VBox> unfolded, Map<VBox, Timeline> folding) {

        /**
         * Builds the five mode buttons, once.
         *
         * <p>The row never changes, so it is built here and only re-selected afterwards. Building it
         * again on each fill would replace the button whose press caused the fill.
         *
         * @param setup {@link RunSetupPresenter} takes the press
         */
        private void buildModeRow(final RunSetupPresenter setup) {
            final var group = new ToggleGroup();
            final List<Node> buttons = new ArrayList<>();
            for (final ModeChoice mode : setup.view().modes()) {
                final var button = new ToggleButton(mode.label());
                button.setId(mode.id());
                button.setToggleGroup(group);
                button.setSelected(mode.chosen());
                button.getStyleClass().add("run-mode-button");
                button.setOnAction(_ -> {
                    setup.setMode(mode.mode());
                    this.fillFrom(setup.view());
                });
                buttons.add(button);
            }
            this.modeRow.getChildren().setAll(buttons);
        }

        /**
         * Marks each mode button selected or not and live or not, leaving the buttons themselves
         * alone.
         *
         * @param modes a {@link List} of {@link ModeChoice} the buttons as the presenter has them
         */
        private void drawModes(final List<ModeChoice> modes) {
            modes.forEach(mode -> {
                if (this.modeRow.lookup("#" + mode.id()) instanceof final ToggleButton button) {
                    button.setSelected(mode.chosen());
                    button.setDisable(!mode.pressable());
                }
            });
        }

        /**
         * Builds the year rows from the counts as they stand, then fills the whole screen in.
         *
         * <p>The one place rows are replaced. Called when the counts first land and when a run has
         * finished changing them, and at neither moment is a row being pressed.
         *
         * @param setup {@link RunSetupPresenter} decides everything this screen shows
         */
        private void drawCounts(final RunSetupPresenter setup) {
            final RunLauncherView view = setup.view();
            final var group = new ToggleGroup();
            // The boxes these hold are about to be discarded, along with any fold still running
            // over one. Left in, they are held for the life of the screen and never asked about
            // again.
            this.folding.values().forEach(Timeline::stop);
            this.folding.clear();
            this.unfolded.clear();
            final List<Node> rows = new ArrayList<>();
            for (final YearChoice year : view.years()) {
                rows.add(this.yearRow(year, group, setup));
            }
            this.yearRows.getChildren().setAll(rows);
            this.fillFrom(view);
        }

        /**
         * Puts everything the presenter says onto the controls that already exist.
         *
         * <p>This is also what keeps a toggle from being clicked back to nothing. A press on an
         * already-selected mode tells the presenter the same thing it already held, and the fill
         * that follows puts the selection back. A press on the already-chosen year folds its months
         * away and leaves the year itself chosen, so the fill puts that selection back too. Holding
         * the {@link ToggleGroup} itself would do the same job for modes and the wrong job for
         * years. No year chosen is a state the presenter draws on purpose.
         *
         * @param view {@link RunLauncherView} what the screen shows now
         */
        private void fillFrom(final RunLauncherView view) {
            this.drawModes(view.modes());
            this.modeHint.setText(view.modeHint());
            this.inboxHeadline.setText(view.inbox().headline());
            this.inboxDetail.setText(SettingsRows.orNothing(view.inbox().detail()));
            this.importPhotos.setText(view.inbox().importLabel());
            this.importHint.setText(view.inbox().importHint());
            this.importPhotos.setDisable(!view.inbox().canImport());
            this.selectYear(view.years(), view.scopeNamesTheRun());
            this.scopeField.setDisable(!view.scopeNamesTheRun());
            this.nothingStaged.setText(SettingsRows.orNothing(view.nothingStaged()));
            this.scopeLegend.before().setText(SettingsRows.orNothing(view.scopeLegend()));
            this.openRuns.setText(SettingsRows.orNothing(view.scopeLegendWayThere()));
            this.scopeLegend.after().setText(SettingsRows.orNothing(view.scopeLegendAfter()));
            this.scopeLabel.setText(view.scopeLabel());
            // Only when it differs. Setting it fires the listener that got here, and an unguarded
            // write would go round again. It also moves the caret, which a reader mid-word notices.
            if (!this.scopeField.getText().equals(view.scopeText())) {
                this.scopeField.setText(view.scopeText());
            }
            this.hint.setText(view.scopeHint());
            this.refusal.setText(SettingsRows.orNothing(view.scopeRefusal()));
            this.drawCost(view.cost());
            this.start.setText(view.startLabel());
            this.start.setDisable(!view.canStart());
            this.drawMessage(view.message());
        }

        /**
         * Marks whichever year row the scope now names, leaving the rows themselves alone.
         *
         * <p>Disabled where a press on them would scope nothing. A row that keeps its full colour
         * and its hand cursor while ignoring the click reads as broken. What being disabled looks
         * like is the stylesheet's to say, and it draws these the way it draws the other two
         * unpressable controls on this screen.
         *
         * @param years a {@link List} of {@link YearChoice} the rows and which of them is chosen
         * @param pressable boolean whether a press on one of them scopes the run
         */
        private void selectYear(final List<YearChoice> years, final boolean pressable) {
            years.forEach(year -> {
                if (this.yearRows.lookup("#" + year.id()) instanceof final ToggleButton row) {
                    row.setSelected(year.chosen());
                    row.setDisable(!pressable);
                }
                year.months().forEach(month -> {
                    if (this.yearRows.lookup("#" + month.id()) instanceof final ToggleButton row) {
                        row.setSelected(month.chosen());
                        row.setDisable(!pressable);
                    }
                });
            });
            this.fold(years);
        }

        /**
         * One year's row: the year, and what is staged under it.
         *
         * @param year {@link YearChoice} the row to draw
         * @param group {@link ToggleGroup} the group every row belongs to
         * @param setup {@link RunSetupPresenter} takes the click
         * @return {@link Node} the row
         */
        private Node yearRow(final YearChoice year, final ToggleGroup group,
                             final RunSetupPresenter setup) {
            // The same two steps a mode button takes: tell the presenter, then draw what it says.
            // The scope field is one of the things a fill writes, so a click and a keystroke reach
            // the screen by the same route.
            final ToggleButton row = this.scopeRow(year.id(), "run-year-row",
                    rowInside("run-year-label", year.label(), year.counts(), null,
                            unfinishedMark(year.unfinishedSift())),
                    year.chosen(), () -> setup.pressYear(year.year()), setup);
            row.setToggleGroup(group);
            if (year.months().isEmpty()) {
                return row;
            }
            // Every month built here, whichever year is chosen, and shown or hidden by a fill. Built
            // on selection instead, a year's own press would replace the row it came from.
            final List<Node> under = new ArrayList<>();
            year.months().forEach(month -> under.add(this.monthRow(year, month, setup)));
            final var months = new VBox(under.toArray(new Node[0]));
            months.setId(monthsId(year.year()));
            months.getStyleClass().add("run-month-rows");
            croppable(months);
            // Built in the state it belongs in rather than closed and then opened. A recount
            // rebuilds these rows, and a year the user has open would otherwise shut and reopen
            // itself each time one lands.
            settle(months, year.monthsShown());
            if (year.monthsShown()) {
                this.unfolded.add(months);
            }
            final var stack = new VBox(row, months);
            stack.getStyleClass().add("run-year-stack");
            return stack;
        }

        /**
         * Stops a year's months drawing outside the height they are given.
         *
         * <p>Height is what the fold moves, so the rows have to be croppable. Unclipped, they draw
         * at full size however little height the box is given.
         *
         * @param months {@link VBox} a year's month rows
         */
        private static void croppable(final VBox months) {
            months.setMinHeight(0);
            final var crop = new Rectangle();
            crop.widthProperty().bind(months.widthProperty());
            crop.heightProperty().bind(months.heightProperty());
            months.setClip(crop);
        }

        /**
         * Puts a year's months straight into the state named, with no travel.
         *
         * <p>Open means no ceiling of its own rather than a measured one. A measurement taken now
         * would still be in force after the window is resized or the rows rewrap.
         *
         * <p>Hidden as well as flat, because a box of no height still holds focusable rows.
         * Traversal passes over what cannot be seen, and steps through what is merely too small to
         * show anything.
         *
         * @param months {@link VBox} a year's month rows
         * @param open boolean the state to take
         */
        private static void settle(final VBox months, final boolean open) {
            months.setMaxHeight(open ? Region.USE_COMPUTED_SIZE : 0);
            months.setOpacity(open ? 1 : 0);
            months.setVisible(open);
        }

        /**
         * Opens or closes a year's months, travelling rather than arriving.
         *
         * <p>An instant change reads as the rows below jumping, with nothing saying the months came
         * from the year above them. Moving the height says where they came from.
         *
         * <p>Whether a year is open is held here rather than read off the box, because a box caught
         * mid-travel is neither.
         *
         * <p>One timeline for the whole fill rather than one per year. Choosing a different year
         * closes one box and opens another. Two timelines would each drive the pane's own position,
         * each from a page height counting only its own half of the change.
         *
         * <p>Everything below reads geometry: each box's own width and height, the page's height,
         * and where the box sits in it. A press arrives on the application thread between pulses,
         * so the layout those readings want can still be pending. Forcing it first is what makes
         * the travel land where the rows actually end up. Left to chance, the pane travels to a
         * position worked out from the page as it stood before the rows it is about to move. A
         * year opened below the fold then never comes into view at all.
         *
         * <p>Free where nothing is pending: a clean tree lays out in no time, and this runs on
         * every fill.
         *
         * @param years a {@link List} of {@link YearChoice} the rows and which show their months
         */
        private void fold(final List<YearChoice> years) {
            if (this.scroll.getContent() instanceof final Parent laidOut) {
                laidOut.applyCss();
                laidOut.layout();
            }
            final List<Turn> turns = years.stream().map(this::turnFor).filter(Objects::nonNull).toList();
            if (turns.isEmpty()) {
                return;
            }
            final List<KeyValue> frames = new ArrayList<>();
            turns.forEach(turn -> {
                frames.add(new KeyValue(turn.months().maxHeightProperty(), turn.open(),
                        Interpolator.EASE_BOTH));
                frames.add(new KeyValue(turn.months().opacityProperty(), turn.shown() ? 1 : 0,
                        Interpolator.EASE_BOTH));
            });
            final KeyValue following = this.following(turns);
            if (following != null) {
                frames.add(following);
            }
            final var travel = new Timeline(new KeyFrame(FOLD_TRAVEL, frames.toArray(new KeyValue[0])));
            travel.setOnFinished(_ -> {
                turns.forEach(turn -> {
                    this.folding.remove(turn.months());
                    settle(turn.months(), turn.shown());
                });
                Platform.runLater(() -> this.arrive(turns));
            });
            turns.forEach(turn -> this.folding.put(turn.months(), travel));
            travel.play();
        }

        /**
         * What one year's months are about to do, or null where they are already doing it.
         *
         * <p>A box nobody can watch takes its end state here and joins no timeline. That is the
         * screen being built. It is also how a render captures a year already open, rather than one
         * caught at the first frame of opening.
         *
         * @param year {@link YearChoice} the row and whether it shows its months
         * @return {@link Turn} the movement to make, or null where there is none to make
         */
        private @Nullable Turn turnFor(final YearChoice year) {
            if (!(this.yearRows.lookup("#" + monthsId(year.year())) instanceof final VBox months)
                    || this.unfolded.contains(months) == year.monthsShown()) {
                return null;
            }
            this.turn(months, year.monthsShown());
            if (!onScreen(months)) {
                settle(months, year.monthsShown());
                return null;
            }
            // Measured with the stylesheet on it. The padding, spacing and border of these rows are
            // all in it, so an unstyled measurement is short and the fold crops the last row to it.
            months.applyCss();
            months.setVisible(true);
            final double open = year.monthsShown()
                    ? months.prefHeight(months.getWidth() > 0 ? months.getWidth() : -1)
                    : 0;
            return new Turn(months, year.monthsShown(), open, open - months.getHeight());
        }

        /**
         * Records that a year is opening or closing, and stops any fold already under way on it.
         *
         * <p>Two timelines on one property fight every frame, and the loser's own end state lands
         * last.
         *
         * @param months {@link VBox} a year's month rows
         * @param shown boolean whether they are now to be shown
         */
        private void turn(final VBox months, final boolean shown) {
            if (shown) {
                this.unfolded.add(months);
            } else {
                this.unfolded.remove(months);
            }
            final Timeline running = this.folding.remove(months);
            if (running != null) {
                running.stop();
            }
        }

        /**
         * Whether a box sits somewhere a reader could watch it move.
         *
         * @param months {@link VBox} a year's month rows
         * @return boolean true only where the box is on a window that is showing
         */
        private static boolean onScreen(final VBox months) {
            return months.getScene() != null
                    && months.getScene().getWindow() != null
                    && months.getScene().getWindow().isShowing();
        }

        /**
         * How the pane moves while the months fold, so the page does not slide under the reader.
         *
         * <p>A pane holds its position as a fraction of what it can scroll. Left alone, a taller
         * page therefore moves what is on screen. This holds the view where it was, and moves by
         * the least that shows a year's months where they would land past the bottom.
         *
         * <p>Worked out before the fold starts, from the height the rows will end at. It rides in
         * the same timeline, so the page settles in one movement.
         *
         * @param turns a {@link List} of {@link Turn} everything this fill moves, in row order
         * @return {@link KeyValue} where the pane should end, or null where it cannot scroll at all
         */
        private @Nullable KeyValue following(final List<Turn> turns) {
            final double viewport = this.scroll.getViewportBounds().getHeight();
            final double before = this.scroll.getContent().getLayoutBounds().getHeight() - viewport;
            final double after = before + turns.stream().mapToDouble(Turn::growth).sum();
            final double top = before > 0 ? this.scroll.getVvalue() / this.scroll.getVmax() * before : 0;
            if (after <= 0) {
                return before > 0 ? new KeyValue(this.scroll.vvalueProperty(), 0, Interpolator.EASE_BOTH) : null;
            }
            final double wanted = Math.max(top, this.revealing(turns, viewport, top));
            return new KeyValue(this.scroll.vvalueProperty(),
                    Math.clamp(wanted, 0, after) / after * this.scroll.getVmax(), Interpolator.EASE_BOTH);
        }

        /**
         * Puts the pane where it was travelling to, now that the rows hold their real heights.
         *
         * <p>{@link #following} works its target out before anything has moved. So it stands a
         * measured preferred height in for the computed one each box settles at. A pane positions
         * itself as a fraction of the page. Whatever those two heights differ by therefore comes
         * off the destination, and the year that was opened finishes short of the bottom. By here
         * the predicted page exists, so the same arithmetic over it predicts nothing.
         *
         * <p>Deferred a pulse rather than run as the travel ends, and that is what makes it work at
         * all. {@link #settle} lifts a box's ceiling, and the box takes its real height on the
         * layout pass after. Reading here without waiting for that pass gives a box of no height
         * and a viewport that has not been sized, which is the same guess this exists to replace.
         *
         * <p>Reads the pane's own position rather than the target it was given: a fold this one
         * interrupted leaves the pane wherever it got to. A box no longer open by the time this
         * runs belongs to a fold that has since been replaced, so the fold that replaced it owns
         * the destination.
         *
         * @param turns a {@link List} of {@link Turn} everything this fill moved, in row order
         */
        private void arrive(final List<Turn> turns) {
            final Optional<Turn> opened = turns.stream().filter(Turn::shown).findFirst();
            if (opened.isEmpty() || !(this.scroll.getContent() instanceof final Parent laidOut)
                    || !this.unfolded.contains(opened.get().months())) {
                return;
            }
            laidOut.applyCss();
            laidOut.layout();
            final double viewport = this.scroll.getViewportBounds().getHeight();
            final double scrollable = laidOut.getLayoutBounds().getHeight() - viewport;
            if (scrollable <= 0) {
                return;
            }
            final VBox months = opened.get().months();
            final double foot = laidOut.sceneToLocal(months.localToScene(months.getLayoutBounds())).getMaxY();
            final double top = this.scroll.getVvalue() / this.scroll.getVmax() * scrollable;
            this.scroll.setVvalue(Math.clamp(Math.max(top, foot - viewport), 0, scrollable)
                    / scrollable * this.scroll.getVmax());
        }

        /**
         * Where the pane has to sit for a year's opening months to end up in view.
         *
         * <p>Only a year being opened asks for this. One closing is either in front of the reader
         * already or one they never touched, and travelling toward either shows them nothing.
         *
         * <p>The box's own foot moves by its growth and by the growth of every box above it. That
         * is why this is worked out over the whole fill rather than one box at a time.
         *
         * @param turns a {@link List} of {@link Turn} everything this fill moves, in row order
         * @param viewport double how much of the page is on screen
         * @param top double where the pane sits now, in the page's own coordinates
         * @return double where it should sit, or {@code top} where nothing is opening
         */
        private double revealing(final List<Turn> turns, final double viewport, final double top) {
            final Optional<Turn> opened = turns.stream().filter(Turn::shown).findFirst();
            if (opened.isEmpty()) {
                return top;
            }
            final Turn turn = opened.get();
            final double above = turns.subList(0, turns.indexOf(turn)).stream()
                    .mapToDouble(Turn::growth)
                    .sum();
            final double foot = this.scroll.getContent()
                    .sceneToLocal(turn.months().localToScene(turn.months().getLayoutBounds()))
                    .getMaxY() + turn.growth() + above;
            return foot - viewport;
        }

        /**
         * The id of the box holding one year's months.
         *
         * @param year int the year they belong to
         * @return {@link String} the id
         */
        private static String monthsId(final int year) {
            return "run-months-" + year;
        }

        /**
         * One month's row, under the year it belongs to.
         *
         * <p>In no toggle group, because a group holds one of its members selected and any number of
         * months can be. Which months are marked comes from the scope alone.
         *
         * <p>The box in front says these add up, where the years above switch. It is drawn from the
         * row's own selected state rather than being a control of its own. That keeps the whole row
         * one target, with no second thing to hit.
         *
         * @param year {@link YearChoice} the year it sits under
         * @param month {@link MonthChoice} the month to draw
         * @param setup {@link RunSetupPresenter} takes the click
         * @return {@link Node} the row
         */
        private Node monthRow(final YearChoice year, final MonthChoice month,
                              final RunSetupPresenter setup) {
            final var box = new Region();
            box.getStyleClass().add("run-month-box");
            final var inside = rowInside("run-month-label", month.label(), month.counts(), box,
                    unfinishedMark(month.unfinishedSift()));
            return this.scopeRow(month.id(), "run-month-row", inside, month.chosen(),
                    () -> setup.pressMonth(year.year(), month.month()), setup);
        }

        /**
         * The mark a timeline row carries when an unfinished sift already covers it.
         *
         * <p>The row is still pressable. What the mark changes is what the button under the field
         * then offers, and the legend under the rows says so.
         *
         * @param unfinished boolean whether a sift of this row has not finished
         * @return {@link Node} the mark, or null where the row carries none
         */
        private static @Nullable Node unfinishedMark(final boolean unfinished) {
            if (!unfinished) {
                return null;
            }
            final var mark = new Label(UNFINISHED_MARK);
            mark.getStyleClass().add("run-unfinished-mark");
            return mark;
        }

        /**
         * What sits inside a scope row: the name, and what it holds.
         *
         * @param labelClass {@link String} the style class for the name
         * @param name {@link String} the year or month it stands for
         * @param counts {@link String} what that holds
         * @param leading {@link Node} drawn ahead of everything, or null where the row has none
         * @param mark {@link Node} drawn at the end, or null where the row carries none
         * @return {@link HBox} the row's contents
         */
        private static HBox rowInside(final String labelClass, final String name, final String counts,
                                      final @Nullable Node leading, final @Nullable Node mark) {
            final var label = new Label(name);
            label.getStyleClass().add(labelClass);
            final var held = new Label(counts);
            held.getStyleClass().add("run-year-counts");
            final var inside = new HBox();
            if (leading != null) {
                inside.getChildren().add(leading);
            }
            inside.getChildren().addAll(label, held);
            if (mark != null) {
                inside.getChildren().add(mark);
            }
            inside.setAlignment(Pos.CENTER_LEFT);
            inside.getStyleClass().add("run-year-inside");
            return inside;
        }

        /**
         * A row that scopes the run when pressed: a name on the left, what it holds on the right.
         *
         * @param id {@link String} the control's id
         * @param rowClass {@link String} the style class carrying its ground and its selected bar
         * @param inside {@link HBox} what the row shows
         * @param chosen boolean whether the scope names it
         * @param press {@link Runnable} what to tell the presenter
         * @param setup {@link RunSetupPresenter} asked again once it has been told
         * @return {@link ToggleButton} the row
         */
        private ToggleButton scopeRow(final String id, final String rowClass, final HBox inside,
                                      final boolean chosen, final Runnable press,
                                      final RunSetupPresenter setup) {
            final var row = new ToggleButton();
            row.setId(id);
            row.setGraphic(inside);
            row.setSelected(chosen);
            row.getStyleClass().add(rowClass);
            row.setOnAction(_ -> {
                press.run();
                this.fillFrom(setup.view());
            });
            return row;
        }

        /**
         * Fills in whichever way this run has of saying what it costs, and empties the other.
         *
         * <p>Both are written on every fill. A mode change that left the last one standing would
         * put a figure beside a box saying there is no figure.
         *
         * @param cost {@link Cost} what to say about money, or null where this mode never spends
         */
        private void drawCost(final @Nullable Cost cost) {
            final Cost.Estimate figures = cost instanceof final Cost.Estimate estimate ? estimate : null;
            final Cost.Free free = cost instanceof final Cost.Free spendsNothing ? spendsNothing : null;
            this.figure.setText(figures == null ? "" : figures.figure());
            this.disclaimer.setText(figures == null ? "" : figures.disclaimer());
            this.withoutHistory.setText(figures == null ? "" : SettingsRows.orNothing(figures.withoutHistory()));
            this.freeHeadline.setText(free == null ? "" : free.headline());
            this.freeDetail.setText(free == null ? "" : free.detail());
        }

        /**
         * Fills in the line the screen reports on, in the colour its own kind earns.
         *
         * @param said {@link RunLauncherView.Message} what to report, or null for nothing
         */
        private void drawMessage(final RunLauncherView.@Nullable Message said) {
            this.message.setText(said == null ? "" : said.text());
            this.message.getStyleClass().setAll("run-message",
                    said != null && said.refused() ? "settings-violation" : "settings-confirmation");
        }

    }

    /**
     * The redraw the faces are handed before the thing that does it exists.
     *
     * <p>Does nothing until it is told what it is. That state is never reachable from a press: the
     * faces are built and told within the same method, before either is on a window.
     */
    private static final class Redraw implements Runnable {

        private Runnable draw = () -> {};

        /**
         * Says what this redraw actually does.
         *
         * @param draw {@link Runnable} the real thing
         */
        private void becomes(final Runnable draw) {
            this.draw = draw;
        }

        @Override
        public void run() {
            this.draw.run();
        }
    }

    /**
     * One year's months on their way open or closed.
     *
     * @param months {@link VBox} the rows being moved
     * @param shown boolean where they are going
     * @param open double the height they end at, zero when closing
     * @param growth double how much taller the page gets because of them, negative when closing
     */
    private record Turn(VBox months, boolean shown, double open, double growth) {
    }
}
