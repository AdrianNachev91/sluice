package photos.sluice.domain.cull;

import java.util.List;
import java.util.Map;

// Outcome summary of one ApplyEngine run. Every count reflects only what THIS run itself moved. A
// decision already carried out by an earlier, crashed run (skipped via applied.log) is not counted
// again, even though it still appears in the merged decisions.json this run writes. reviewed is the
// prep directory's own reviewable photo count (PrepDir.photos()), carried through for the merged
// record. heals lists every path ShardValidator auto-corrected via a unique sidecar basename, for a
// caller to surface as non-fatal warnings.
public record ApplyReport(
        int reviewed,
        Map<String, Integer> byCategory,
        int unreviewable,
        int nearDupGroups,
        int nearDupRejects,
        List<String> heals) {

    public ApplyReport {
        byCategory = Map.copyOf(byCategory);
        heals = List.copyOf(heals);
    }
}
