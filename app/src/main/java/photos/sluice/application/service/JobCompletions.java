package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells whoever is listening that the job that was running has finished.
 *
 * <p>A listener that throws a {@link RuntimeException} is logged and the rest still run. One
 * failing to react must not stop another from hearing. An {@link Error} is left alone, since
 * nothing here can carry on after one.
 */
final class JobCompletions {

    private static final Logger log = LoggerFactory.getLogger(JobCompletions.class);

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Asks to be told when a running job has finished.
     *
     * @param listener {@link Runnable} what to run, on whichever thread announces
     */
    void onFinished(final Runnable listener) {
        this.listeners.add(listener);
    }

    /**
     * Broadcasts to every listener that a job has finished.
     */
    void finished() {
        this.listeners.forEach(JobCompletions::announce);
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
            log.warn("A listener failed on being told a job had finished", e);
        }
    }
}
