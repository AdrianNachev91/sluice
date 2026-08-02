package photos.sluice.domain.cull;

import java.util.List;

/**
 * {@link photos.sluice.application.service.PrepDirDoctor#diagnose}'s health report for a prep dir:
 * an overall state, plus every currently open finding.
 *
 * <p>Findings are ordered by repair dependency - AUTO-remedied first, then CHOICE, then the
 * informational NONE ones - rather than just by severity. The same shape backs both a proactive
 * dashboard read and a failed apply's own {@link photos.sluice.application.port.out.ApplyException}.
 * The UI panel for either looks identical.
 */
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

    /**
     * A prep dir's overall repair/completion state. WAITING: {@code index.json} exists, but at
     * least one montage has no shard yet - still being culled. BLOCKED: every montage has a shard,
     * but something needs a remedy before apply would succeed. READY: every montage has a shard,
     * and nothing blocks apply from running now. COMPLETE: {@code decisions.json} exists - this
     * run already applied. DAMAGED: reading the prep dir did not get far enough to establish
     * anything about the run.
     *
     * <p>DAMAGED carries a single {@link Finding.UnreadablePrepDir}, whose remedy is NONE. It says
     * only that the reading stopped, which is the one thing actually known. That covers a read that
     * failed outright, and equally content past index.json that no reader could make sense of. No
     * repair is offered either way, because nothing has been established as broken.
     */
    public enum State {
        WAITING, BLOCKED, READY, COMPLETE, DAMAGED
    }
}
