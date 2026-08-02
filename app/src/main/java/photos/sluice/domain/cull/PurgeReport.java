package photos.sluice.domain.cull;

import java.util.List;
import java.util.Map;

/**
 * {@code PrepDirDoctor.purgeCompleted()}'s outcome for one sweep of the cull-prep root. purged
 * names every scope (the prep dir's own folder name) whose completed run was hard-deleted this
 * sweep. skipped names every other scope the sweep looked at, mapped to the state that kept it -
 * a completed run is the only state this manual, one-button purge ever touches. unreadable names a
 * scope the sweep could not finish reasoning about at all, mapped to why: its own occupancy could
 * not be read, or a delete attempt on it failed partway. Distinct from skipped, whose state was
 * successfully diagnosed and simply was not COMPLETE.
 */
public record PurgeReport(List<String> purged, Map<String, PrepDirHealth.State> skipped,
                          Map<String, String> unreadable) {

    /**
     * Defensively copies the mutable collection fields.
     *
     * @param purged a {@link List} of {@link String} scope tags of completed runs deleted this sweep
     * @param skipped a {@link Map} of {@link String} to {@link PrepDirHealth.State} scope tags left untouched, with
     * the state that kept them
     * @param unreadable a {@link Map} of {@link String} to {@link String} scope tags the sweep could not finish
     * with, mapped to why
     */
    public PurgeReport {
        purged = List.copyOf(purged);
        skipped = Map.copyOf(skipped);
        unreadable = Map.copyOf(unreadable);
    }
}
