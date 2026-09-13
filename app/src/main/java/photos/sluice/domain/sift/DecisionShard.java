package photos.sluice.domain.sift;

import java.util.List;
import java.util.Objects;

/**
 * The vision step's verdicts for one montage, mirroring an on-disk {@code decisions-NNN.json} file:
 * the montage id it covers, plus one verdict for every photo the montage showed.
 *
 * <p>An empty list means nobody judged this sheet, and {@link ShardValidator} refuses it. A sheet
 * held entirely as keepers is a full list of {@link Verdict.Keep}.
 *
 * <p>{@code montage} must equal the shard filename's own id, a rule {@link ShardValidator} checks.
 */
public record DecisionShard(String montage, List<Verdict> verdicts) {

    /**
     * Validates montage is present and defensively copies verdicts.
     *
     * @param montage {@link String} the montage id this shard covers
     * @param verdicts a {@link List} of {@link Verdict} one verdict per photo the montage showed
     */
    public DecisionShard {
        Objects.requireNonNull(montage, "montage");
        verdicts = List.copyOf(verdicts);
    }
}
