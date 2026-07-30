package photos.sluice.domain.cull;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code Troubleshooter.troubleshoot()}'s outcome for one prep dir. before is the diagnosis taken
 * first. indexRebuilt is whether a CorruptIndex finding in before was AUTO-repaired. It's checked
 * and attempted first, since nothing else here - the tally, the shard contract, missing sources -
 * can be computed at all without a readable index. reconcile is the move-log rebuild it triggered,
 * or null if no finding suggested one was needed. strayShardsRepaired names every stray shard the
 * AUTO remedy renamed into place ("decisions-002.json -> montage-001"), in the order repaired;
 * empty if none qualified. after is the re-diagnosis taken once every repair above ran, equal to
 * before when none did. text is the same technical, path-and-hash-level report the disaster drawer
 * files away. It is also what a support hand-off text field would show verbatim - never
 * layman-friendly copy. That stays a UI-layer concern built on top of this structured data.
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
