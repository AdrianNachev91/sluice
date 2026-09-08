package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import photos.sluice.application.port.in.CullJobOutcome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells whoever is listening that a sift has continued with nobody pressing anything.
 *
 * <p>Apart from {@link RunChanges}, which says only that a run moved and leaves a listener to read
 * the runs again. This carries the job itself, which no reading of disk can hand back.
 *
 * <p>What announces here is a watcher's own resume. A job a screen started is already that
 * screen's, and so are the jobs behind a troubleshoot, a discard or a purge.
 *
 * <p>Fired on the watcher's polling thread, so a listener marshals for itself.
 *
 * <p>A listener that throws a {@link RuntimeException} is logged and the rest still run. One
 * failing to react must not stop another from hearing, and the polling thread has nothing above it
 * to catch anything. An {@link Error} is left alone, since nothing here can carry on after one.
 */
public final class AutoResumedSifts {

    private static final Logger log = LoggerFactory.getLogger(AutoResumedSifts.class);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Asks to be told when a sift has continued on its own.
     *
     * @param listener {@link Listener} what to run, on the watcher's polling thread
     */
    public void onResumed(final Listener listener) {
        this.listeners.add(listener);
    }

    /**
     * Broadcasts to every listener that a sift has continued on its own.
     *
     * @param scope {@link String} what that sift covers, as its own run is named
     * @param job a {@link JobHandle} of {@link CullJobOutcome} the job now running
     */
    void resumed(final String scope, final JobHandle<CullJobOutcome> job) {
        this.listeners.forEach(listener -> announce(listener, scope, job));
    }

    /**
     * Announces to one listener, keeping its failure to itself.
     *
     * @param listener {@link Listener} the listener to announce to
     * @param scope {@link String} what that sift covers
     * @param job a {@link JobHandle} of {@link CullJobOutcome} the job now running
     */
    private static void announce(final Listener listener, final String scope,
                                 final JobHandle<CullJobOutcome> job) {
        try {
            listener.resumed(scope, job);
        } catch (final RuntimeException e) {
            log.warn("A listener failed on being told a sift had continued on its own", e);
        }
    }

    /**
     * What a caller registers to hear about a sift that continued on its own.
     */
    @FunctionalInterface
    public interface Listener {

        /**
         * Takes one such sift.
         *
         * @param scope {@link String} what it covers, as its own run is named
         * @param job a {@link JobHandle} of {@link CullJobOutcome} the job now running
         */
        void resumed(String scope, JobHandle<CullJobOutcome> job);
    }
}
