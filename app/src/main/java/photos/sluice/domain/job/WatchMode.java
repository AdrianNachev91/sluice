package photos.sluice.domain.job;

/**
 * How a waiting cull (see {@link WaitingCullJob}) gets resumed once the external agent's shards
 * land.
 *
 * <p>{@code WATCH}, the default, polls the prep dir and auto-resumes the moment every expected
 * shard has arrived. A provider that calls a model applies its answers without asking anyone, and
 * an external agent's are no different once they are all in. Whether those shards hold anything
 * usable is decided by the resume itself, not by the poll.
 *
 * <p>{@code MANUAL} requires an explicit resume, for anyone who wants to read what their agent
 * produced before it is applied to their photos.
 */
public enum WatchMode {
    MANUAL,
    WATCH
}
