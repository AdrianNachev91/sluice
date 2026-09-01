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
import photos.sluice.adapter.ui.RunLauncherView;
import photos.sluice.adapter.ui.RunResultView;
import photos.sluice.adapter.ui.RunResultView.CardAction;
import photos.sluice.adapter.ui.RunResultView.Count;
import photos.sluice.adapter.ui.RunResultView.Tone;
import photos.sluice.adapter.ui.RunSetupPresenter;
import photos.sluice.adapter.ui.RunStage;

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
 * <p>Done and whatever else the card offers both sit outside the pane that scrolls. A long list of
 * categories must not be able to put the way off this page below the fold.
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
        // Given its own ground only where the run failed, which tone() decides. On every other
        // ending this line introduces the counts under it. On a failure it is the whole card, and
        // it names a file the reader has to go and deal with.
        final var detailBox = new VBox(detail);
        detailBox.setId("run-result-detail-box");
        detailBox.managedProperty().bind(detailBox.visibleProperty());
        detailBox.visibleProperty().bind(detail.visibleProperty());

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
        warning.getStyleClass().add("warning-box");
        showWhile(warning, warningHeadline);


        // The question sits in the body with everything else the card has to say, and only its
        // button joins the row of actions. In that row the sentence reads as a label on Done.
        final var actionQuestion = new Label();
        SettingsRows.wrapping(actionQuestion);
        final var question = new VBox(actionQuestion);
        question.setId("run-result-resume");
        question.getStyleClass().add("run-result-resume");
        showWhile(question, actionQuestion);

        // What a refused press on this card has to say. It belongs here rather than on the
        // launcher, which is behind the card and unread until the card is dismissed. Outside the
        // scrolling body, next to the button it answers. Inside it, a card with counts enough to
        // scroll would put the refusal below the fold while the button that drew it stayed in
        // view. That reads as a press that did nothing.
        final var message = new Label();
        message.setId("run-result-message");
        SettingsRows.wrapping(message);
        SettingsRows.showWhileItSaysSomething(message);

        // Its weight is the arm's, set on every fill, so the class goes on there rather than here.
        final var actionButton = new Button();
        actionButton.setId("run-resume");
        actionButton.managedProperty().bind(actionButton.visibleProperty());
        actionButton.visibleProperty().bind(actionButton.textProperty().isNotEmpty());

        final var done = new Button();
        done.setId("run-done");
        done.getStyleClass().add("run-cancel");
        done.setOnAction(_ -> {
            presenter.dismissResult();
            redraw.run();
        });
        final var doneRow = new HBox(spacer(), actionButton, done);
        doneRow.getStyleClass().add("run-start-row");
        doneRow.setAlignment(Pos.CENTER_RIGHT);

        final var body = new VBox(detailBox, question, warning, counts);
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

        final var page = new VBox(heading, scroll, message, doneRow);
        page.setId("run-result");
        page.getStyleClass().add("run-result");

        final var controls = new Controls(page, heading, detail, detailBox, counts, warningHeadline,
                warningDetail, actionQuestion, actionButton, message, done);
        return new Mounted(page, showing -> controls.fill(showing, presenter, redraw));
    }

    /**
     * Sifts the timeline this card offers, putting whatever question the presenter says is owed.
     *
     * <p>This screen opens the dialog and decides nothing else. Whether one is owed at all, and
     * what it says, are the presenter's.
     *
     * <p>The dialog blocks, so nothing else happens while it is open. Backing out of it leaves the
     * card exactly as it was.
     *
     * @param presenter {@link RunLauncherPresenter} takes the press
     * @param opensOver {@link Node} something on the window the question opens over
     * @param offer {@link CardAction.SiftNow} what the card offered
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     */
    private static void onSiftNow(final RunLauncherPresenter presenter, final Node opensOver,
                                  final CardAction.SiftNow offer, final Runnable redraw) {
        presenter.siftNow(offer, asked -> agreed(opensOver, asked));
        redraw.run();
    }

    /**
     * Puts one question and answers whether the reader agreed.
     *
     * @param opensOver {@link Node} something on the window the question opens over
     * @param asked {@link RunSetupPresenter.Confirmation} what to ask
     * @return boolean true where they chose to go ahead
     */
    private static boolean agreed(final Node opensOver, final RunSetupPresenter.Confirmation asked) {
        return Dialogs.ask(opensOver, asked.heading(), asked.question(),
                new Dialogs.Choice(asked.goAhead(), Dialogs.Role.GO_AHEAD,
                        Dialogs.Emphasis.of(asked.goAheadLeads())),
                new Dialogs.Choice(asked.cancel(), Dialogs.Role.CANCEL,
                        Dialogs.Emphasis.of(!asked.goAheadLeads())))
                .isPresent();
    }

    /**
     * Gives a button one of the two weights, dropping whichever it had.
     *
     * <p>Swaps the two rather than replacing the list. A control arrives with style classes of the
     * toolkit's own, and a button stripped of {@code button} loses its padding and its border along
     * with the weight.
     *
     * @param button {@link Button} the button to weigh
     * @param weight {@link String} the style class to carry
     */
    private static void weigh(final Button button, final String weight) {
        button.getStyleClass().removeAll("run-start", "run-cancel");
        button.getStyleClass().add(weight);
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
     * @param detailBox {@link VBox} what wears that sentence's own ground on a failed card
     * @param counts {@link VBox} the rows saying what the run did
     * @param warningHeadline {@link Label} what a reader needs to know about it, in one line
     * @param warningDetail {@link Label} what caused it and what Sluice did instead
     * @param actionQuestion {@link Label} what the reader is asked before the card's own action
     * @param actionButton {@link Button} the card's own action, beside Done
     * @param message {@link Label} what a refused press on this card has to report
     * @param done {@link Button} the button back to the launcher
     */
    private record Controls(VBox page, Label heading, Label detail, VBox detailBox, VBox counts,
                            Label warningHeadline, Label warningDetail,
                            Label actionQuestion, Button actionButton, Label message, Button done) {

        /**
         * Puts everything the presenter says onto the card.
         *
         * @param showing {@link RunStage.Finished} the card and anything it has to report
         * @param presenter {@link RunLauncherPresenter} takes the press on the card's own action
         * @param redraw {@link Runnable} draws the dashboard again once it has been told
         */
        private void fill(final RunStage.Finished showing, final RunLauncherPresenter presenter,
                          final Runnable redraw) {
            final RunResultView view = showing.result();
            this.heading.setText(view.heading());
            this.tone(view.tone());
            this.detail.setText(SettingsRows.orNothing(view.detail()));
            this.drawCounts(view.counts());
            this.warningHeadline.setText(view.warning() == null ? "" : view.warning().headline());
            this.warningDetail.setText(view.warning() == null ? "" : view.warning().detail());
            this.drawAction(view.action(), presenter, redraw);
            this.drawMessage(showing.message());
            this.done.setText(view.doneLabel());
        }

        /**
         * Marks the card where its run failed, for the stylesheet to draw.
         *
         * <p>Only that one ending changes what is drawn. A run that stopped with work left is
         * neither finished nor at fault, and the words say which it was.
         *
         * <p>A failure's own sentence also takes its own ground. It is the whole of what the card
         * has to say, where every other ending has counts under it. The colour is the ground's
         * rather than the text's: a whole paragraph in the caution colour shouts where a box says
         * it once.
         *
         * <p>Both are set on every fill, so an ending that is not a failure clears them.
         *
         * @param tone {@link Tone} how the run ended
         */
        private void tone(final Tone tone) {
            this.page.pseudoClassStateChanged(FAILED, tone == Tone.FAILED);
            this.detailBox.getStyleClass().remove("warning-box");
            if (tone == Tone.FAILED) {
                this.detailBox.getStyleClass().add("warning-box");
            }
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
         * Fills in whatever this card offers beyond Done, or empties it where it offers nothing.
         *
         * <p>What the button carries is read off the offer at press time rather than captured when
         * the button was built. The button outlives every run this screen shows, so a captured
         * value would act on whichever run happened to be first.
         *
         * <p>Only one of the two arms puts a sentence above the button. Sifting asks in a dialog
         * on the press, because what it costs is not known while this card is being built.
         *
         * @param action {@link CardAction} what the card offers, or null where it offers nothing
         * @param presenter {@link RunLauncherPresenter} takes the press
         * @param redraw {@link Runnable} draws the dashboard again once it has been told
         */
        private void drawAction(final @Nullable CardAction action,
                                final RunLauncherPresenter presenter, final Runnable redraw) {
            switch (action) {
                case null -> {
                    this.actionQuestion.setText("");
                    this.actionButton.setText("");
                    weigh(this.actionButton, "run-cancel");
                    this.actionButton.setOnAction(null);
                }
                // Quiet, because a run that stopped at its spending limit leaves the reader
                // choosing between spending more and spending no more. This app has nothing to
                // lean with there: which one is right depends on what the photos are worth to them.
                case CardAction.ContinueRun(final String asked, final String label, final Path dir) -> {
                    this.actionQuestion.setText(asked);
                    this.actionButton.setText(label);
                    weigh(this.actionButton, "run-cancel");
                    this.actionButton.setOnAction(_ -> {
                        presenter.continueRun(dir);
                        redraw.run();
                    });
                }
                // Loud, because Done is a dismissal rather than a competing action, so nothing else
                // on the card wants the weight. That reasoning holds on a stopped sort too, where
                // the reader came to sort rather than to sift. What sifting costs is put to them in
                // the confirm the press opens, so the weight here is about the way forward rather
                // than about the money.
                case final CardAction.SiftNow offer -> {
                    this.actionQuestion.setText("");
                    this.actionButton.setText(offer.label());
                    weigh(this.actionButton, "run-start");
                    this.actionButton.setOnAction(_ -> onSiftNow(presenter, this.actionButton, offer, redraw));
                }
            }
        }

        /**
         * Fills in the line a refused press leaves behind, in the colour its own kind earns.
         *
         * @param said {@link RunLauncherView.Message} what to report, or null for nothing
         */
        private void drawMessage(final RunLauncherView.@Nullable Message said) {
            this.message.setText(said == null ? "" : said.text());
            this.message.getStyleClass().setAll("run-message",
                    said != null && said.refused() ? "settings-violation" : "settings-confirmation");
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
            if (count.partOfTheRowAbove()) {
                row.getStyleClass().add("run-result-count-part");
            }
            return row;
        }
    }

    /**
     * A built card and the way to fill it in.
     *
     * @param node {@link Node} the card itself
     * @param fill a {@link Consumer} of {@link RunStage.Finished} writes a card onto it
     */
    record Mounted(Node node, Consumer<RunStage.Finished> fill) {
    }
}
