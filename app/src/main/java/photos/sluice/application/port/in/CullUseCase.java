package photos.sluice.application.port.in;

import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.job.WaitingCullJob;

import java.nio.file.Path;
import java.util.List;

public interface CullUseCase {

    // Builds montages for scope, dispatches them to the configured provider, and applies on the
    // spot when a complete shard set comes back from that same call.
    CullJobOutcome cull(CullScope scope);

    // Every cull still waiting on an external agent's shards, across this and prior app runs -
    // read live off disk, never a persisted list.
    List<WaitingCullJob> waitingJobs();

    // Re-validates prepDir's shards and applies if the set is now complete or allowPartial waives
    // what's still missing; returns Waiting again, with an updated tally, if problems remain.
    CullJobOutcome resume(Path prepDir, boolean allowPartial);
}
