package photos.sluice.domain.job;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A cull run still waiting on an external agent to drop the rest of its decision shards.
 *
 * <p>There is no persistent job store. This is derived live by scanning the sift-prep log tree for
 * a prep dir with an {@code index.json} but no merged {@code decisions.json} yet.
 *
 * <p>{@code prepDir} is therefore the job's own identity, passed back to
 * {@code CullUseCase.resume()}. {@code since} is that prep dir's own mtime, not a stored creation
 * timestamp.
 */
public record WaitingCullJob(String scope, Path prepDir, ShardTally shards, Instant since) {
}
