package photos.sluice.domain.cull;

import java.nio.file.Path;
import java.util.List;

/**
 * What a sweep of the sift-prep root found: the runs under it, or that it could not be read at all.
 *
 * <p>Refusing is the safe direction for every caller, the same direction an unreadable scope's own
 * occupancy check takes. Nothing has been established here. Treating an unknown as nothing there is
 * what lets a run be overwritten, or a library be moved out from under one.
 */
public sealed interface CullRuns {

    /**
     * The root was read, and these are the runs in it.
     *
     * @param runs a {@link List} of {@link CullRunSummary} every run found, diagnosed, ordered by
     *     scope. Empty means the root genuinely holds none
     */
    record Listed(List<CullRunSummary> runs) implements CullRuns {

        /**
         * Defensively copies the mutable list.
         *
         * @param runs a {@link List} of {@link CullRunSummary} the runs found
         */
        public Listed {
            runs = List.copyOf(runs);
        }
    }

    /**
     * The root itself could not be read, so what is under it is unknown.
     *
     * @param root {@link Path} the sift-prep root that could not be listed
     */
    record Unlistable(Path root) implements CullRuns {
    }
}
