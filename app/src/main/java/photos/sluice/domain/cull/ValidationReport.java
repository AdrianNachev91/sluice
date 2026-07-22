package photos.sluice.domain.cull;

import java.util.List;

// The outcome of validating a prep directory's decision shards against the shard contract. problems
// is every contract violation found, aggregated across all shards so a bad cull is seen whole rather
// than one error per re-run; the run is valid only when it is empty. heals are non-fatal warnings for
// paths auto-corrected via a unique sidecar basename (a culler retyped a \YYYY\MM\ segment). decisions
// is the merged, heal-corrected flat list every shard contributed, in shard order - the input the
// apply step moves files from. It is populated best-effort even on an invalid report, but apply reads it
// only once valid() holds.
public record ValidationReport(List<String> problems, List<String> heals, List<Decision> decisions) {

    public ValidationReport {
        problems = List.copyOf(problems);
        heals = List.copyOf(heals);
        decisions = List.copyOf(decisions);
    }

    public boolean valid() {
        return problems.isEmpty();
    }
}
