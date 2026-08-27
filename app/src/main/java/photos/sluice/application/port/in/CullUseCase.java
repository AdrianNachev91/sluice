package photos.sluice.application.port.in;

import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.CullScope;

import java.nio.file.Path;

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
     * live by enumerating the sift-prep root, never from a persisted list. A run whose own index
     * cannot be read is listed too, since that is the one a caller most needs to show. A root
     * nobody could read at all is its own answer, never an empty list.
     *
     * @return {@link CullRuns} every run found, diagnosed and ordered by scope, or that the root
     *     could not be read
     */
    // No UI consumes this port yet, so no caller currently invokes this method through it.
    @SuppressWarnings("unused")
    CullRuns cullRuns();

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
