package photos.sluice.domain.sift;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code Troubleshooter.troubleshoot()}'s outcome for one prep dir.
 *
 * <p>The index rebuild is checked and attempted first. Nothing else here can be computed at all
 * without a readable index: not the tally, not the shard contract, not missing sources.
 *
 * <p>{@code strayShardsRepaired} is in the order repaired, and each entry reads like
 * "decisions-002.json -> montage-001". {@code after} equals {@code before} when no repair ran.
 *
 * <p>{@code text} is the technical, path-and-hash-level report the disaster drawer files away. It
 * is never written for a layman.
 */
public record TroubleshootReport(PrepDirHealth before, boolean indexRebuilt, @Nullable ReconcileReport reconcile,
                                 List<String> strayShardsRepaired, PrepDirHealth after, String text) {

    /**
     * Defensively copies the mutable list field.
     *
     * @param before {@link PrepDirHealth} the diagnosis taken first
     * @param indexRebuilt boolean whether a CorruptIndex finding in before was AUTO-repaired
     * @param reconcile {@link ReconcileReport} the move-log rebuild outcome, or null if none ran
     * @param strayShardsRepaired a {@link List} of {@link String} every stray shard AUTO-renamed into place
     * @param after {@link PrepDirHealth} the re-diagnosis taken once any repairs ran
     * @param text {@link String} the rendered report text
     */
    public TroubleshootReport {
        strayShardsRepaired = List.copyOf(strayShardsRepaired);
    }
}
