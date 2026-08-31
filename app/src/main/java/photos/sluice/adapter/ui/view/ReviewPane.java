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
import photos.sluice.adapter.ui.ReviewPresenter;
import photos.sluice.adapter.ui.ReviewView;
import photos.sluice.adapter.ui.ReviewView.Action;
import photos.sluice.adapter.ui.ReviewView.FolderCard;
import photos.sluice.adapter.ui.ReviewView.Group;
import photos.sluice.adapter.ui.ReviewView.Notes;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The review screen: every folder Sluice has filled and left for somebody to look at, grouped by what
 * put the photos there.
 *
 * <p>The cards are rebuilt on every fill, because how many there are and what each offers is not
 * known until the three folders have been read. Everything around them is built once and written
 * onto.
 */
final class ReviewPane {

    // Worn by the report line when it is carrying a refusal.
    private static final String CAUTION = "review-report-caution";

    private ReviewPane() {
    }

    /**
     * Builds the screen, ready to sit in the shell's content area.
     *
     * @param presenter {@link ReviewPresenter} supplies what to draw and takes every press
     * @return {@link Node} the screen
     */
    static Node pane(final ReviewPresenter presenter) {
        final var heading = new Label();
        heading.setId("review-heading");
        heading.getStyleClass().add("pane-heading");

        final var headerRow = new HBox(heading);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("review-header");

        // On its own ground rather than in the caution colour. This is a paragraph, and a whole
        // paragraph set in that colour shouts where a box says the same thing once.
        final Label unreadable = SettingsRows.emptyHelpLine("review-unreadable");
        final var unreadableBox = new VBox(unreadable);
        unreadableBox.getStyleClass().add("warning-box");
        unreadableBox.managedProperty().bind(unreadableBox.visibleProperty());
        unreadableBox.visibleProperty().bind(unreadable.visibleProperty());
        final Label nothingYet = SettingsRows.emptyHelpLine("review-nothing-yet");
        final Label message = SettingsRows.emptyHelpLine("review-message");

        final var groups = new VBox();
        groups.setId("review-groups");
        groups.getStyleClass().add("review-groups");

        final var body = new VBox(message, unreadableBox, nothingYet, groups);
        body.getStyleClass().add("review-body");
        final ScrollPane scroll = SettingsRows.scrolling(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        final var page = new VBox(headerRow, scroll);
        page.setId("review");
        page.getStyleClass().add("review");

        final var controls = new Controls(heading, unreadable, nothingYet, message, groups, scroll);
        final Runnable redraw = new Runnable() {
            @Override
            public void run() {
                controls.fill(presenter.view(), presenter, this);
            }
        };
        redraw.run();
        AfterFirstFrame.run(() -> readThenDraw(presenter, redraw));
        return page;
    }

    /**
     * Reads the three folders away from the thread that paints, then draws what it found.
     *
     * @param presenter {@link ReviewPresenter} does the reading
     * @param redraw {@link Runnable} draws the screen once it lands
     */
    private static void readThenDraw(final ReviewPresenter presenter, final Runnable redraw) {
        Thread.ofVirtual().start(() -> {
            presenter.refresh();
            Platform.runLater(redraw);
        });
    }

    /**
     * Every control the screen fills in.
     *
     * @param heading {@link Label} the screen's own name
     * @param unreadable {@link Label} what to say where a folder could not be read
     * @param nothingYet {@link Label} what to say where nothing at all is waiting
     * @param message {@link Label} what the screen has to report
     * @param groups {@link VBox} one node per section
     */
    private record Controls(Label heading, Label unreadable, Label nothingYet, Label message,
                            VBox groups, ScrollPane scroll) {

        /**
         * Puts everything the presenter says onto the controls.
         *
         * @param view {@link ReviewView} what the screen shows now
         * @param presenter {@link ReviewPresenter} takes a press on any card's button
         * @param redraw {@link Runnable} draws the screen again after one of those presses
         */
        private void fill(final ReviewView view, final ReviewPresenter presenter,
                          final Runnable redraw) {
            this.heading.setText(view.heading());
            this.unreadable.setText(SettingsRows.orNothing(view.unreadable()));
            this.nothingYet.setText(SettingsRows.orNothing(view.nothingYet()));
            SettingsRows.report(this.message, view.message(), CAUTION);
            final List<Node> drawn = new ArrayList<>();
            view.groups().forEach(group -> drawn.add(section(group, presenter, redraw, this.scroll)));
            this.groups.getChildren().setAll(drawn);
        }

        /**
         * One section, headed and explained, with its cards under it.
         *
         * @param group {@link Group} the section
         * @param presenter {@link ReviewPresenter} takes a press on any of its buttons
         * @param redraw {@link Runnable} draws the screen again after one of those presses
         * @param scroll {@link ScrollPane} the pane the page sits in, which a fold takes with it
         * @return {@link Node} the section
         */
        private static Node section(final Group group, final ReviewPresenter presenter,
                                    final Runnable redraw, final ScrollPane scroll) {
            final var heading = new Label(group.heading());
            heading.getStyleClass().add("review-group-heading");
            final var explained = new Label(group.explained());
            SettingsRows.wrapping(explained);
            explained.getStyleClass().add("review-group-explained");

            final var cards = new VBox();
            cards.getStyleClass().add("review-cards");
            final List<Node> drawn = new ArrayList<>();
            group.folders().forEach(folder -> drawn.add(card(folder, presenter, redraw, scroll)));
            cards.getChildren().setAll(drawn);

            final var section = new VBox(heading, explained, cards);
            section.setId("review-group-" + group.id());
            section.getStyleClass().add("review-group");
            return section;
        }

        /**
         * One folder's card.
         *
         * @param folder {@link FolderCard} what the card says
         * @param presenter {@link ReviewPresenter} takes a press on its buttons
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @param scroll {@link ScrollPane} the pane the page sits in, which its fold takes with it
         * @return {@link Node} the card
         */
        private static Node card(final FolderCard folder, final ReviewPresenter presenter,
                                 final Runnable redraw, final ScrollPane scroll) {
            final var name = new Label(folder.name());
            name.getStyleClass().add("review-card-name");
            final var held = new Label(folder.held());
            held.getStyleClass().add("review-card-held");
            final var age = new Label(folder.age());
            age.getStyleClass().add("review-card-age");

            final var lines = new VBox(name, held, age);
            lines.getStyleClass().add("review-card-lines");

            final var card = new VBox(lines);
            card.setId(folder.id());
            card.getStyleClass().add("card");
            final Node buttons = actionRow(folder, presenter, redraw);
            final Notes notes = folder.notes();
            if (notes == null) {
                card.getChildren().add(buttons);
                return card;
            }
            // The buttons share the fold's own row rather than sitting under what it opens. Below
            // it they travel down with every line drawn, and a long note takes them off screen.
            final var written = new VBox();
            written.getStyleClass().add("review-notes-lines");
            fillNotes(written, notes);
            card.getChildren().addAll(
                    foldRow(folder.path(), notes, presenter, card, written, scroll, buttons),
                    written);
            return card;
        }

        /**
         * The row carrying the fold's own control and the card's buttons.
         *
         * @param folder {@link Path} which folder a press acts on
         * @param notes {@link Notes} what the fold says and holds
         * @param presenter {@link ReviewPresenter} reads the notes and remembers which fold is open
         * @param card {@link Node} the whole card, whose foot has to end on screen once it opens
         * @param written {@link VBox} the box the note's lines are drawn into
         * @param scroll {@link ScrollPane} the pane the page sits in, which the fold takes with it
         * @param buttons {@link Node} what this card offers, drawn at the row's other end
         * @return {@link Node} the row
         */
        private static Node foldRow(final Path folder, final Notes notes,
                                    final ReviewPresenter presenter, final Node card,
                                    final VBox written, final @Nullable ScrollPane scroll,
                                    final Node buttons) {
            final var toggle = new Button(notes.label());
            toggle.setId(notes.id());
            toggle.getStyleClass().add("review-notes-toggle");
            SettingsRows.pointing(toggle, notes.shown());

            final var gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            final var row = new HBox(toggle, gap, buttons);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("review-notes");

            final var travel = new SectionFold(written, card, scroll);
            travel.to(notes.shown());

            final var showing = new AtomicBoolean(notes.shown());
            toggle.setOnAction(_ -> {
                final boolean opening = !showing.get();
                showing.set(opening);
                SettingsRows.pointing(toggle, opening);
                if (!opening) {
                    travel.to(false);
                    presenter.toggleNotes(folder);
                    return;
                }
                // Off the thread that paints. A note carries a line per photo the folder ever took,
                // so a long-lived junk folder holds thousands.
                Thread.ofVirtual().start(() -> {
                    presenter.toggleNotes(folder);
                    Platform.runLater(() -> {
                        fillNotes(written, presenter.notesOn(folder));
                        travel.to(true);
                    });
                });
            });
            return row;
        }

        /**
         * Puts what a fold holds into the box that travels.
         *
         * @param written {@link VBox} the box
         * @param notes {@link Notes} what the fold holds, or null where the folder has gone
         */
        private static void fillNotes(final VBox written, final @Nullable Notes notes) {
            final List<Node> drawn = new ArrayList<>();
            if (notes != null) {
                notes.lines().forEach(line -> drawn.add(noteLine(line)));
                if (notes.nothingWritten() != null) {
                    drawn.add(noteLine(notes.nothingWritten()));
                }
            }
            written.getChildren().setAll(drawn);
        }

        /**
         * One line of what Sluice wrote.
         *
         * @param line {@link String} the line
         * @return {@link Node} it, drawn
         */
        private static Node noteLine(final String line) {
            final var label = new Label(line);
            SettingsRows.wrapping(label);
            label.getStyleClass().add("review-note-line");
            return label;
        }

        /**
         * A card's buttons.
         *
         * @param folder {@link FolderCard} the card they belong to
         * @param presenter {@link ReviewPresenter} takes the presses it has to
         * @param redraw {@link Runnable} draws the screen again once a press has been taken
         * @return {@link Node} the row
         */
        private static Node actionRow(final FolderCard folder, final ReviewPresenter presenter,
                                      final Runnable redraw) {
            final List<Node> buttons = new ArrayList<>();
            folder.actions().forEach(action -> buttons.add(button(action, presenter, redraw)));
            final var row = new HBox(buttons.toArray(new Node[0]));
            row.setAlignment(Pos.CENTER_RIGHT);
            row.getStyleClass().add("run-start-row");
            return row;
        }

        /**
         * One of a card's buttons.
         *
         * @param action {@link Action} what the button does
         * @param presenter {@link ReviewPresenter} takes the press where one has to be taken
         * @param redraw {@link Runnable} draws the screen again once the press has been taken
         * @return {@link Button} the button
         */
        private static Button button(final Action action, final ReviewPresenter presenter,
                                     final Runnable redraw) {
            return SettingsRows.actionButton(action.id(), action.label(), action.leading(),
                    action.confirm(), () -> {
                        switch (action.kind()) {
                            case OPEN -> FileManager.open(action.path());
                            case MOVE_TO_LIBRARY -> {
                                presenter.moveToLibrary(action);
                                redraw.run();
                            }
                        }
                    });
        }
    }
}
