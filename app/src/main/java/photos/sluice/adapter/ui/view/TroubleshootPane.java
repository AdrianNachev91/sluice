package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.TroubleshootPresenter;
import photos.sluice.adapter.ui.TroubleshootView;
import photos.sluice.adapter.ui.TroubleshootView.Action;
import photos.sluice.adapter.ui.RunLauncherView.Message;
import photos.sluice.adapter.ui.TroubleshootView.Detail;
import photos.sluice.adapter.ui.TroubleshootView.Option;
import photos.sluice.adapter.ui.TroubleshootView.Problem;
import photos.sluice.adapter.ui.TroubleshootView.SameProblem;

import java.util.ArrayList;
import java.util.List;

/**
 * The troubleshoot screen: what one damaged sift's records still have wrong with them, and what a
 * reader can do about each.
 *
 * <p>Every value it shows and every sentence on it comes from {@link TroubleshootPresenter}. This
 * class lays those out and hands each press back. It never decides what a finding means or which
 * answers a reader is offered.
 *
 * <p>Reached from a run's own card rather than from the sidebar, and it takes the whole content
 * area while it is up. Back returns to the runs list.
 */
final class TroubleshootPane {

    private static final String BANNER = "troubleshoot-banner";

    private TroubleshootPane() {
    }

