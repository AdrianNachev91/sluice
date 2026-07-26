package photos.sluice.domain.cull;

import java.util.List;

// PrepDirDoctor.diagnose()'s health report for a prep dir: an overall state, plus every currently
// open finding. Findings are ordered by repair dependency - AUTO-remedied first, then CHOICE, then
// the informational NONE ones - rather than just severity. The same shape backs both a proactive
// dashboard read and a failed apply's own ApplyException, so the UI panel for either looks
// identical.
public record PrepDirHealth(State state, List<Finding> findings) {

    /**
     * Defensively copies the mutable findings list.
     *
     * @param state {@link State} the prep dir's overall state
     * @param findings a {@link List} of {@link Finding} every currently open finding
     */
    public PrepDirHealth {
        findings = List.copyOf(findings);
    }

    // WAITING: index.json exists, but at least one montage has no shard yet - still being culled.
    // BLOCKED: every montage has a shard, but something needs a remedy before apply() would succeed.
    // READY: every montage has a shard, and nothing blocks apply() from running now.
    // COMPLETE: decisions.json exists - this run already applied.
    public enum State {
        WAITING, BLOCKED, READY, COMPLETE
    }
}
