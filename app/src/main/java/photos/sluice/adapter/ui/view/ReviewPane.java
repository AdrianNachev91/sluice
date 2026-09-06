package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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

    private ReviewPane() {
    }

    /**
     * Builds the screen, ready to sit in the shell's content area.
     *
     * @param presenter {@link ReviewPresenter} supplies what to draw and takes every press
     * @param navigation {@link ScreenNavigation} how this screen opens another
     * @return {@link Node} the screen
     */
    static Node pane(final ReviewPresenter presenter, final ScreenNavigation navigation) {
        final TextField heading = SelectableText.line();
        heading.setId("review-heading");
        heading.getStyleClass().add("pane-heading");

        final var headerRow = new HBox(heading);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("review-header");

        // Each on its own ground rather than in the caution colour. Both are paragraphs, and a
        // whole paragraph set in that colour shouts where a box says the same thing once.
        final TextArea unreadable = SettingsRows.emptyHelpLine("review-unreadable");
        final VBox unreadableBox = boxed(unreadable, "review-unreadable-box");
        final TextArea message = SettingsRows.emptyHelpLine("review-message");
        final Hyperlink messageWayThere = SettingsRows.wayThereLink("review-message-link");
        final var messageBox = new VBox(SettingsRows.wayThereLines(message, messageWayThere));
        messageBox.setId("review-message-box");
        messageBox.getStyleClass().add("warning-box");
        messageBox.managedProperty().bind(messageBox.visibleProperty());
        messageBox.visibleProperty().bind(message.visibleProperty());
        final TextArea nothingYet = SettingsRows.emptyHelpLine("review-nothing-yet");

        final TextArea explained = SettingsRows.emptyHelpLine("review-explained");
        // The badge stands on the callout's own edge, so the whole thing has to go together. Bound
        // on the outside: hiding only the box inside would leave the badge floating on the page.
        final Node explainer = SettingsRows.badgedCallout(new VBox(explained));
        explainer.setId("review-explained-box");
        explainer.managedProperty().bind(explainer.visibleProperty());
        explainer.visibleProperty().bind(explained.visibleProperty());

        final var groups = new VBox();
        groups.setId("review-groups");
        groups.getStyleClass().add("review-groups");

        final var body = new VBox(messageBox, unreadableBox, explainer, nothingYet, groups);
        body.getStyleClass().add("review-body");
        final ScrollPane scroll = SettingsRows.scrolling(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        PageHeader.heldToTheViewport(scroll, headerRow);
        final var page = new VBox(headerRow, scroll);
        page.setId("review");
        page.getStyleClass().add("review");

        final var controls = new Controls(heading, explained, unreadable, nothingYet, message,
                messageWayThere, navigation, groups, scroll);
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
     * One help line inside the box that gives it its own ground.
     *
     * @param line {@link TextArea} the line, which shows itself only while it says something
     * @param id {@link String} the box's own id
     * @return {@link VBox} the box, which collapses with the line
     */
    private static VBox boxed(final TextArea line, final String id) {
        final var box = new VBox(line);
        box.setId(id);
        box.getStyleClass().add("warning-box");
        box.managedProperty().bind(box.visibleProperty());
        box.visibleProperty().bind(line.visibleProperty());
        return box;
    }

    /**
     * Every control the screen fills in.
     *
     * @param heading {@link TextField} the screen's own name
     * @param explained {@link TextArea} what the screen is and what to do with it
     * @param unreadable {@link TextArea} what to say where a folder could not be read
     * @param nothingYet {@link TextArea} what to say where nothing at all is waiting
     * @param message {@link TextArea} what the screen has to report
     * @param messageWayThere {@link Hyperlink} the control under it, where that report names a screen
     * @param navigation {@link ScreenNavigation} how this screen opens another
     * @param groups {@link VBox} one node per section
     */
    private record Controls(TextField heading, TextArea explained, TextArea unreadable,
                            TextArea nothingYet,
                            TextArea message, Hyperlink messageWayThere, ScreenNavigation navigation,
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
            this.explained.setText(SettingsRows.orNothing(view.explained()));
            this.unreadable.setText(SettingsRows.orNothing(view.unreadable()));
            this.nothingYet.setText(SettingsRows.orNothing(view.nothingYet()));
            this.message.setText(view.message() == null ? "" : view.message().text());
            SettingsRows.offering(this.messageWayThere,
                    view.message() == null ? null : view.message().wayThere(), this.navigation);
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
            final TextField heading = SelectableText.line(group.heading());
            heading.getStyleClass().add("review-group-heading");
            final TextArea explained = SelectableText.prose(group.explained());
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
            final TextField name = SelectableText.line(folder.name());
            name.getStyleClass().add("review-card-name");
            final TextField held = SelectableText.line(folder.held());
            held.getStyleClass().add("review-card-held");
            final TextField age = SelectableText.line(folder.age());
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
                if (notes.beyondTheFold() != null) {
                    drawn.add(SettingsRows.helpLine(notes.beyondTheFold()));
                }
                if (notes.openTheFolder() != null) {
                    drawn.add(SettingsRows.helpLine(notes.openTheFolder()));
                }
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
            final TextArea label = SelectableText.prose(line);
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
            final Button drawn = SettingsRows.actionButton(action.id(), action.label(), action.leading(),
                    action.confirm(), () -> {
                        switch (action.kind()) {
                            case OPEN -> FileManager.open(action.path());
                            case RESCUE -> {
                                presenter.rescue(action);
                                redraw.run();
                            }
                        }
                    });
            drawn.setDisable(!action.live());
            return drawn;
        }
    }
}
