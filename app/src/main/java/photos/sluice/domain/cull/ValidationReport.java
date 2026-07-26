package photos.sluice.domain.cull;

import java.util.List;

// The outcome of validating a prep directory's decision shards against the shard contract. findings
// is every contract violation found, aggregated across all shards so a bad cull is seen whole rather
// than one error per re-run; the run is valid only when it is empty. heals are non-fatal warnings for
// paths auto-corrected via a unique sidecar basename (a culler retyped a \YYYY\MM\ segment). decisions
// is the merged, heal-corrected flat list every shard contributed, in shard order - the input the
// apply step moves files from. It is populated best-effort even on an invalid report, but apply reads it
// only once valid() holds.
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
        return findings.isEmpty();
    }
}
