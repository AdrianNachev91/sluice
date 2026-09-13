package photos.sluice.domain.sift;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one apply run: how many files were routed, by what category, and how many
 * near-duplicate groups and rejects were resolved.
 *
 * <p>This shape serves two distinct uses with two distinct counting rules. As
 * {@code ApplyEngine.apply()}'s return value, every count reflects
 * only what this run itself moved. A decision already carried out by an earlier, crashed run
 * (confirmed done rather than reprocessed) is not counted again, so a caller can report what this
 * invocation just did. As the summary embedded in the merged {@code decisions.json}, the counts
 * instead cover every decision in that file's decisions array, this run's and every prior run's
 * alike. Otherwise the persisted summary would silently drift from the array sitting next to it.
 *
 * <p>{@code reviewed} is the prep directory's own fixed count ({@link PrepDir#photos()}), carried
 * through unchanged either way. Unlike {@code byCategory}/{@code nearDupGroups}/
 * {@code nearDupRejects}, it never shrinks when a prior, crashed run already carried some of it
 * out. It describes the prep dir's scope, not this run's own actions. {@code unreviewable} starts
 * from that same fixed scope ({@link PrepDir#unreviewable()}{@code .size()}) but can shrink: a file
 * the disposition ledger resolved TRUST_DECISION is no longer counted as unreviewable at all (see
 * {@code ApplyPlanner.resolvedUnreviewable()}). {@code heals}
 * lists every path {@link ShardValidator} auto-corrected via a unique sidecar basename.
 */
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
