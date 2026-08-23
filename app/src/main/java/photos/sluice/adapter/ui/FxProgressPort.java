package photos.sluice.adapter.ui;

import javafx.application.Platform;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ProgressPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The desktop's {@link ProgressPort}: it records what a running job reports and asks the screen to
 * redraw on the toolkit's own thread.
 *
 * <p>Events arrive on whichever thread the job runs on, and a JavaFX control may only be touched
 * from the application thread. So nothing here draws. It keeps a snapshot the screen can read, then
 * hands the redraw to the toolkit. That is the same division the presenters already use, where the
 * screen supplies the redraw and decides nothing about what it means.
 *
 * <p>The snapshot is replaced whole on every event rather than mutated in place. A reader on the
 * application thread therefore always sees one consistent list, never a phase half-written by the
 * job thread.
 *
 * <p>Constructing this starts no toolkit and needs none started. The constructor stores
 * {@link Platform#runLater} as a method reference without calling it, and calling it is what
 * requires a live toolkit. So a Spring context carrying this bean can be built in a process that
 * never shows a window.
 */
@Component
@Profile("!cli")
public class FxProgressPort implements ProgressPort {

    private final Consumer<Runnable> onFxThread;
    private final AtomicReference<List<ProgressPhase>> phases = new AtomicReference<>(List.of());
    // True from the moment a redraw is handed over until it starts running, so a burst of events
    // costs one redraw rather than one each. A sort ticks once per file. Without this a large one
    // hands the toolkit a task per photo, each rebuilding the whole progress area.
    private final AtomicBoolean redrawPending = new AtomicBoolean();
    // Assigned by whichever screen owns the progress area, and read when a redraw runs rather than
    // when it is handed over. Volatile so a job already running sees the assignment.
    private volatile Runnable repaint = () -> {};

    /**
     * The one Spring builds. With no constructor annotated and no single candidate to infer, Spring
     * falls back to the no-argument one, which is this.
     */
    public FxProgressPort() {
        this(Platform::runLater);
    }

    /**
     * Test seam: production wiring always goes through the public constructor above, which hands
     * every redraw to the toolkit. A test proving that events are marshalled rather than run where
     * they arrive passes a dispatcher it can inspect, and needs no toolkit at all.
     *
     * @param onFxThread a {@link Consumer} of {@link Runnable} how a redraw reaches the application
     *     thread
     */
    FxProgressPort(final Consumer<Runnable> onFxThread) {
        this.onFxThread = onFxThread;
    }

    /**
     * Tells this where to send a redraw once the snapshot has changed.
     *
     * <p>Whichever screen is registered when a redraw runs is the one that draws it, which is not
     * always the one that was registered when the event arrived.
     *
     * @param repaint {@link Runnable} what to run on the application thread once the snapshot has
     *     changed
     */
    public void setRepaint(final Runnable repaint) {
        this.repaint = repaint;
    }

    /**
     * What the job has reported so far, oldest phase first.
     *
     * @return a {@link List} of {@link ProgressPhase} the phases of the current job
     */
    public List<ProgressPhase> phases() {
        return this.phases.get();
    }

    /**
     * Drops everything reported so far, so the next job's phases stand alone.
     *
     * <p>Separate from starting a job because nothing here knows a job started. This port is told
     * about phases, and two jobs in a row report phases the same way. So the boundary has to be
     * drawn by a caller that knows where one job ended and the next began.
     */
    public void forgetPhases() {
        this.change(_ -> List.of());
    }

    /**
     * Records a phase beginning and redraws.
     *
     * @param phase {@link String} short human-readable label for the phase
     */
    @Override
    public void phaseStarted(final String phase) {
        this.change(before -> {
            final List<ProgressPhase> after = new ArrayList<>(before);
            after.add(new ProgressPhase(phase, 0, 0, false));
            return List.copyOf(after);
        });
    }

    /**
     * Records progress within the phase that reported it and redraws.
     *
     * @param phase {@link String} short human-readable label for the phase
     * @param current int units completed so far
     * @param total int total units in the phase
     */
    @Override
    public void tick(final String phase, final int current, final int total) {
        this.change(before -> replaceLast(before, phase,
                found -> new ProgressPhase(found.label(), current, total, found.finished())));
    }

    /**
     * Records a phase ending and redraws.
     *
     * @param phase {@link String} short human-readable label for the phase
     */
    @Override
    public void phaseFinished(final String phase) {
        this.change(before -> replaceLast(before, phase,
                found -> new ProgressPhase(found.label(), found.current(), found.total(), true)));
    }

    /**
     * Swaps the snapshot for a new one and asks the screen to redraw.
     *
     * <p>A job reports from its own thread while the screen clears the list from the application
     * thread. The swap reads and replaces in one step so neither can overwrite the other's work.
     *
     * <p>The swap comes first, and that order is load-bearing. A redraw handed over beforehand can
     * run against the list as it stood before the event that asked for it.
     *
     * @param next a {@link UnaryOperator} of {@link List} of {@link ProgressPhase} what the
     *     snapshot becomes
     */
    private void change(final UnaryOperator<List<ProgressPhase>> next) {
        this.phases.updateAndGet(next);
        if (this.redrawPending.compareAndSet(false, true)) {
            this.askForARedraw();
        }
    }

    /**
     * Hands one redraw to the application thread, and gives up rather than failing the job when
     * there is no toolkit to hand it to.
     *
     * <p>Reporting progress is something a job does on the way past. A job moving files must not
     * fail because the window it was describing has gone. So a refused hand-over ends here rather
     * than unwinding into the engine that reported the event.
     *
     * <p>The pending flag is cleared as the redraw starts rather than as it ends. An event arriving
     * while the screen is drawing then asks for another one, instead of being folded into a draw
     * that has already read the list.
     */
    private void askForARedraw() {
        try {
            this.onFxThread.accept(() -> {
                this.redrawPending.set(false);
                this.repaint.run();
            });
        } catch (final IllegalStateException noToolkitToDrawOn) {
            this.redrawPending.set(false);
        }
    }

    /**
     * Rewrites the most recent phase carrying this label, leaving the list alone when none does.
     *
     * <p>Matched by label from the end rather than by position, because the same label can appear
     * more than once in the list. This port is told about phases and never about jobs, so a second
     * job's phases follow the first job's until the screen clears them. Rewriting the last entry
     * blindly would credit a running phase's counts to whichever phase happened to be added last.
     *
     * <p>A label that was never started is ignored rather than added. The port's own contract is
     * that a phase is bracketed, so an unbracketed tick is a bug in the engine reporting it.
     * Adding a phase here would draw a bar for it and hide that bug. It would also draw one for a
     * late event from a job the screen has already cleared away.
     *
     * @param before a {@link List} of {@link ProgressPhase} the snapshot as it stands
     * @param label {@link String} the phase to rewrite
     * @param rewrite a {@link UnaryOperator} of {@link ProgressPhase} what it becomes
     * @return a {@link List} of {@link ProgressPhase} the new snapshot
     */
    private static List<ProgressPhase> replaceLast(final List<ProgressPhase> before, final String label,
                                                   final UnaryOperator<ProgressPhase> rewrite) {
        for (int i = before.size() - 1; i >= 0; i--) {
            if (before.get(i).label().equals(label)) {
                final List<ProgressPhase> after = new ArrayList<>(before);
                after.set(i, rewrite.apply(after.get(i)));
                return List.copyOf(after);
            }
        }
        return before;
    }
}
