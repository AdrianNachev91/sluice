package photos.sluice.domain.job;

/**
 * How a waiting cull (see {@link WaitingCullJob}) gets resumed once the external agent's shards
 * land.
 *
 * <p>{@code MANUAL}, the default, requires an explicit resume. The external agent is human-paced,
 * and a job that silently auto-applies the instant the last shard lands removes the natural review
 * pause a human-driven flow depends on.
 *
 * <p>{@code WATCH} additionally polls the prep dir and auto-resumes the moment every expected
 * shard has arrived. Whether those shards hold anything usable is decided by the resume itself,
 * not by the poll.
 */
public enum WatchMode {
    MANUAL,
    WATCH
}
