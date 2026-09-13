package photos.sluice.domain.sift;

import java.util.List;

/**
 * The outcome of validating a prep directory's decision shards against the shard contract.
 *
 * <p>{@code findings} is every contract violation found, aggregated across all shards so a bad
 * sift is seen whole rather than one error per re-run. The run is valid only when it is empty.
 * {@code heals} are non-fatal warnings for paths auto-corrected via a unique sidecar basename (a
 * sieve retyped a {@code \YYYY\MM\} segment). {@code decisions} is the merged, heal-corrected flat
 * list every shard contributed, in shard order. It is populated best-effort even on an invalid
 * report, so {@link #valid()} is what says whether it can be acted on.
 */
public record ValidationReport(List<Finding> findings, List<String> heals, List<Decision> decisions) {

    /**
     * Defensively copies the mutable collection fields.
     *
     * @param findings a {@link List} of {@link Finding} every contract violation found
     * @param heals a {@link List} of {@link String} non-fatal warnings for auto-corrected paths
     * @param decisions a {@link List} of {@link Decision} the merged, heal-corrected flat decision list
     */
    public ValidationReport {
        findings = List.copyOf(findings);
        heals = List.copyOf(heals);
        decisions = List.copyOf(decisions);
    }

    /**
     * Whether every contract check passed.
     *
     * @return boolean true if no findings were reported
     */
    public boolean valid() {
        return this.findings.isEmpty();
    }
}
