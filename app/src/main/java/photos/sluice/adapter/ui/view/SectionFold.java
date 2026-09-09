package photos.sluice.adapter.ui.view;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A part of a page that opens and shuts by travelling, and takes the scroll pane with it.
 *
 * <p>Holding the height rather than the visibility is what lets it travel. A box asks for its
 * computed height as a minimum, and a minimum outranks a maximum, so the floor comes off before the
 * ceiling means anything.
 */
final class SectionFold {

    // How long a section takes to open or shut. One figure across every fold, so two of them on
    // different screens do not read as different controls.
    private static final Duration TRAVEL = Duration.millis(160);

    private final Region travelling;

    private final Node section;

    // Null on a page that does not scroll. The travel is the same there. What it loses is the pane
    // moving to bring the opened section into view, which such a page cannot do anyway.
    private final @Nullable ScrollPane scroll;

    // Held so a press arriving mid-travel can stop what is already running. Null while nothing is.
    private @Nullable Timeline running;

    private boolean open;

    /**
     * Creates the fold, shut.
     *
     * @param travelling {@link Region} what changes height, which is the fold's contents rather
     *     than the control that opens them
     * @param section {@link Node} the whole of it, control included, whose foot has to end on
     *     screen once it opens
     * @param scroll {@link ScrollPane} the pane the page sits in, or null where it does not scroll
     */
    SectionFold(final Region travelling, final Node section, final @Nullable ScrollPane scroll) {
        this.travelling = travelling;
        this.section = section;
        this.scroll = scroll;
        settle(travelling, false);
    }

    /**
     * Where the fold rests, once nothing is moving it.
     *
     * @param contents {@link Region} the fold's contents
     * @param open boolean whether they are showing
     */
    static void settle(final Region contents, final boolean open) {
        contents.setMinHeight(0);
        contents.setMaxHeight(open ? Region.USE_COMPUTED_SIZE : 0);
        contents.setOpacity(open ? 1 : 0);
        contents.setVisible(open);
    }

    /**
     * Opens or shuts it, travelling rather than jumping.
     *
     * <p>A fold nobody can watch takes its end state and starts no travel, so a section that opens
     * off screen is already open the moment anyone sees it.
     *
     * @param wanted boolean whether the contents should end up showing
     */
    void to(final boolean wanted) {
        // A fold already going where it is being asked to go has nothing to do. Without this, every
        // redraw travels again, and every travel carries the pane with it. A press anywhere on the
        // page would then throw a reader who had scrolled away.
        if (this.open == wanted) {
            return;
        }
        this.open = wanted;
        if (this.running != null) {
            this.running.stop();
            this.running = null;
        }
        if (this.travelling.getScene() == null || this.travelling.getWidth() <= 0) {
            settle(this.travelling, wanted);
            return;
        }
        // The height it is about to travel to is read off a laid-out page. A press arrives between
        // pulses, so the layout that reading wants can still be pending.
        layOut(this.scroll == null ? this.section : this.scroll.getContent());
        this.travelling.setVisible(true);
        this.travelling.setMinHeight(0);
        this.travelling.setMaxHeight(this.travelling.getHeight());
        final double to = wanted ? this.travelling.prefHeight(this.travelling.getWidth()) : 0;
        final List<KeyValue> frames = new ArrayList<>(List.of(
                new KeyValue(this.travelling.maxHeightProperty(), to, Interpolator.EASE_BOTH),
                new KeyValue(this.travelling.opacityProperty(), wanted ? 1 : 0,
                        Interpolator.EASE_BOTH)));
        final KeyValue following = wanted
                ? this.following(to - this.travelling.getHeight())
                : null;
        if (following != null) {
            frames.add(following);
        }
        final var travel = new Timeline(new KeyFrame(TRAVEL, frames.toArray(new KeyValue[0])));
        travel.setOnFinished(_ -> {
            this.running = null;
            settle(this.travelling, wanted);
            if (wanted) {
                Platform.runLater(this::arrive);
            }
        });
        this.running = travel;
        travel.play();
    }

    /**
     * Where the pane has to end up for the opening section to be on screen, as a frame it can
     * travel through.
     *
     * <p>Without it the pane holds a fraction of a page that is growing under it, so what is
     * already on screen slides while the section opens. The reader sees that as the page jumping
     * rather than as the section arriving.
     *
     * <p>Only opening asks for this. A section closing is either in front of the reader already or
     * one they never opened, and travelling toward either shows them nothing.
     *
     * @param growth double how much taller the page is about to be
     * @return {@link KeyValue} the frame, or null where the page cannot scroll at all
     */
    private @Nullable KeyValue following(final double growth) {
        if (this.scroll == null
                || !(this.scroll.getContent() instanceof final Parent laidOut)) {
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
                .sceneToLocal(this.section.localToScene(this.section.getLayoutBounds()))
                .getMaxY() + growth;
        return new KeyValue(this.scroll.vvalueProperty(),
                Math.clamp(Math.max(top, foot - viewport), 0, scrollable)
                        / scrollable * this.scroll.getVmax(), Interpolator.EASE_BOTH);
    }

    /**
     * Moves the pane so the section that just opened ends on screen.
     *
     * <p>Has to run a pulse after the travel ends. Lifting a box's ceiling only gives it its real
     * height on the layout pass after, so a reading taken as the travel ends answers for the page
     * as it stood before the section grew.
     */
    private void arrive() {
        // The fold can have been shut again in the pulse this waited out, and travelling toward a
        // section that is closing shows the reader nothing.
        if (!this.open || this.scroll == null
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
                .sceneToLocal(this.section.localToScene(this.section.getLayoutBounds()))
                .getMaxY();
        final double top = this.scroll.getVvalue() / this.scroll.getVmax() * scrollable;
        this.scroll.setVvalue(Math.clamp(Math.max(top, foot - viewport), 0, scrollable)
                / scrollable * this.scroll.getVmax());
    }

    /**
     * Brings a subtree's layout up to date, so what is read off it is where things end up.
     *
     * @param node {@link Node} the subtree, ignored where it is not one
     */
    private static void layOut(final @Nullable Node node) {
        if (node instanceof final Parent parent) {
            parent.applyCss();
            parent.layout();
        }
    }
}