    /**
     * Builds the screen, ready to sit in the shell's content area.
     *
     * @param presenter {@link TroubleshootPresenter} supplies what to draw and takes every press
     * @return {@link Node} the screen
     */
    static Node pane(final TroubleshootPresenter presenter) {
        final var heading = new Label();
        heading.setId("troubleshoot-heading");
        heading.getStyleClass().add("pane-heading");

        final Button back = WayBack.to("troubleshoot-back", "", presenter::back);

        final var header = new VBox(back, heading);
        header.getStyleClass().add("pane-header");

        final Label checking = SettingsRows.emptyHelpLine("troubleshoot-checking");
        final Label summary = SettingsRows.emptyHelpLine("troubleshoot-summary");
        final Label nothingLeft = SettingsRows.emptyHelpLine("troubleshoot-nothing-left");

        final var problems = new VBox();
        problems.setId("troubleshoot-problems");
        problems.getStyleClass().add("runs-cards");

        final var detailToggle = new Button();
        detailToggle.setId("troubleshoot-detail-toggle");
        detailToggle.getStyleClass().add("runs-section-toggle");
        final var detailCopy = new Button();
        detailCopy.setId("troubleshoot-detail-copy");
        detailCopy.getStyleClass().add("button-quiet");
        final TextArea trace = CopyableTrace.area("troubleshoot-detail-text", "");
        final var detailHead = new HBox(detailToggle, spacer(), detailCopy);
        detailHead.setAlignment(Pos.CENTER_LEFT);
        final var detail = new VBox(detailHead, trace);
        detail.setId("troubleshoot-detail");
        detail.getStyleClass().add("troubleshoot-detail");

        final var body = new VBox(checking, summary, problems, nothingLeft, detail);
        body.getStyleClass().add("runs-body");
        final ScrollPane scroll = SettingsRows.scrolling(body);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        final var fold = new SectionFold(trace, detail, scroll);

        final var actions = new HBox();
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setId("troubleshoot-actions");
        actions.getStyleClass().add("run-start-row");

        PageHeader.heldToTheViewport(scroll, header, actions);
        final var page = new VBox(header, scroll, actions);
        page.setId("troubleshoot");
        page.getStyleClass().add("runs");

        final var controls = new Controls(back, heading, page, checking, summary, problems,
                nothingLeft, detail, detailToggle, detailCopy, trace, actions, fold,
                new ArrayList<>(), new ArrayList<>());
        final Runnable redraw = new Runnable() {
            @Override
            public void run() {
                controls.fill(presenter.view(), presenter, this);
            }
        };
        controls.wireTheFold(redraw);
        presenter.setRepaint(() -> Platform.runLater(redraw));
        redraw.run();
        return page;
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
     * @param back {@link Button} the way out
     * @param heading {@link Label} the screen's own name
     * @param page {@link VBox} the screen itself, which a report is put at the top of
     * @param checking {@link Label} what to say while the pass runs
     * @param summary {@link Label} what the pass found
     * @param problems {@link VBox} one row per problem
     * @param nothingLeft {@link Label} what to say in place of the rows
     * @param detail {@link VBox} the technical report and its controls
     * @param detailToggle {@link Button} folds the report open and shut
     * @param detailCopy {@link Button} puts the report on the clipboard
     * @param trace {@link TextArea} the report itself
     * @param actions {@link HBox} the buttons acting on the whole run
     * @param fold {@link SectionFold} opens and shuts the report
     * @param unfolded a {@link List} of {@link VBox} holding the report while it is open, empty
     *     while it is shut
     * @param reported a {@link List} of {@link Integer} the report number the banner now up came
     *     from, empty before the first draw. Held so a redraw leaves the banner it already put up
     *     alone rather than restarting its four seconds
     */
    private record Controls(Button back, Label heading, VBox page, Label checking, Label summary,
                            VBox problems, Label nothingLeft, VBox detail, Button detailToggle,
                            Button detailCopy, TextArea trace, HBox actions, SectionFold fold,
                            List<VBox> unfolded, List<Integer> reported) {

        /**
         * Wires the fold, which is the one control whose press changes nothing on disk.
         *
         * @param redraw {@link Runnable} draws the screen again
         */
        private void wireTheFold(final Runnable redraw) {
            this.detailToggle.setOnAction(_ -> {
                if (this.unfolded.contains(this.detail)) {
                    this.unfolded.clear();
                } else {
                    this.unfolded.add(this.detail);
                }
                redraw.run();
            });
        }

        /**
         * Puts everything the presenter says onto the controls.
         *
         * @param view {@link TroubleshootView} what the screen shows now
         * @param presenter {@link TroubleshootPresenter} takes every press
         * @param redraw {@link Runnable} draws the screen again after one of those presses
         */
        private void fill(final TroubleshootView view, final TroubleshootPresenter presenter,
                          final Runnable redraw) {
            this.back.setText(view.back());
            this.heading.setText(view.heading());
            this.report(view.message(), view.reportNumber());
            this.checking.setText(SettingsRows.orNothing(view.checking()));
            this.summary.setText(SettingsRows.orNothing(view.summary()));
            this.nothingLeft.setText(SettingsRows.orNothing(view.nothingLeft()));
            this.drawProblems(view.problems(), presenter, redraw);
            this.drawDetail(view.detail(), presenter);
            this.drawActions(view.actions(), presenter, redraw);
        }

        /**
         * Puts what the screen has to report at the top of it, above everything that scrolls.
         *
         * <p>A banner rather than a line in the body. What this screen reports is the outcome of a
         * press somewhere down a page that can run long. A line in that body is read only by
         * somebody already looking at it. The report that matters most says an answer left the
         * problem where it was, which is the one case where nothing else on the screen changed.
         *
         * <p>Only a report the screen has not drawn yet puts up a new banner. Every press redraws
         * the whole screen, and a fresh banner each time would restart the four seconds for as long
         * as the reader kept pressing.
         *
         * @param said {@link Message} what to report, or null where there is nothing
         * @param number int which report this is, counted by the presenter
         */
        private void report(final @Nullable Message said, final int number) {
            if (this.reported.equals(List.of(number))) {
                return;
            }
            this.reported.clear();
            this.reported.add(number);
            this.page.getChildren().removeIf(node -> BANNER.equals(node.getId()));
            if (said == null) {
                return;
            }
            // Every report this screen has is one short sentence about the press just made, so all
            // of them leave on their own. Nothing here names a path or a count to be read twice.
            final HBox banner = SettingsRows.banner(this.page, BANNER, said.text(), true);
            if (said.refused()) {
                banner.getStyleClass().add("settings-banner-caution");
            }
            this.page.getChildren().addFirst(banner);
        }

        /**
         * Replaces the rows with the ones the screen now holds.
         *
         * @param problems a {@link List} of {@link SameProblem} what is on the screen now
         * @param presenter {@link TroubleshootPresenter} takes a press on any row's buttons
         * @param redraw {@link Runnable} draws the screen again once it has been told
         */
        private void drawProblems(final List<SameProblem> problems,
                                  final TroubleshootPresenter presenter, final Runnable redraw) {
            this.problems.getChildren().setAll(problems.stream()
                    .flatMap(same -> drawn(same, presenter, redraw).stream())
                    .toList());
        }

        /**
         * How one kind's problems are laid out.
         *
         * <p>Rows a reader can answer keep a card each. The buttons are what they aim at, and
         * several sets inside one card read as one set of choices for the whole group. Rows with
         * nothing to answer share their kind's card.
         *
         * @param same {@link SameProblem} the heading and its rows
         * @param presenter {@link TroubleshootPresenter} takes a press on any row's buttons
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return a {@link List} of {@link Node} what to add, in drawing order
         */
        private static List<Node> drawn(final SameProblem same,
                                        final TroubleshootPresenter presenter,
                                        final Runnable redraw) {
            final List<Node> nodes = new ArrayList<>();
            if (same.heading() != null) {
                nodes.add(heading(same.heading()));
            }
            if (same.rows().stream().anyMatch(problem -> !problem.options().isEmpty())) {
                same.rows().forEach(problem -> {
                    final Node row = row(problem, presenter, redraw);
                    row.getStyleClass().add("card");
                    nodes.add(row);
                });
                return nodes;
            }
            final var card = new VBox();
            card.getStyleClass().add("card");
            same.rows().forEach(problem -> {
                final Node row = row(problem, presenter, redraw);
                row.getStyleClass().add("troubleshoot-stacked-problem");
                card.getChildren().add(row);
            });
            nodes.add(card);
            return nodes;
        }

        /**
         * The line above one kind's rows, counting them.
         *
         * @param said {@link String} what it says
         * @return {@link Label} the heading
         */
        private static Label heading(final String said) {
            final var heading = new Label(said);
            heading.getStyleClass().add("troubleshoot-stack-heading");
            SettingsRows.wrapping(heading);
            return heading;
        }

        /**
         * Fills in the technical report, or takes it off the screen where there is none.
         *
         * @param detail {@link Detail} the report, or null where no pass has produced one
         * @param presenter {@link TroubleshootPresenter} hands over the text to copy
         */
        private void drawDetail(final @Nullable Detail detail,
                                final TroubleshootPresenter presenter) {
            this.detail.setVisible(detail != null);
            this.detail.setManaged(detail != null);
            if (detail == null) {
                return;
            }
            this.detailToggle.setText(detail.label());
            SettingsRows.pointing(this.detailToggle, this.unfolded.contains(this.detail));
            this.detailCopy.setText(presenter.detailCopied() ? detail.copied() : detail.copy());
            this.detailCopy.setOnAction(_ -> {
                final String text = presenter.detail();
                if (text != null) {
                    CopyableTrace.putOnTheClipboard(text);
                    this.detailCopy.setText(detail.copied());
                }
            });
            this.trace.setText(detail.text());
            this.fold.to(this.unfolded.contains(this.detail));
        }

        /**
         * Replaces the buttons acting on the whole run.
         *
         * @param actions a {@link List} of {@link Action} the buttons
         * @param presenter {@link TroubleshootPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         */
        private void drawActions(final List<Action> actions,
                                 final TroubleshootPresenter presenter, final Runnable redraw) {
            final List<Node> buttons = new ArrayList<>();
            actions.forEach(action -> buttons.add(SettingsRows.actionButton(action.id(),
                    action.label(), action.leading(), action.confirm(), () -> {
                        presenter.press(action);
                        redraw.run();
                    })));
            this.actions.getChildren().setAll(buttons);
            this.actions.setVisible(!actions.isEmpty());
            this.actions.setManaged(!actions.isEmpty());
        }

        /**
         * One problem's row.
         *
         * @param problem {@link Problem} what the row says
         * @param presenter {@link TroubleshootPresenter} takes a press on its buttons
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Node} the row
         */
        private static Node row(final Problem problem, final TroubleshootPresenter presenter,
                                final Runnable redraw) {
            final var lines = new VBox();
            lines.getStyleClass().add("runs-card-lines");
            addIfPresent(lines, problem.problem(), "runs-card-headline");
            addIfPresent(lines, problem.about(), "runs-card-detail");

            final var row = new VBox(lines);
            row.setId(problem.id());
            row.getStyleClass().add("troubleshoot-problem");
            if (problem.options().isEmpty()) {
                return row;
            }
            final List<Node> buttons = new ArrayList<>();
            buttons.add(spacer());
            problem.options().forEach(option ->
                    buttons.add(optionButton(problem, option, presenter, redraw)));
            final var offers = new HBox(buttons.toArray(new Node[0]));
            offers.setAlignment(Pos.CENTER_RIGHT);
            offers.getStyleClass().add("run-start-row");
            row.getChildren().add(offers);
            return row;
        }

        /**
         * One of a row's answers.
         *
         * @param problem {@link Problem} the row it belongs to
         * @param option {@link Option} the answer it gives
         * @param presenter {@link TroubleshootPresenter} takes the press
         * @param redraw {@link Runnable} draws the screen again once it has been told
         * @return {@link Button} the button
         */
        private static Button optionButton(final Problem problem, final Option option,
                                           final TroubleshootPresenter presenter,
                                           final Runnable redraw) {
            // Off the thread that paints, because answering reads the run's own files again.
            return SettingsRows.actionButton(option.id(), option.label(), option.leading(),
                    option.confirm(), () -> Thread.ofVirtual().start(() -> {
                        presenter.press(problem, option);
                        Platform.runLater(redraw);
                    }));
        }

        /**
         * Adds a line to a row, where there is one to add.
         *
         * @param into {@link VBox} the row's lines
         * @param value what the line reads, or null where the row has no such line
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
