package photos.sluice.domain.job;

// A waiting cull's shard progress, matching the waiting card's own "present/valid/total" wording
// (docs/plans/2026-07-04-external-agent-interaction.md). present counts every decisions-NNN.json
// file found in the prep dir, whether or not it parses; valid narrows that to ones that also pass
// ShardValidator. present can exceed valid (a shard mid-write, or one with a contract violation);
// neither can exceed total, the prep dir's own montage count.
public record ShardTally(int present, int valid, int total) {

    public ShardTally {
        if (valid < 0 || total < 0 || present > total || valid > present) {
            throw new IllegalArgumentException(
                    "require 0 <= valid <= present <= total: present=%d, valid=%d, total=%d"
                            .formatted(present, valid, total));
        }
    }
}
