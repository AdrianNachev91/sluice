package photos.sluice.application.port.in;

import photos.sluice.domain.sift.SiftRuns;
import photos.sluice.domain.sift.SiftScope;

import java.nio.file.Path;

/**
 * The use case for running the vision sift over sorted media, including waiting on and resuming
 * jobs that pause for an external agent's shards. A caller drives the whole sift lifecycle
 * through this interface: starting a run, listing what is on disk, and resuming a prep dir.
 */
public interface SiftUseCase {

    /**
     * Builds montages for scope, dispatches them to the configured provider, and applies on the
     * spot when a complete shard set comes back from that same call.
     *
     * @param scope {@link SiftScope} which media to sift
     * @return {@link SiftJobOutcome} the outcome of this sift attempt
     */
    SiftJobOutcome sift(SiftScope scope);

    /**
     * Every sift run currently on disk, across this and prior app runs, each one diagnosed. Read
     * live by enumerating the sift-prep root, never from a persisted list. A run whose own index
     * cannot be read is listed too, since that is the one a caller most needs to show. A root
     * nobody could read at all is its own answer, never an empty list.
     *
     * @return {@link SiftRuns} every run found, diagnosed and ordered by scope, or that the root
     *     could not be read
     */
    // Nothing invokes this method through the port yet.
    @SuppressWarnings("unused")
    SiftRuns siftRuns();

    /**
     * Picks a paused run back up. A montage still missing its shard sends the run back to the
     * configured provider, unless allowPartial waives it. A full shard set goes straight to apply.
     * The outcome then says what happened: applied, waiting on shards still to come, or blocked on
     * problems apply refused to carry out.
     *
     * @param prepDir {@link Path} the prep directory to re-validate
     * @param allowPartial boolean whether missing shards may be waived
     * @return {@link SiftJobOutcome} the outcome of this resume attempt
     */
    SiftJobOutcome resume(Path prepDir, boolean allowPartial);
}
