package photos.sluice.domain.sift;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code PrepDirDoctor.purgeCompleted()}'s outcome for one sweep of the sift-prep root.
 *
 * <p>purged names every scope whose completed run was hard-deleted this sweep. A scope is the prep
 * dir's own folder name. skipped names every other scope the sweep looked at, mapped to the state
 * that kept it. A completed run is the only state this manual, one-button purge ever touches.
 *
 * <p>unreadable names a scope the sweep could not finish reasoning about at all, mapped to why.
 * Either its own occupancy could not be read, or a delete attempt on it failed partway. Distinct
 * from skipped, whose state was diagnosed and simply was not COMPLETE.
 *
 * <p>unlistableRoot is the sweep that never started, and it is not the same report as a sweep that
 * found nothing. Both leave the other three empty, so without it the button answers "0 purged, 0
 * skipped" to a machine that could not look.
 */
public record PurgeReport(List<String> purged, Map<String, PrepDirHealth.State> skipped,
                          Map<String, String> unreadable, @Nullable Path unlistableRoot) {

    /**
     * Defensively copies the mutable collection fields.
     *
     * @param purged a {@link List} of {@link String} scope tags of completed runs deleted this sweep
     * @param skipped a {@link Map} of {@link String} to {@link PrepDirHealth.State} scope tags left untouched, with
     * the state that kept them
     * @param unreadable a {@link Map} of {@link String} to {@link String} scope tags the sweep could not finish
     * with, mapped to why
     * @param unlistableRoot {@link Path} the sift-prep root, where it could not be read at all, and
     * null for every sweep that ran
     */
    public PurgeReport {
        purged = List.copyOf(purged);
        skipped = Map.copyOf(skipped);
        unreadable = Map.copyOf(unreadable);
    }
}
