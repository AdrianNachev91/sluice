package photos.sluice.application.port.in;

import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullScope;

import java.nio.file.Path;
import java.util.List;

/**
 * The use case for running the vision cull over sorted media, including waiting on and resuming
 * jobs that pause for an external agent's shards. A caller drives the whole cull lifecycle
 * through this interface: starting a run, listing what is on disk, and resuming a prep dir.
 */
public interface CullUseCase {

    /**
     * Builds montages for scope, dispatches them to the configured provider, and applies on the
     * spot when a complete shard set comes back from that same call.
     *
     * @param scope {@link CullScope} which media to cull
     * @return {@link CullJobOutcome} the outcome of this cull attempt
     */
    CullJobOutcome cull(CullScope scope);

    /**
     * Every cull run currently on disk, across this and prior app runs, each one diagnosed. Read
     * live by enumerating the cull-prep root, never from a persisted list. A run whose own index
     * cannot be read is listed too, since that is the one a caller most needs to show.
     *
     * @return a {@link List} of {@link CullRunSummary} every run found, diagnosed, ordered by scope
     */
    // No UI consumes this port yet, so no caller currently invokes this method through it.
    @SuppressWarnings("unused")
    List<CullRunSummary> cullRuns();

    /**
     * Picks a paused run back up. A montage still missing its shard sends the run back to the
     * configured provider, unless allowPartial waives it. A full shard set goes straight to apply.
     * The outcome then says which of the three things happened: applied, waiting on shards still
     * to come, or blocked on problems apply refused to carry out.
     *
     * @param prepDir {@link Path} the prep directory to re-validate
     * @param allowPartial boolean whether missing shards may be waived
     * @return {@link CullJobOutcome} the outcome of this resume attempt
     */
    CullJobOutcome resume(Path prepDir, boolean allowPartial);
}
