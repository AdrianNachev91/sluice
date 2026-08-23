package photos.sluice.adapter.ui.view;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunLauncherView;
import photos.sluice.adapter.ui.RunLauncherView.ModeChoice;
import photos.sluice.adapter.ui.RunLauncherView.MonthChoice;
import photos.sluice.adapter.ui.RunLauncherView.YearChoice;

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

    private RunLauncherPane() {
    }

    /**
     * Builds the launcher, ready to sit in the shell's content area.
     *
     * @param presenter {@link RunLauncherPresenter} decides everything this screen shows
     * @return {@link Node} the launcher
     */
    static Node pane(final RunLauncherPresenter presenter) {
        final var heading = new Label("Dashboard");
        heading.getStyleClass().add("pane-heading");

        final var modeRow = new HBox();
        modeRow.getStyleClass().add("run-mode-row");
        final Label modeHint = helpLine("run-mode-hint");
        modeHint.getStyleClass().add("run-mode-hint");

        final var inboxHeadline = new Label();
        inboxHeadline.setId("run-inbox-headline");
        inboxHeadline.getStyleClass().add("run-card-headline");
        final Label inboxDetail = helpLine("run-inbox-detail");
        final VBox inboxCard = SettingsRows.card("INBOX", null, inboxHeadline, inboxDetail);

        final var yearRows = new VBox();
        yearRows.getStyleClass().add("run-year-rows");
        final Label nothingStaged = helpLine("run-nothing-staged");
        final VBox sortedCard = SettingsRows.card("SORTED", null, yearRows, nothingStaged);

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

        final Label hint = helpLine("run-scope-hint");
        final var refusal = new Label();
        refusal.setId("run-scope-refusal");
        wrapping(refusal);
        // The class that draws a refusal at length rather than the one that draws it in a phrase.
        // What this says of a gapped month list runs to a short paragraph. The bold weight that
        // suits a single line under a field turns a paragraph into shouting.
        refusal.getStyleClass().add("settings-violation-detail");
        showWhileItSaysSomething(refusal);

        final var figure = new Label();
        figure.setId("run-estimate-figure");
        figure.getStyleClass().add("run-estimate-figure");
        final Label disclaimer = helpLine("run-estimate-disclaimer");
        final Label withoutHistory = helpLine("run-estimate-without-history");
        final var estimate = new VBox(figure, disclaimer, withoutHistory);
        estimate.setId("run-estimate");
        estimate.getStyleClass().add("run-estimate");
        estimate.managedProperty().bind(estimate.visibleProperty());
        estimate.visibleProperty().bind(figure.textProperty().isNotEmpty());

        final var message = new Label();
        message.setId("run-message");
        wrapping(message);
        showWhileItSaysSomething(message);

        final var action = new VBox(scopeLabel, scopeField, hint, refusal, estimate, message, startRow);
        action.getStyleClass().add("run-action");

        // No grow priority, and a floor of nothing. With the cards short the pane takes only their
        // height, so the scope field sits under them rather than across a gap. With more years than
        // fit, the pane is the one thing the column can shrink, so it scrolls and the field stays
        // where it is. Growing it instead would strand the field at the foot of every short page.
        final var scroll = SettingsRows.scrolling(body);
        scroll.setMinHeight(0);

        final var controls = new Controls(modeRow, modeHint, inboxHeadline, inboxDetail, yearRows, nothingStaged,
                scopeLabel, scopeField, start, hint, refusal, figure, disclaimer, withoutHistory, message,
                scroll, new HashSet<>(), new HashMap<>());
        controls.buildModeRow(presenter);
        scopeField.textProperty().addListener((_, _, typed) -> {
            presenter.setScope(typed);
            controls.fillFrom(presenter.view());
        });
        start.setOnAction(_ -> onStart(presenter, controls));
        // Whichever launcher is on screen when a run ends is the one that draws it, and that is not
        // always the one that started it.
        presenter.setRepaint(() -> Platform.runLater(() -> controls.fillFrom(presenter.view())));
        presenter.setRecount(() -> recount(presenter, controls));
        controls.drawCounts(presenter);

        // No id of its own. The shell names whichever screen it puts in the content area, so one set
        // here would be overwritten. A test finding it would be finding the shell's name.
        final var page = new VBox(heading, modeRow, modeHint, scroll, action);
        page.getStyleClass().add("run-launcher");
        countInTheBackground(presenter, controls);
        return page;
    }

    /**
     * Starts the work, asking first where the presenter says a question is owed.
     *
     * <p>The dialog blocks, so nothing else happens while it is open. Backing out of it leaves the
     * screen exactly as it was.
     *
     * @param presenter {@link RunLauncherPresenter} decides everything this screen shows
     * @param controls {@link Controls} the controls to fill in afterwards
     */
    private static void onStart(final RunLauncherPresenter presenter, final Controls controls) {
        final RunLauncherPresenter.Confirmation asked = presenter.confirmationNeeded();
        if (asked != null && Dialogs.ask(asked.heading(), asked.question(),
                new Dialogs.Choice(asked.goAhead(), Dialogs.Role.GO_AHEAD, Dialogs.Emphasis.LOUD),
                new Dialogs.Choice(asked.cancel(), Dialogs.Role.CANCEL, Dialogs.Emphasis.QUIET)).isEmpty()) {
            return;
        }
        presenter.start();
        controls.fillFrom(presenter.view());
    }

    /**
     * Reads the two folder trees away from the thread that paints, then fills the cards in.
     *
     * <p>Held back until the window has been painted, so the walk cannot delay the screen
     * appearing. A virtual thread rather than the common pool: the work is a long wait on a disk
     * rather than arithmetic, and nothing else should queue behind it.
     *
     * @param presenter {@link RunLauncherPresenter} does the reading
     * @param controls {@link Controls} the controls to fill in once it lands
     */
    private static void countInTheBackground(final RunLauncherPresenter presenter, final Controls controls) {
        AfterFirstFrame.run(() -> recount(presenter, controls));
    }

    /**
     * Reads both folder trees again and redraws from what they now hold.
     *
     * <p>What a finished run changed is on disk, not in the presenter. Redrawing without reading
     * again would put the numbers from before the run back on the cards. It would also refuse a
     * sift over the year that run had just created.
     *
     * @param presenter {@link RunLauncherPresenter} does the reading
     * @param controls {@link Controls} the controls to fill in once it lands
     */
    private static void recount(final RunLauncherPresenter presenter, final Controls controls) {
        Thread.ofVirtual().start(() -> {
            presenter.refreshCounts();
            Platform.runLater(() -> controls.drawCounts(presenter));
        });
    }

    /**
     * A muted line that takes no room at all while it has nothing to say.
     *
     * @param id {@link String} the control's id
     * @return {@link Label} the line
     */
    private static Label helpLine(final String id) {
        final var line = new Label();
        line.setId(id);
        wrapping(line);
        line.getStyleClass().add("settings-help");
        showWhileItSaysSomething(line);
        return line;
    }

    /**
     * Makes a label wrap, and hold the height its wrapping needs.
     *
     * <p>Wrapping alone is not enough. A wrapped label's minimum height is one line. A column short
     * of room shrinks it to that, and the sentence comes out on one line with an ellipsis rather
     * than wrapped. Pinning the minimum to the preferred height moves the shrinking onto the
     * scrolling pane above, the one control here built to give room up.
     *
     * @param line {@link Label} the label to wrap
     */
    private static void wrapping(final Label line) {
        line.setWrapText(true);
        line.setMinHeight(Region.USE_PREF_SIZE);
    }

    /**
     * Has a label take up room only while it carries text.
     *
     * <p>A screen reserving a row for every line it might one day show would carry those gaps on
     * every screen that has nothing to put in them.
     *
     * @param line {@link Label} the label to bind
     */
    private static void showWhileItSaysSomething(final Label line) {
        line.managedProperty().bind(line.visibleProperty());
        line.visibleProperty().bind(line.textProperty().isNotEmpty());
    }

    /**
     * Text for a label, where nothing to say is an empty string rather than a missing one.
     *
     * @param said what the presenter had, or null where it had nothing
     * @return {@link String} what to put in the label
     */
    private static String orNothing(final @Nullable String said) {
        return said == null ? "" : said;
    }

    /**
     * Every control the screen fills in after a change.
     *
     * @param modeRow {@link HBox} the row of mode buttons
     * @param modeHint {@link Label} what the chosen mode does
     * @param inboxHeadline {@link Label} the Inbox card's first line
     * @param inboxDetail {@link Label} the Inbox card's second line
     * @param yearRows {@link VBox} the Sorted card's rows
     * @param nothingStaged {@link Label} what the Sorted card says with no rows to show
     * @param scopeLabel {@link Label} the label above the scope field
     * @param scopeField {@link TextField} the scope field
     * @param start {@link Button} the start button
     * @param hint {@link Label} what the field accepts
     * @param refusal {@link Label} what is wrong with what was typed
     * @param figure {@link Label} the estimated cost
     * @param disclaimer {@link Label} what that figure is worth
     * @param withoutHistory {@link Label} the extra line where nothing backs the figure yet
     * @param message {@link Label} what the screen has to report
     * @param scroll {@link ScrollPane} the pane the cards sit in
     * @param unfolded a {@link Set} of {@link VBox} the month boxes now showing
     * @param folding a {@link Map} of {@link VBox} to {@link Timeline} the folds still running
     */
    private record Controls(HBox modeRow, Label modeHint, Label inboxHeadline, Label inboxDetail,
                            VBox yearRows, Label nothingStaged, Label scopeLabel, TextField scopeField,
                            Button start, Label hint, Label refusal, Label figure, Label disclaimer,
                            Label withoutHistory, Label message, ScrollPane scroll, Set<VBox> unfolded,
                            Map<VBox, Timeline> folding) {

        /**
         * Builds the five mode buttons, once.
         *
         * <p>The row never changes, so it is built here and only re-selected afterwards. Building it
         * again on each fill would replace the button whose press caused the fill.
         *
         * @param presenter {@link RunLauncherPresenter} takes the press
         */
        private void buildModeRow(final RunLauncherPresenter presenter) {
            final var group = new ToggleGroup();
            final List<Node> buttons = new ArrayList<>();
            for (final ModeChoice mode : presenter.view().modes()) {
                final var button = new ToggleButton(mode.label());
                button.setId(mode.id());
                button.setToggleGroup(group);
                button.setSelected(mode.chosen());
                button.getStyleClass().add("run-mode-button");
                button.setOnAction(_ -> {
                    presenter.setMode(mode.mode());
                    this.fillFrom(presenter.view());
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
         * @param presenter {@link RunLauncherPresenter} decides everything this screen shows
         */
        private void drawCounts(final RunLauncherPresenter presenter) {
            final RunLauncherView view = presenter.view();
            final var group = new ToggleGroup();
            // The boxes these hold are about to be discarded, along with any fold still running
            // over one. Left in, they are held for the life of the screen and never asked about
            // again.
            this.folding.values().forEach(Timeline::stop);
            this.folding.clear();
            this.unfolded.clear();
            final List<Node> rows = new ArrayList<>();
            for (final YearChoice year : view.years()) {
                rows.add(this.yearRow(year, group, presenter));
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
            this.inboxDetail.setText(orNothing(view.inbox().detail()));
            this.selectYear(view.years(), view.scopeNamesTheRun());
            this.scopeField.setDisable(!view.scopeNamesTheRun());
            this.nothingStaged.setText(orNothing(view.nothingStaged()));
            this.scopeLabel.setText(view.scopeLabel());
            // Only when it differs. Setting it fires the listener that got here, and an unguarded
            // write would go round again. It also moves the caret, which a reader mid-word notices.
            if (!this.scopeField.getText().equals(view.scopeText())) {
                this.scopeField.setText(view.scopeText());
            }
            this.hint.setText(view.scopeHint());
            this.refusal.setText(orNothing(view.scopeRefusal()));
            this.drawEstimate(view.estimate());
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
         * @param presenter {@link RunLauncherPresenter} takes the click
         * @return {@link Node} the row
         */
        private Node yearRow(final YearChoice year, final ToggleGroup group,
                             final RunLauncherPresenter presenter) {
            // The same two steps a mode button takes: tell the presenter, then draw what it says.
            // The scope field is one of the things a fill writes, so a click and a keystroke reach
            // the screen by the same route.
            final ToggleButton row = this.scopeRow(year.id(), "run-year-row",
                    rowInside("run-year-label", year.label(), year.counts(), null), year.chosen(),
                    () -> presenter.pressYear(year.year()), presenter);
            row.setToggleGroup(group);
            if (year.months().isEmpty()) {
                return row;
            }
            // Every month built here, whichever year is chosen, and shown or hidden by a fill. Built
            // on selection instead, a year's own press would replace the row it came from.
            final List<Node> under = new ArrayList<>();
            year.months().forEach(month -> under.add(this.monthRow(year, month, presenter)));
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
         * @param years a {@link List} of {@link YearChoice} the rows and which show their months
         */
        private void fold(final List<YearChoice> years) {
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
            travel.setOnFinished(_ -> turns.forEach(turn -> {
                this.folding.remove(turn.months());
                settle(turn.months(), turn.shown());
            }));
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
         * @param presenter {@link RunLauncherPresenter} takes the click
         * @return {@link Node} the row
         */
        private Node monthRow(final YearChoice year, final MonthChoice month,
                              final RunLauncherPresenter presenter) {
            final var box = new Region();
            box.getStyleClass().add("run-month-box");
            return this.scopeRow(month.id(), "run-month-row",
                    rowInside("run-month-label", month.label(), month.counts(), box), month.chosen(),
                    () -> presenter.pressMonth(year.year(), month.month()), presenter);
        }

        /**
         * What sits inside a scope row: an optional mark, the name, and what it holds.
         *
         * @param labelClass {@link String} the style class for the name
         * @param name {@link String} the year or month it stands for
         * @param counts {@link String} what that holds
         * @param marker {@link Node} drawn before the name, or null where the row carries none
         * @return {@link HBox} the row's contents
         */
        private static HBox rowInside(final String labelClass, final String name, final String counts,
                                      final @Nullable Node marker) {
            final var label = new Label(name);
            label.getStyleClass().add(labelClass);
            final var held = new Label(counts);
            held.getStyleClass().add("run-year-counts");
            final var inside = marker == null
                    ? new HBox(label, held)
                    : new HBox(marker, label, held);
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
         * @param presenter {@link RunLauncherPresenter} asked again once it has been told
         * @return {@link ToggleButton} the row
         */
        private ToggleButton scopeRow(final String id, final String rowClass, final HBox inside,
                                      final boolean chosen, final Runnable press,
                                      final RunLauncherPresenter presenter) {
            final var row = new ToggleButton();
            row.setId(id);
            row.setGraphic(inside);
            row.setSelected(chosen);
            row.getStyleClass().add(rowClass);
            row.setOnAction(_ -> {
                press.run();
                this.fillFrom(presenter.view());
            });
            return row;
        }

        /**
         * Fills in the cost block, or empties it where this run costs nothing.
         *
         * @param estimate {@link RunLauncherView.Estimate} what a sift would cost, or null
         */
        private void drawEstimate(final RunLauncherView.@Nullable Estimate estimate) {
            this.figure.setText(estimate == null ? "" : estimate.figure());
            this.disclaimer.setText(estimate == null ? "" : estimate.disclaimer());
            this.withoutHistory.setText(estimate == null ? "" : orNothing(estimate.withoutHistory()));
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
