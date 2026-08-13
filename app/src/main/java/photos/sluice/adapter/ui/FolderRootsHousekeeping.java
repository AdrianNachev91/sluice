package photos.sluice.adapter.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.FolderRootsChangeListener;
import photos.sluice.application.service.Pipeline;

/**
 * Redoes the desktop's housekeeping after a save moves a folder root.
 *
 * <p>{@link StartupSequence} does this once, for the roots the app booted on. Two saves leave that
 * run stale. One moves a root out from under it. The other completes first-run configuration on an
 * install that had no root to claim at boot. The startup sequence returned before doing any of it
 * there, so without this the whole session goes without.
 *
 * <p>Retiring first is the load-bearing half of the order. Arming runs against the new roots, so a
 * retire after it would kill the watchers it had just armed.
 *
 * <p>The retention sweep {@link StartupSequence} runs alongside these two is deliberately absent.
 * It only ever deletes what is already 30 days past use, nothing waits on it, and the next launch
 * collects whatever a session skipped. Against that, it walks two directory trees while no job can
 * start, which is the most expensive thing this method could hold that lock across for the least
 * that anybody gains.
 *
 * <p>Only a working-root move retires anything. Watchers poll prep dirs under that root, so that is
 * the one move which leaves them all pointing outside it. A library or inbox move leaves every prep
 * dir where it was, and retiring there would silently switch off a watch the user turned on by hand
 * for a single run. Nothing would switch it back: the re-arm below only covers runs the configured
 * mode would have armed anyway.
 *
 * <p>Every failure is logged and swallowed, including a refused retire. The save that triggered
 * this has already reached disk, so a throw would report a successful save as a failed one. The
 * likeliest failure is a root that saved and is not usable, which refuses at the facade. A save
 * naming no working root at all reaches here holding no claim, since that release happens upstream.
 */
@Component
@Profile("!cli")
public class FolderRootsHousekeeping implements FolderRootsChangeListener {

    private static final Logger log = LoggerFactory.getLogger(FolderRootsHousekeeping.class);

    private final Pipeline pipeline;

    /**
     * Creates the housekeeping listener.
     *
     * @param pipeline {@link Pipeline} the facade carrying both housekeeping steps
     */
    public FolderRootsHousekeeping(final Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Retires the watchers a working-root move stranded, then arms against the new roots.
     *
     * <p>Catches {@link Throwable} rather than {@link RuntimeException}. This walks directory trees,
     * which is the work {@code JobRunner} already names as the place a stack overflow or an
     * out-of-memory surfaces. Only that hazard is borrowed from there: that class forwards what it
     * catches, while this discards it behind a log line, because everything durable landed before
     * this method ran. An {@link Error} escaping would report a save that plainly succeeded as a
     * failed one, and there is nothing left to repair by reporting it.
     *
     * @param workingRootMoved boolean whether the save moved the working root itself
     */
    @Override
    public void folderRootsChanged(final boolean workingRootMoved) {
        try {
            if (workingRootMoved) {
                this.pipeline.stopAllWatching();
            }
            this.pipeline.armWatchesForResumableRuns();
        } catch (final Throwable t) {
            log.warn("Housekeeping for the new folder roots did not complete", t);
        }
    }
}
