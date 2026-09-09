package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells whoever is listening that a run may have moved when nobody pressed anything.
 *
 * <p>A watch can finish a run minutes after the reader last touched anything. A screen showing that
 * run has no other way to learn it is out of date. Everything else that moves a run follows a press,
 * and whoever took the press already knows.
 *
 * <p>Says only that something moved, never what. A listener reads the runs again and works out the
 * rest, which is the same reading it takes when a reader opens the screen. Carrying the change
 * itself would mean a second description of a run alongside the one on disk.
 *
 * <p>Fired on whatever thread caused the change, which may well be the one that paints. A watcher
 * announces from its own polling thread; a startup scan arming one announces from the thread that
 * boots. So a listener marshals for itself and does its own work somewhere else, and one that reads
 * the runs folder had better not do it here.
 *
 * <p>A listener that throws a {@link RuntimeException} is logged and the rest still run. One screen
 * failing to redraw must not stop another from hearing, and a watcher's thread has nothing above it
 * to catch anything. An {@link Error} is left alone, since nothing here can carry on after one.
 */
public final class RunChanges {

    private static final Logger log = LoggerFactory.getLogger(RunChanges.class);

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Asks to be told when a run may have moved on its own.
     *
     * @param listener {@link Runnable} what to run, on whatever thread caused the change. That can
     *     be the one that paints, so it marshals for itself
     */
    public void onMoved(final Runnable listener) {
        this.listeners.add(listener);
    }

    /**
     * Broadcasts to every listener that a run may have moved.
     */
    void changed() {
        this.listeners.forEach(RunChanges::announce);
    }

    /**
     * Announces to one listener, keeping its failure to itself.
     *
     * @param listener {@link Runnable} the listener to announce to
     */
    private static void announce(final Runnable listener) {
        try {
            listener.run();
        } catch (final RuntimeException e) {
            log.warn("A listener failed on being told a run moved", e);
        }
    }
}
