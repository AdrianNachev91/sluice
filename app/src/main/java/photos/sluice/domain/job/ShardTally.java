package photos.sluice.domain.job;

import photos.sluice.domain.cull.ShardValidator;

/**
 * A waiting cull's shard progress, matching the waiting card's own present/valid/total wording.
 *
 * <p>{@code present} counts every {@code decisions-NNN.json} file found in the prep dir, whether
 * or not it parses.
 *
 * <p>{@code valid} narrows that to shards that also pass {@link ShardValidator}.
 *
 * <p>{@code present} can exceed {@code valid} when a shard is mid-write or has a contract
 * violation. Neither can exceed {@code total}, the prep dir's own montage count.
 */
public record ShardTally(int present, int valid, int total) {

    /**
     * Validates the tally invariants: valid never exceeds present, present never exceeds total.
     *
     * @param present int shard files found in the prep dir
     * @param valid int present shards that also pass validation
     * @param total int the prep dir's own expected montage count
     */
    public ShardTally {
        if (valid < 0 || total < 0 || present > total || valid > present) {
            throw new IllegalArgumentException(
                    "require 0 <= valid <= present <= total: present=%d, valid=%d, total=%d"
                            .formatted(present, valid, total));
        }
    }
}
