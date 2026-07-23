package photos.sluice.domain.cull;

import java.util.List;
import java.util.Map;

// Two distinct uses, two distinct scopes. As ApplyEngine.apply()'s return value, every count
// reflects only what THIS run itself moved. A decision already carried out by an earlier, crashed
// run (skipped via applied.log) is not counted again, so a caller can report "what did this
// invocation just do". As the summary embedded in the merged decisions.json, the counts instead
// cover every decision in that same file's decisions array - this run's and every prior run's
// alike. Otherwise the persisted summary would silently drift from the array sitting right next to
// it. reviewed is the prep directory's own reviewable photo count (PrepDir.photos()), carried
// through for the merged record either way. heals lists every path ShardValidator auto-corrected
// via a unique sidecar basename, for a caller to surface as non-fatal warnings.
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
