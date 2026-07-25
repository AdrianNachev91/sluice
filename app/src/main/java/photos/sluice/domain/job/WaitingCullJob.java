package photos.sluice.domain.job;

import java.nio.file.Path;
import java.time.Instant;

// A cull run still waiting on an external agent to drop the rest of its decision shards. There is
// no persistent job store. This is derived live by scanning the cull-prep log tree for a prep dir
// with an index.json but no merged decisions.json yet. prepDir itself is therefore the job's
// identity, passed back to CullUseCase.resume(). since is that prep dir's own mtime, not a stored
// creation timestamp.
public record WaitingCullJob(String scope, Path prepDir, ShardTally shards, Instant since) {
}
