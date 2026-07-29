package photos.sluice.domain.cull;

import java.util.List;
import java.util.Map;

// Two distinct uses, two distinct scopes. As ApplyEngine.apply()'s return value, every count
// reflects only what THIS run itself moved. A decision already carried out by an earlier, crashed
// run (confirmed done rather than reprocessed) is not counted again. That lets a caller report
// "what did this invocation just do". As the summary embedded in the merged decisions.json, the
// counts instead cover every decision in that same file's decisions array. This run's and every
// prior run's decisions count alike. Otherwise the persisted summary would silently drift from the
// array sitting right next to it. reviewed is the prep directory's own fixed count
// (PrepDir.photos()), carried through for the merged record either way. Unlike
// byCategory/nearDupGroups/nearDupRejects, it doesn't shrink when a prior, crashed run already
// carried some of it out. It describes the prep dir's scope, not this run's own actions.
// unreviewable starts from that same fixed scope (PrepDir.unreviewable().size()) but can shrink.
// A file the disposition ledger resolved TRUST_DECISION is no longer counted as unreviewable at
// all (see ApplyPlanner.resolvedUnreviewable()).
// heals lists every path ShardValidator auto-corrected via a unique sidecar basename, for a caller
// to surface as non-fatal warnings.
public record ApplyReport(
        int reviewed,
        Map<String, Integer> byCategory,
        int unreviewable,
        int nearDupGroups,
        int nearDupRejects,
        List<String> heals) {

    /**
     * Defensively copies the mutable collection fields.
     *
     * @param reviewed int count of decisions this run acted on
     * @param byCategory a {@link Map} of {@link String} to {@link Integer} count of files routed per category
     * @param unreviewable int count of files that could not be judged, minus any TRUST_DECISION-resolved
     * @param nearDupGroups int count of near-duplicate groups resolved
     * @param nearDupRejects int count of near-duplicate rejects moved
     * @param heals a {@link List} of {@link String} paths auto-corrected via a unique sidecar basename
     */
    public ApplyReport {
        byCategory = Map.copyOf(byCategory);
        heals = List.copyOf(heals);
    }
}
