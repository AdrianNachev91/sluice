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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.RunSetupPresenter.Confirmation;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.adapter.ui.RunsView;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.RunCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The runs screen: every sift on disk, what state it is in, and what can be done about each.
 *
 * <p>The cards are rebuilt on every fill, because how many there are and what each offers is not
 * known until the folder has been read. Everything around them is built once and written onto.
 *
 * <p>The whole page is drawn again after a press rather than patched. A discard removes a card and
 * a continue changes what one offers, so there is no smaller unit of change to aim at.
 */
final class RunsPane {

    // Worn by whichever of this screen's two report lines is carrying a refusal.
    private static final String CAUTION = "runs-report-caution";

    // How long the finished section takes to open or shut. The launcher folds its own rows at the
    // same figure, so the two do not read as different controls.
    private static final Duration FOLD_TRAVEL = Duration.millis(160);

    private RunsPane() {
    }

    /**
     * Builds the screen, ready to sit in the shell's content area.
     *
     * @param presenter {@link RunsPresenter} supplies what to draw and takes every press
     * @param recount {@link Runnable} redraws the sidebar's own count, since what this screen does
     *     is what changes it
     * @return {@link Node} the screen
     */
    static Node pane(final RunsPresenter presenter, final Runnable recount) {
        final var heading = new Label();
        heading.setId("runs-heading");
        heading.getStyleClass().add("pane-heading");

        final var clear = new Button();
        clear.setId("runs-clear-completed");
        clear.getStyleClass().add("run-cancel");

        final var headerRow = new HBox(heading, spacer(), clear);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("runs-header");

        // On its own ground rather than in the caution colour. This is a paragraph, and a whole
        // paragraph set in that colour shouts where a box says the same thing once.
        final Label unreadable = SettingsRows.emptyHelpLine("runs-unreadable");
        final var unreadableBox = new VBox(unreadable);
        unreadableBox.getStyleClass().add("warning-box");
        unreadableBox.managedProperty().bind(unreadableBox.visibleProperty());
        unreadableBox.visibleProperty().bind(unreadable.visibleProperty());
        final Label nothingYet = SettingsRows.emptyHelpLine("runs-nothing-yet");
        final Label message = SettingsRows.emptyHelpLine("runs-message");

        final var cards = new VBox();
        cards.setId("runs-cards");
        cards.getStyleClass().add("runs-cards");

        final var completedToggle = new Button();
        completedToggle.setId("runs-completed-toggle");
        completedToggle.getStyleClass().add("runs-section-toggle");

        final var completedCards = new VBox();
        completedCards.setId("runs-completed-cards");
        completedCards.getStyleClass().add("runs-cards");

        // Shut before anything draws, since the gate below only acts on a fold that has to turn.
        Controls.settle(completedCards, false);
        final var completed = new VBox(completedToggle, completedCards);
        completed.setId("runs-completed");
        completed.getStyleClass().add("runs-completed");

        final var body = new VBox(message, unreadableBox, nothingYet, cards, completed);
        body.getStyleClass().add("runs-body");
        final ScrollPane scroll = SettingsRows.scrolling(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        final var page = new VBox(headerRow, scroll);
        page.setId("runs");
        page.getStyleClass().add("runs");

        final var controls = new Controls(heading, clear, unreadable, nothingYet, message, cards,
                completedToggle, completedCards, completed, scroll, new ArrayList<>(), new HashSet<>());
        final Runnable redraw = new Runnable() {
            @Override
            public void run() {
                controls.fill(presenter.view(), presenter, this);
            }
        };
        // Every press that changes a run changes the sidebar's count too. Drawn from the one
        // reading, rather than each going back to disk for its own.
        final Runnable drawBoth = () -> {
            redraw.run();
            recount.run();
        };
        controls.wire(presenter, drawBoth);
        // A job reports its ending from whatever thread it ran on, so this hops. The read behind
        // the redraw walks the whole runs folder, so it does not hop until that read has landed.
        presenter.setRepaint(() -> readThenDraw(presenter, drawBoth));
        drawBoth.run();
        AfterFirstFrame.run(() -> readThenDraw(presenter, drawBoth));
        return page;
    }

    /**
     * Reads every run away from the thread that paints, then draws what it found.
     *
     * <p>The read diagnoses every run in the folder, opening every sidecar and shard of each. That
     * is slow enough on a real install to be seen, which is why it stays off the thread that
     * paints.
     *
     * @param presenter {@link RunsPresenter} does the reading
     * @param redraw {@link Runnable} draws the screen once it lands
     */
    private static void readThenDraw(final RunsPresenter presenter, final Runnable redraw) {
        Thread.ofVirtual().start(() -> {
            presenter.refresh();
            Platform.runLater(redraw);
        });
    }

    /**
     * Something that pushes what follows it to the far side of a row.
     *
     * @return {@link Region} the gap
     */
    private static Region spacer() {
        final var gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return gap;
    }

    /**
     * Every control the screen fills in.
     *
     * @param heading {@link Label} the screen's own name
     * @param clear {@link Button} clears the finished runs
     * @param unreadable {@link Label} what to say where the folder could not be read
     * @param nothingYet {@link Label} what to say where there are no runs at all
     * @param message {@link Label} what the screen has to report
     * @param cards {@link VBox} one card per unfinished run
     * @param completedToggle {@link Button} folds the finished runs open and shut
     * @param completedCards {@link VBox} one card per finished run
     * @param completed {@link VBox} the whole folded section
     * @param scroll {@link ScrollPane} the pane the body sits in, moved so an opening fold ends up
     *     on screen
     * @param folding a {@link List} of {@link Timeline} the travel in flight, empty when the fold
     *     is at rest. At most one, since the screen has one fold
     * @param unfolded a {@link Set} of {@link VBox} where the fold is headed, holding the section
     *     while it is open. What a travel is toward, not what is on screen this instant
     */
    private record Controls(Label heading, Button clear, Label unreadable, Label nothingYet,
                            Label message, VBox cards, Button completedToggle,
                            VBox completedCards, VBox completed, ScrollPane scroll,
                            List<Timeline> folding, Set<VBox> unfolded) {

        /**
         * Wires the controls that never change what they do.
         *
         * @param presenter {@link RunsPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         */
        private void wire(final RunsPresenter presenter, final Runnable redraw) {
            this.clear.setOnAction(_ -> {
                presenter.clearCompleted();
                redraw.run();
            });
            this.completedToggle.setOnAction(_ -> {
                presenter.toggleCompleted();
                redraw.run();
            });
        }

        /**
         * Puts everything the presenter says onto the controls.
         *
         * @param view {@link RunsView} what the screen shows now
         * @param presenter {@link RunsPresenter} takes a press on any card's button
         * @param redraw {@link Runnable} draws the screen again after one of those presses
         */
        private void fill(final RunsView view, final RunsPresenter presenter,
                          final Runnable redraw) {
            this.heading.setText(view.heading());
            this.clear.setText(view.clearCompleted());
            this.clear.setDisable(!view.canClearCompleted());
            this.unreadable.setText(SettingsRows.orNothing(view.unreadable()));
            this.nothingYet.setText(SettingsRows.orNothing(view.nothingYet()));
            // Only a refusal wears the caution colour. A sweep that worked is an ordinary report,
            // and the class comes off again so the next one is not dressed as the last.
            final Message said = view.message();
            this.message.setText(said == null ? "" : said.text());
            this.message.getStyleClass().remove(CAUTION);
            if (said != null && said.refused()) {
                this.message.getStyleClass().add(CAUTION);
            }
            this.draw(this.cards, view.unfinished(), presenter, redraw);
            this.completedToggle.setText(view.completedHeading());
            pointing(this.completedToggle, view.completedShown());
            this.completed.setVisible(!view.completed().isEmpty());
            this.completed.setManaged(!view.completed().isEmpty());
            this.draw(this.completedCards, view.completed(), presenter, redraw);
            // Shut where the section itself is gone. A sweep leaves nothing to fold, and a travel
            // over a subtree the screen is no longer laying out reads its own geometry off bounds
            // nothing has updated.
            this.fold(view.completedShown() && !view.completed().isEmpty());
        }

        /**
         * Replaces one section's cards with the ones it now holds.
         *
         * @param into {@link VBox} the section
         * @param runs a {@link List} of {@link RunCard} its cards, in the order drawn
         * @param presenter {@link RunsPresenter} takes a press on any of their buttons
         * @param redraw {@link Runnable} draws the screen again after one of those presses
         */
        private void draw(final VBox into, final List<RunCard> runs, final RunsPresenter presenter,
                          final Runnable redraw) {
            final List<Node> drawn = new ArrayList<>();
            runs.forEach(run -> drawn.add(card(run, presenter, redraw)));
            into.getChildren().setAll(drawn);
        }

        /**
         * Opens or shuts the finished section, travelling rather than jumping.
         *
         * <p>The pane travels with it, so a section opening below the fold ends up on screen
         * instead of leaving the reader to go and find it.
         *
         * <p>A fold nobody can watch takes its end state and starts no travel. That covers the
         * screen being built, where the page is not in a scene yet. It also covers a render, which
         * would otherwise capture whichever frame the travel happened to be on.
         *
         * @param open boolean whether the section should end up showing
         */
        private void fold(final boolean open) {
            // A fold already going where it is being asked to go has nothing to do. Without this,
            // every redraw travels again, and every travel carries the pane with it. A press on any
            // card, or a job ending, would then throw a reader who had scrolled back up to the foot
            // of the open section.
            if (this.unfolded.contains(this.completedCards) == open) {
                return;
            }
            if (open) {
                this.unfolded.add(this.completedCards);
            } else {
                this.unfolded.remove(this.completedCards);
            }
            this.folding.forEach(Timeline::stop);
            this.folding.clear();
            if (this.completedCards.getScene() == null || this.completedCards.getWidth() <= 0) {
                settle(this.completedCards, open);
                return;
            }
            // The height it is about to travel to is read off a laid-out page. A press arrives
            // between pulses, so the layout that reading wants can still be pending.
            layOut(this.scroll.getContent());
            this.completedCards.setVisible(true);
            this.completedCards.setMinHeight(0);
            this.completedCards.setMaxHeight(this.completedCards.getHeight());
            final double to = open
                    ? this.completedCards.prefHeight(this.completedCards.getWidth())
                    : 0;
            final List<KeyValue> frames = new ArrayList<>(List.of(
                    new KeyValue(this.completedCards.maxHeightProperty(), to, Interpolator.EASE_BOTH),
                    new KeyValue(this.completedCards.opacityProperty(), open ? 1 : 0,
                            Interpolator.EASE_BOTH)));
            final KeyValue following = open
                    ? this.following(to - this.completedCards.getHeight())
                    : null;
            if (following != null) {
                frames.add(following);
            }
            final var travel = new Timeline(new KeyFrame(FOLD_TRAVEL,
                    frames.toArray(new KeyValue[0])));
            travel.setOnFinished(_ -> {
                this.folding.clear();
                settle(this.completedCards, open);
                if (open) {
                    Platform.runLater(this::arrive);
                }
            });
            this.folding.add(travel);
            travel.play();
        }

        /**
         * Where the pane has to end up for the opening section to be on screen, as a frame it can
         * travel through.
         *
         * <p>Without it the pane holds a fraction of a page that is growing under it, so the rows
         * already on screen slide while the section opens. The reader sees that as the page
         * jumping rather than as the section arriving.
         *
         * <p>Only opening asks for this. A section closing is either in front of the reader already
         * or one they never opened, and travelling toward either shows them nothing.
         *
         * @param growth double how much taller the page is about to be
         * @return {@link KeyValue} the frame, or null where the page cannot scroll at all
         */
        private @Nullable KeyValue following(final double growth) {
            if (!(this.scroll.getContent() instanceof final Parent laidOut)) {
                return null;
            }
            final double viewport = this.scroll.getViewportBounds().getHeight();
            final double now = laidOut.getLayoutBounds().getHeight();
            final double scrollable = now + growth - viewport;
            if (scrollable <= 0) {
                return null;
            }
            final double top = this.scroll.getVvalue() / this.scroll.getVmax()
                    * Math.max(now - viewport, 0);
            final double foot = laidOut
                    .sceneToLocal(this.completed.localToScene(this.completed.getLayoutBounds()))
                    .getMaxY() + growth;
            return new KeyValue(this.scroll.vvalueProperty(),
                    Math.clamp(Math.max(top, foot - viewport), 0, scrollable)
                            / scrollable * this.scroll.getVmax(), Interpolator.EASE_BOTH);
        }

        /**
         * Moves the pane so the section that just opened ends on screen.
         *
         * <p>Deferred a pulse by its caller, and that is the whole of it. Lifting a box's ceiling
         * only gives it its real height on the layout pass after. A reading taken as the travel
         * ends answers for the page as it stood before the section grew.
         */
        private void arrive() {
            // The fold can have been shut again in the pulse this waited out, and travelling
            // toward a section that is closing shows the reader nothing.
            if (!this.unfolded.contains(this.completedCards)
                    || !(this.scroll.getContent() instanceof final Parent laidOut)) {
                return;
            }
            layOut(laidOut);
            final double viewport = this.scroll.getViewportBounds().getHeight();
            final double scrollable = laidOut.getLayoutBounds().getHeight() - viewport;
            if (scrollable <= 0) {
                return;
            }
            final double foot = laidOut
                    .sceneToLocal(this.completed.localToScene(this.completed.getLayoutBounds()))
                    .getMaxY();
            final double top = this.scroll.getVvalue() / this.scroll.getVmax() * scrollable;
            this.scroll.setVvalue(Math.clamp(Math.max(top, foot - viewport), 0, scrollable)
                    / scrollable * this.scroll.getVmax());
        }

        /**
         * Where the fold rests, once nothing is moving it.
         *
         * @param section {@link VBox} the folded section
         * @param open boolean whether it is showing
         */
        private static void settle(final VBox section, final boolean open) {
            // A ceiling on its own does nothing here. A box asks for its computed height as a
            // minimum, and a minimum outranks a maximum, so the cards would keep their room and the
            // page would scroll for a section nobody has opened.
            section.setMinHeight(0);
            section.setMaxHeight(open ? Region.USE_COMPUTED_SIZE : 0);
            section.setOpacity(open ? 1 : 0);
            section.setVisible(open);
        }

        /**
         * Brings a subtree's layout up to date, so what is read off it is where things end up.
         *
         * @param node {@link Node} the subtree, ignored where it is not one
         */
        private static void layOut(final Node node) {
            if (node instanceof final Parent parent) {
                parent.applyCss();
                parent.layout();
            }
        }

        /**
         * Turns a fold's marker to say which way it is.
         *
         * @param toggle {@link Button} the fold's own control
         * @param open boolean whether the section below it is showing
         */
        private static void pointing(final Button toggle, final boolean open) {
            if (toggle.getGraphic() == null) {
                toggle.setGraphic(foldMarker());
            }
            toggle.getGraphic().setRotate(open ? 90 : 0);
        }

        /**
         * The marker on a fold: a triangle, drawn rather than typed so no font has to carry it.
         *
         * @return {@link Polygon} the triangle, pointing right
         */
        private static Polygon foldMarker() {
            final var triangle = new Polygon(0, 0, 0, 8, 6, 4);
            triangle.getStyleClass().add("runs-fold-marker");
            return triangle;
        }

        /**
         * One run's card.
         *
         * @param run {@link RunCard} what the card says
         * @param presenter {@link RunsPresenter} takes a press on its buttons
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Node} the card
         */
        private static Node card(final RunCard run, final RunsPresenter presenter,
                                 final Runnable redraw) {
            final var scope = new Label(run.scope());
            scope.getStyleClass().add("runs-card-scope");
            final var headline = new Label(run.headline());
            headline.getStyleClass().add("runs-card-headline");

            final var lines = new VBox(scope, headline);
            lines.getStyleClass().add("runs-card-lines");
            addIfSaid(lines, run.detail(), "runs-card-detail");
            addIfSaid(lines, run.sheets(), "runs-card-sheets");
            addIfSaid(lines, run.age(), "runs-card-age");

            final var card = new VBox(lines);
            card.setId(run.id());
            card.getStyleClass().add("card");
            if (!run.actions().isEmpty()) {
                card.getChildren().add(actionRow(run, presenter, redraw));
            }
            return card;
        }

        /**
         * A card's buttons, along the bottom and pushed to its right.
         *
         * @param run {@link RunCard} the run they act on
         * @param presenter {@link RunsPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Node} the row
         */
        private static Node actionRow(final RunCard run, final RunsPresenter presenter,
                                      final Runnable redraw) {
            final List<Node> buttons = new ArrayList<>();
            buttons.add(spacer());
            run.actions().forEach(action -> buttons.add(button(action, presenter, redraw)));
            final var row = new HBox(buttons.toArray(new Node[0]));
            row.setAlignment(Pos.CENTER_RIGHT);
            row.getStyleClass().add("run-start-row");
            return row;
        }

        /**
         * One of a card's buttons.
         *
         * <p>An action carrying a question is asked before anything happens, and a reader who backs
         * out leaves the run exactly as it was.
         *
         * @param action {@link Action} what the button does
         * @param presenter {@link RunsPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Button} the button
         */
        private static Button button(final Action action, final RunsPresenter presenter,
                                     final Runnable redraw) {
            final var button = new Button(action.label());
            button.setId(action.id());
            // Carrying a run on is the way forward from a card, and throwing it away is not, so
            // only the first wears the fill. The result card deliberately does the opposite: there
            // the choice between going on and stopping is the reader's to make unprompted.
            button.getStyleClass().add(action.leading() ? "run-start" : "run-cancel");
            button.setOnAction(_ -> {
                if (agreed(action.confirm())) {
                    presenter.press(action);
                    redraw.run();
                }
            });
            return button;
        }

        /**
         * Whether a reader agreed to what an action is about to do.
         *
         * <p>Keeping it is the loud choice, and throwing away is not. Nothing on this screen can
         * put back what a discard files away, so Enter lands on the answer that changes nothing.
         * That is what {@link Dialogs.Emphasis} asks for wherever a dialog is about something the
         * app cannot undo.
         *
         * @param confirm {@link Confirmation} what to ask, or null where nothing needs asking
         * @return boolean true where the action should go ahead
         */
        private static boolean agreed(final @Nullable Confirmation confirm) {
            return confirm == null || Dialogs.ask(confirm.heading(), confirm.question(),
                    new Dialogs.Choice(confirm.goAhead(), Dialogs.Role.GO_AHEAD,
                            Dialogs.Emphasis.QUIET),
                    new Dialogs.Choice(confirm.cancel(), Dialogs.Role.CANCEL,
                            Dialogs.Emphasis.LOUD)).isPresent();
        }

        /**
         * Adds a line to a card, where there is one to add.
         *
         * @param into {@link VBox} the card's lines
         * @param says what the line says, or null where the card has no such line
         * @param styleClass {@link String} the line's own style class
         */
        private static void addIfSaid(final VBox into, final @Nullable String says,
                                      final String styleClass) {
            if (says == null) {
                return;
            }
            final var line = new Label(says);
            SettingsRows.wrapping(line);
            line.getStyleClass().add(styleClass);
            into.getChildren().add(line);
        }
    }
}
