package photos.sluice.adapter.ui.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunProgressView;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the dashboard shows while a run works: which phase it is on, how far through, and the one
 * button that asks it to stop.
 *
 * <p>The bars are the one part rebuilt rather than filled. How many there are is not known until
 * the job reports them, and a phase arrives already carrying its first counts. Everything else is
 * built once and written onto, the way the launcher's controls are.
 *
 * <p>Cancel sits outside the box the bars are in. A rebuild there must not be able to destroy the
 * control somebody is reaching for.
 */
final class RunProgressPane {

    private RunProgressPane() {
    }

    /**
     * Builds the progress area, ready to sit in the dashboard's swapping region.
     *
     * @param presenter {@link RunLauncherPresenter} takes the press on Cancel
     * @param redraw {@link Runnable} draws the dashboard again once the presenter has been told
     * @return {@link Mounted} the area and the way to fill it in
     */
    static Mounted mount(final RunLauncherPresenter presenter, final Runnable redraw) {
        final var heading = new Label();
        heading.setId("run-progress-heading");
        heading.getStyleClass().add("pane-heading");

        final var scope = new Label();
        scope.setId("run-progress-scope");
        SettingsRows.wrapping(scope);
        scope.getStyleClass().add("run-progress-scope");

        final var bars = new VBox();
        bars.setId("run-progress-bars");
        bars.getStyleClass().add("run-progress-bars");

        final var waiting = SettingsRows.emptyHelpLine("run-progress-waiting");
        final var cancelling = SettingsRows.emptyHelpLine("run-progress-cancelling");

        final var cancel = new Button();
        cancel.setId("run-cancel");
        cancel.getStyleClass().add("run-cancel");
        // Told, then drawn. A sift can be a minute from noticing, and a button that stayed live
        // and silent for that long reads as a press that missed.
        cancel.setOnAction(_ -> {
            presenter.cancel();
            redraw.run();
        });
        final var cancelRow = new HBox(cancel);
        cancelRow.getStyleClass().add("run-start-row");
        cancelRow.setAlignment(Pos.CENTER_RIGHT);

        final var body = new VBox(bars, waiting);
        body.getStyleClass().add("run-progress-body");
        // Everything sits under the bars rather than the row being pushed to the foot. A run has
        // little to show. Stretching the page would put the bars at one end of a wide screen and
        // the way to stop them at the other.
        final var page = new VBox(heading, scope, body, cancelling, cancelRow);
        page.setId("run-progress");
        page.getStyleClass().add("run-progress");

        return new Mounted(page, view -> fill(view, heading, scope, bars, waiting, cancelling, cancel));
    }

    /**
     * Puts everything the presenter says onto the area.
     *
     * @param view {@link RunProgressView} what the area shows now
     * @param heading {@link Label} what is running
     * @param scope {@link Label} what this run covers
     * @param bars {@link VBox} the phase bars
     * @param waiting {@link Label} what to say before the first phase arrives
     * @param cancelling {@link Label} what to say once a stop has been asked for
     * @param cancel {@link Button} the button that asks
     */
    private static void fill(final RunProgressView view, final Label heading, final Label scope,
                             final VBox bars, final Label waiting, final Label cancelling,
                             final Button cancel) {
        heading.setText(view.heading());
        scope.setText(view.scope());
        drawBars(view.phases(), bars, view.reservedBars());
        waiting.setText(SettingsRows.orNothing(view.waiting()));
        cancelling.setText(SettingsRows.orNothing(view.cancelling()));
        cancel.setText(view.cancelLabel());
        cancel.setDisable(!view.cancelPressable());
    }

    /**
     * Draws one row per phase the job has reported.
     *
     * <p>Rebuilt whole rather than matched up row by row. A phase list only ever grows during a
     * run, and it is replaced outright when the next run begins. Matching would be arithmetic in a
     * class whose job is to have no arithmetic in it.
     *
     * @param phases a {@link List} of {@link PhaseBar} the phases, oldest first
     * @param bars {@link VBox} the box to draw them in
     */
    private static void drawBars(final List<PhaseBar> phases, final VBox bars, final int reserved) {
        final List<Node> rows = new ArrayList<>();
        phases.forEach(phase -> rows.add(barRow(phase)));
        // Padded out to the room the mode asked for, with rows built the same way and then hidden.
        // A hidden row still takes its own height, so the controls below sit where they will sit
        // for the whole run. Measuring a height instead would have to guess at the font, and the
        // font is whatever the desktop says it is.
        while (rows.size() < reserved) {
            rows.add(reservedRow());
        }
        bars.getChildren().setAll(rows);
    }

    /**
     * A bar row that holds its place without drawing anything.
     *
     * @return {@link Node} a row the layout counts and the reader never sees
     */
    private static Node reservedRow() {
        final var row = barRow(new PhaseBar("", "", null, 0, true, true, false));
        row.setVisible(false);
        return row;
    }

    /**
     * One phase, as a name over a bar with its counts beside it.
     *
     * <p>A phase with no total runs the toolkit's own indeterminate animation rather than sitting
     * at zero. Zero is a claim that nothing has happened, and what is true is that nothing has been
     * counted. A phase the job has not reached sits at zero instead: nothing has happened there,
     * and an animation would say something is under way.
     *
     * @param phase {@link PhaseBar} the phase to draw
     * @return {@link Node} the row
     */
    private static Node barRow(final PhaseBar phase) {
        final var label = new Label(phase.label());
        label.getStyleClass().add("run-phase-label");
        final var counts = new Label(SettingsRows.orNothing(phase.counts()));
        counts.getStyleClass().add("run-phase-counts");
        final var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        final var top = new HBox(label, spacer, counts);
        top.setAlignment(Pos.CENTER_LEFT);

        final var bar = new ProgressBar();
        bar.setProgress(waitingBar(phase));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("run-phase-bar");

        final var row = new VBox(top, bar);
        row.setId(phase.id());
        row.getStyleClass().add("run-phase");
        // On the row rather than on the bar, because what each state changes is the label, and the
        // label is the bar's sibling rather than its child.
        if (phase.finished()) {
            row.getStyleClass().add("run-phase-done");
        }
        if (!phase.started()) {
            row.getStyleClass().add("run-phase-ahead");
        }
        return row;
    }

    /**
     * How full to draw one phase's bar.
     *
     * @param phase {@link PhaseBar} the phase to draw
     * @return double the fill, or the toolkit's indeterminate marker
     */
    private static double waitingBar(final PhaseBar phase) {
        if (!phase.started()) {
            return 0;
        }
        return phase.measured() ? phase.fraction() : ProgressBar.INDETERMINATE_PROGRESS;
    }

    /**
     * A built area and the way to fill it in.
     *
     * @param node {@link Node} the area itself
     * @param fill a {@link Consumer} of {@link RunProgressView} writes a view onto it
     */
    record Mounted(Node node, Consumer<RunProgressView> fill) {
    }
}
