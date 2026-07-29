package photos.sluice.application.port.in;

import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.util.List;

/**
 * The use case for running the vision cull over sorted media, including waiting on and resuming
 * jobs that pause for an external agent's shards. A caller drives the whole cull lifecycle
 * through this interface: starting a run, listing what is still waiting, and resuming a prep dir.
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
     * Every cull still waiting on an external agent's shards, across this and prior app runs -
     * read live off disk, never a persisted list.
     *
     * @return a {@link List} of {@link WaitingCullJob} the currently waiting cull jobs
     */
    // No UI consumes this port yet, so no caller currently invokes this method through it.
    @SuppressWarnings("unused")
    List<WaitingCullJob> waitingJobs();

    /**
     * Re-validates prepDir's shards and applies if the set is now complete or allowPartial waives
     * what's still missing; returns Waiting again, with an updated tally, if problems remain.
     *
     * @param prepDir {@link Path} the prep directory to re-validate
     * @param allowPartial boolean whether missing shards may be waived
     * @return {@link CullJobOutcome} the outcome of this resume attempt
     */
    CullJobOutcome resume(Path prepDir, boolean allowPartial);
}
