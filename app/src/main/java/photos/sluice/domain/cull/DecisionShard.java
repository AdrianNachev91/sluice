package photos.sluice.domain.cull;

import java.util.List;
import java.util.Objects;

/**
 * The vision step's decisions for one montage, mirroring an on-disk {@code decisions-NNN.json}
 * file: the montage id it covers, plus every non-keep decision within it.
 *
 * <p>An empty decisions list is valid and meaningful. It marks a montage as reviewed and held
 * entirely as keeps, distinct from a montage never processed, whose shard is simply absent.
 * {@code montage} must equal the shard filename's own id, a rule {@link ShardValidator} checks.
 */
public record DecisionShard(String montage, List<Decision> decisions) {

    /**
     * Validates montage is present and defensively copies decisions.
     *
     * @param montage {@link String} the montage id this shard covers
     * @param decisions a {@link List} of {@link Decision} the non-keep decisions for this montage
     */
    public DecisionShard {
        Objects.requireNonNull(montage, "montage");
        decisions = List.copyOf(decisions);
    }
}
