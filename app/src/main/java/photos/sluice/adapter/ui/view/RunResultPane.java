package photos.sluice.adapter.ui.view;

import javafx.css.PseudoClass;
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
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunResultView;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Resume;
import photos.sluice.adapter.ui.RunResultView.Tone;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the dashboard shows once a run has ended: how it ended, what it did, and the way back to the
 * launcher.
 *
 * <p>The count rows are the one part rebuilt, since which of them a run has is not known until it
 * ends. Everything else is built once and written onto.
 *
 * <p>Done and the button that continues a stopped run both sit outside the pane that scrolls. A
 * long list of categories must not be able to put the way off this page below the fold.
 */
final class RunResultPane {

    private static final PseudoClass FAILED = PseudoClass.getPseudoClass("failed");

    private RunResultPane() {
    }

    /**
     * Builds the result card, ready to sit in the dashboard's swapping region.
     *
     * @param presenter {@link RunLauncherPresenter} takes the press on either button
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     * @return {@link Mounted} the card and the way to fill it in
     */
    static Mounted mount(final RunLauncherPresenter presenter, final Runnable redraw) {
        final var heading = new Label();
        heading.setId("run-result-heading");
        SettingsRows.wrapping(heading);
        heading.getStyleClass().add("pane-heading");

        final Label detail = SettingsRows.emptyHelpLine("run-result-detail");
        detail.getStyleClass().add("run-result-detail");

        final var counts = new VBox();
        counts.setId("run-result-counts");
        counts.getStyleClass().add("run-result-counts");

        final var warningHeadline = new Label();
        SettingsRows.wrapping(warningHeadline);
        warningHeadline.getStyleClass().add("settings-caution");
        final var warningDetail = new Label();
        SettingsRows.wrapping(warningDetail);
        warningDetail.getStyleClass().add("run-result-warning-detail");
        final var warning = new VBox(warningHeadline, warningDetail);
        warning.setId("run-result-warning");
        warning.getStyleClass().add("run-result-warning");
        showWhile(warning, warningHeadline);

        final Label archived = SettingsRows.emptyHelpLine("run-result-archived");

        // The question sits in the body with everything else the card has to say, and only its
        // button joins the row of actions. In that row the sentence reads as a label on Done.
        final var resumeQuestion = new Label();
        SettingsRows.wrapping(resumeQuestion);
        final var resume = new VBox(resumeQuestion);
        resume.setId("run-result-resume");
        resume.getStyleClass().add("run-result-resume");
        showWhile(resume, resumeQuestion);

        // Neither button carries the fill, and that is the point. Where a card offers a way on,
        // continuing and stopping are the reader's own choice between spending more and spending
        // no more. A fill on either would be this app leaning on that choice. It has nothing to
        // lean with, since which one is right depends on what the photos are worth to them.
        final var resumeButton = new Button();
        resumeButton.setId("run-resume");
        resumeButton.getStyleClass().add("run-cancel");
        resumeButton.managedProperty().bind(resumeButton.visibleProperty());
        resumeButton.visibleProperty().bind(resumeButton.textProperty().isNotEmpty());

        final var done = new Button();
        done.setId("run-done");
        done.getStyleClass().add("run-cancel");
        done.setOnAction(_ -> {
            presenter.dismissResult();
            redraw.run();
        });
        final var doneRow = new HBox(spacer(), resumeButton, done);
        doneRow.getStyleClass().add("run-start-row");
        doneRow.setAlignment(Pos.CENTER_RIGHT);

        final var body = new VBox(detail, resume, warning, counts, archived);
        body.getStyleClass().add("run-result-body");
        final ScrollPane scroll = SettingsRows.scrolling(body);
        scroll.setMinHeight(0);
        // Asks for exactly the height its rows need, so the buttons sit under the counts rather
        // than at the far end of a wide screen. A scrolling pane has no opinion of its own about
        // that: left alone it takes a fixed preferred height whatever it holds.
        //
        // The viewport rather than the pane. A pane sized to its content is short by its own
        // insets. That leaves the content a hair too tall for the hole it sits in, and draws a
        // scrollbar over rows that already fit.
        scroll.prefViewportHeightProperty().bind(body.heightProperty());

        final var page = new VBox(heading, scroll, doneRow);
        page.setId("run-result");
        page.getStyleClass().add("run-result");

        final var controls = new Controls(page, heading, detail, counts, warningHeadline,
                warningDetail, archived, resumeQuestion, resumeButton, done);
        return new Mounted(page, view -> controls.fill(view, presenter, redraw));
    }

