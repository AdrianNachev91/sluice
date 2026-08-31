package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.adapter.ui.RunsView;
import photos.sluice.adapter.ui.RunsView.Action;
import photos.sluice.adapter.ui.RunsView.RunCard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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

        final var headerRow = new HBox(heading);
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

        // Beside the fold's own control rather than in the page header. It acts on what the fold
        // holds, so it belongs where they are, and it travels down with them as they open. In the
        // header it sat in the page's most prominent spot for the one press this screen steers a
        // reader away from once its confirm is up.
        final var completedRow = new HBox(completedToggle, spacer(), clear);
        completedRow.setAlignment(Pos.CENTER_LEFT);
        completedRow.getStyleClass().add("runs-completed-row");

        final var completed = new VBox(completedRow, completedCards);
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
                completedToggle, completedCards, completed,
                new SectionFold(completedCards, completed, scroll));
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
        // Draws from the reading already taken, rather than reading again. Registered per screen
        // and replaced by the next one built. A pane the reader has left stops being drawn into as
        // soon as they come back to a new one.
        presenter.setRedrawCards(() -> Platform.runLater(drawBoth));
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
     * @param fold {@link SectionFold} opens and shuts that section
     */
    private record Controls(Label heading, Button clear, Label unreadable, Label nothingYet,
                            Label message, VBox cards, Button completedToggle,
                            VBox completedCards, VBox completed, SectionFold fold) {

        /**
         * Wires the controls that never change what they do.
         *
         * @param presenter {@link RunsPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         */
        private void wire(final RunsPresenter presenter, final Runnable redraw) {
            // The question is read now rather than from the fill that drew the button. So it names
            // the runs that are finished at the moment of the press.
            this.clear.setOnAction(_ -> {
                if (Dialogs.agreed(this.clear, presenter.view().clearConfirm())) {
                    presenter.clearCompleted();
                    redraw.run();
                }
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
            SettingsRows.report(this.message, view.message(), CAUTION);
            this.draw(this.cards, view.unfinished(), presenter, redraw);
            this.completedToggle.setText(view.completedHeading());
            SettingsRows.pointing(this.completedToggle, view.completedShown());
            this.completed.setVisible(!view.completed().isEmpty());
            this.completed.setManaged(!view.completed().isEmpty());
            this.draw(this.completedCards, view.completed(), presenter, redraw);
            // Shut where the section itself is gone. A sweep leaves nothing to fold, and a travel
            // over a subtree the screen is no longer laying out reads its own geometry off bounds
            // nothing has updated.
            this.fold.to(view.completedShown() && !view.completed().isEmpty());
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
            addIfPresent(lines, run.detail(), "runs-card-detail");
            addIfPresent(lines, run.sheets(), "runs-card-sheets");

            final var card = new VBox(lines);
            card.setId(run.id());
            card.getStyleClass().add("card");
            addIfPresent(lines, run.age(), "runs-card-age");
            final RunsView.Waiting waiting = run.waiting();
            if (waiting != null) {
                card.getChildren().add(waitingBlock(waiting, presenter, redraw));
            }
            // Kept with the button it is about rather than up among the run's own lines. A card
            // carrying a waiting block would otherwise put that whole block between the two.
            final RunsView.Redo redo = run.redo();
            if (redo != null) {
                addIfPresent(card, redo.note(), "runs-card-detail");
            }
            if (redo != null && redo.drawnAt() == null) {
                final var offer = new HBox(redoButton(redo, presenter, redraw));
                offer.getStyleClass().add("runs-card-copies");
                card.getChildren().add(offer);
            }
            if (!run.actions().isEmpty() || redo != null) {
                card.getChildren().add(actionRow(run, presenter, redraw));
            }
            return card;
        }

        /**
         * What a card shows while its run is still owed judged sheets.
         *
         * @param waiting {@link RunsView.Waiting} what the card carries in this state
         * @param presenter {@link RunsPresenter} writes the text and takes each change
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Node} the block
         */
        private static Node waitingBlock(final RunsView.Waiting waiting,
                                         final RunsPresenter presenter, final Runnable redraw) {
            final var note = new Label(waiting.note());
            SettingsRows.wrapping(note);
            note.getStyleClass().add("runs-card-detail");

            final var folder = new Label(waiting.folder().toString());
            SettingsRows.wrapping(folder);
            folder.getStyleClass().add("runs-card-folder");

            final var block = new VBox(note, folder);
            block.getStyleClass().add("runs-card-waiting");
            if (waiting.copyPrompt() != null) {
                final var copyRow = new HBox(copyButton("run-copy-prompt", waiting.copyPrompt(),
                        presenter.copied(), redraw,
                        () -> presenter.instructionsFor(waiting.folder(), waiting.promptCorrects())));
                copyRow.getStyleClass().add("runs-card-copies");
                block.getChildren().add(copyRow);
            }
            return block;
        }

        /**
         * The button that asks for a run's rejected answers again.
         *
         * <p>A press that goes ahead redraws and says nothing on the button itself. It files sheets
         * away whichever route it takes, so the card that replaces this one is the answer. A press
         * the reader called off changes nothing and draws nothing.
         *
         * @param redo {@link RunsView.Redo} what the card carries about it
         * @param presenter {@link RunsPresenter} sets the answers aside and does what getting them
         *     judged again takes
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Button} the button
         */
        private static Button redoButton(final RunsView.Redo redo, final RunsPresenter presenter,
                                         final Runnable redraw) {
            final var button = new Button(redo.label());
            button.setId(redo.id());
            button.getStyleClass().add(redo.leading() ? "run-start" : "run-cancel");
            button.setOnAction(_ -> {
                if (Dialogs.agreed(button, redo.confirm())) {
                    final String text = presenter.judgeAgain(redo.prepDir(), redo.scope());
                    if (text != null) {
                        CopyableTrace.putOnTheClipboard(text);
                    }
                    redraw.run();
                }
            });
            return button;
        }

        /**
         * A button that puts something on the clipboard and says it did.
         *
         * <p>Says so on itself rather than in a line elsewhere on the page. A copy is over the
         * instant it is asked for, and a message somewhere else would be one more thing to find.
         *
         * <p>A press that moved the run says nothing here. The presenter asks for the screen to be
         * read again, and the card that replaces this one is what reports it.
         *
         * @param id {@link String} the button's id
         * @param label {@link String} what it says before it is pressed
         * @param done {@link String} what it says once it has copied
         * @param redraw {@link Runnable} draws the card again where the press had something to
         *     report and no clipboard to report it on
         * @param text a {@link Supplier} of {@link String} what to copy, or null where it could not
         *     be written
         * @return {@link Button} the button
         */
        private static Button copyButton(final String id, final String label, final String done,
                                         final Runnable redraw,
                                         final Supplier<@Nullable String> text) {
            final var button = new Button(label);
            button.setId(id);
            button.getStyleClass().add("run-cancel");
            button.setOnAction(_ -> {
                final String copied = text.get();
                if (copied == null) {
                    redraw.run();
                    return;
                }
                CopyableTrace.putOnTheClipboard(copied);
                button.setText(done);
            });
            return button;
        }

        /**
         * A card's buttons, along the bottom and pushed to its right.
         *
         * <p>Where the way back sits among them is {@link RunsView.Redo#drawnAt()}'s to say, and a
         * null there keeps it out of the row entirely. The row is one ordering question and the
         * presenter answers all of it.
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
            final RunsView.Redo redo = run.redo();
            // Clamped rather than trusted. A position past the end would otherwise drop the control
            // silently, and a card missing its only way forward looks like a card that has none.
            final int redoAt = redo == null || redo.drawnAt() == null
                    ? -1
                    : Math.min(redo.drawnAt(), run.actions().size());
            for (int i = 0; i <= run.actions().size(); i++) {
                if (redo != null && i == redoAt) {
                    buttons.add(redoButton(redo, presenter, redraw));
                }
                if (i < run.actions().size()) {
                    buttons.add(button(run.actions().get(i), presenter, redraw));
                }
            }
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
            return SettingsRows.actionButton(action.id(), action.label(), action.leading(),
                    action.confirm(), () -> {
                        presenter.press(action);
                        redraw.run();
                    });
        }

        /**
         * Adds a line to a card, where there is one to add.
         *
         * @param into {@link VBox} the card's lines
         * @param value what the line reads, or null where the card has no such line
         * @param styleClass {@link String} the line's own style class
         */
        private static void addIfPresent(final VBox into, final @Nullable String value,
                                         final String styleClass) {
            if (value == null) {
                return;
            }
            final var line = new Label(value);
            SettingsRows.wrapping(line);
            line.getStyleClass().add(styleClass);
            into.getChildren().add(line);
        }
    }
}