    /**
     * A region that takes room only while the label inside it carries text.
     *
     * <p>Bound on the region rather than on the label, because the region is what holds the ground
     * and the padding. A label hidden inside a shown box leaves an empty stripe.
     *
     * @param region {@link Region} the box
     * @param says {@link Label} the label that decides
     */
    private static void showWhile(final Region region, final Label says) {
        region.managedProperty().bind(region.visibleProperty());
        region.visibleProperty().bind(says.textProperty().isNotEmpty());
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
     * Every control the card fills in after a run ends.
     *
     * @param page {@link VBox} the card itself, which carries the tone
     * @param heading {@link Label} how the run ended
     * @param detail {@link Label} the sentence under it
     * @param counts {@link VBox} the rows saying what the run did
     * @param warningHeadline {@link Label} what a reader needs to know about it, in one line
     * @param warningDetail {@link Label} what caused it and what Sluice did instead
     * @param archived {@link Label} what happened to a previous record of this timeline
     * @param resumeQuestion {@link Label} what the reader is asked before continuing
     * @param resumeButton {@link Button} the button that continues
     * @param done {@link Button} the button back to the launcher
     */
    private record Controls(VBox page, Label heading, Label detail, VBox counts,
                            Label warningHeadline, Label warningDetail, Label archived,
                            Label resumeQuestion, Button resumeButton, Button done) {

        /**
         * Puts everything the presenter says onto the card.
         *
         * @param view {@link RunResultView} what the card shows now
         * @param presenter {@link RunLauncherPresenter} takes the press on the resume button
         * @param redraw {@link Runnable} draws the dashboard again once it has been told
         */
        private void fill(final RunResultView view, final RunLauncherPresenter presenter,
                          final Runnable redraw) {
            this.heading.setText(view.heading());
            this.tone(view.tone());
            this.detail.setText(SettingsRows.orNothing(view.detail()));
            this.drawCounts(view.counts());
            this.warningHeadline.setText(view.warning() == null ? "" : view.warning().headline());
            this.warningDetail.setText(view.warning() == null ? "" : view.warning().detail());
            this.archived.setText(SettingsRows.orNothing(view.archived()));
            this.drawResume(view.resume(), presenter, redraw);
            this.done.setText(view.doneLabel());
        }

        /**
         * Marks the card where its run failed, for the stylesheet to draw.
         *
         * <p>Only that one ending changes what is drawn. A run that stopped with work left is
         * neither finished nor at fault, and the words say which it was. What a failure needs is
         * legibility rather than a colour. Its own sentence is the whole of what the card has to
         * say, where every other ending has counts under it.
         *
         * <p>Set on every fill, so an ending that is not a failure clears it.
         *
         * @param tone {@link Tone} how the run ended
         */
        private void tone(final Tone tone) {
            this.page.pseudoClassStateChanged(FAILED, tone == Tone.FAILED);
        }

        /**
         * Draws one row per thing the run counted.
         *
         * @param rows a {@link List} of {@link Count} what the run did
         */
        private void drawCounts(final List<Count> rows) {
            final List<Node> drawn = new ArrayList<>();
            rows.forEach(count -> drawn.add(countRow(count)));
            this.counts.getChildren().setAll(drawn);
        }

        /**
         * Fills in the offer to continue a stopped run, or empties it where none is open.
         *
         * <p>The directory is read off the offer at press time rather than captured when the
         * button was built. The button outlives every run this screen shows, so a captured one
         * would continue whichever run happened to be first.
         *
         * @param resume {@link Resume} the offer, or null where none is open
         * @param presenter {@link RunLauncherPresenter} takes the press
         * @param redraw {@link Runnable} draws the dashboard again once it has been told
         */
        private void drawResume(final @Nullable Resume resume, final RunLauncherPresenter presenter,
                                final Runnable redraw) {
            this.resumeQuestion.setText(resume == null ? "" : resume.question());
            this.resumeButton.setText(resume == null ? "" : resume.label());
            final Path continuing = resume == null ? null : resume.prepDir();
            this.resumeButton.setOnAction(continuing == null ? null : _ -> {
                presenter.continueRun(continuing);
                redraw.run();
            });
        }

        /**
         * One counted thing, as a name on the left and its number on the right.
         *
         * @param count {@link Count} what was counted
         * @return {@link Node} the row
         */
        private static Node countRow(final Count count) {
            final var label = new Label(count.label());
            label.getStyleClass().add("run-result-count-label");
            final var value = new Label(count.value());
            value.getStyleClass().add("run-result-count-value");
            final var row = new HBox(label, spacer(), value);
            row.setId(count.id());
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("run-result-count");
            return row;
        }
    }

    /**
     * A built card and the way to fill it in.
     *
     * @param node {@link Node} the card itself
     * @param fill a {@link Consumer} of {@link RunResultView} writes a view onto it
     */
    record Mounted(Node node, Consumer<RunResultView> fill) {
    }
}
