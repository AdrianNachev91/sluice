package photos.sluice.domain.cull;

/**
 * How a user resolved a {@link Finding.CorruptSidecar} finding. SET_ASIDE means that montage's
 * decisions are ledger-abandoned - its photos stay in Sorted for a future cull of that scope.
 * APPLY_ANYWAY means the montage's own shard is trusted at face value, its membership cross-check
 * skipped. Every other safety net still applies: files must exist, categories configured,
 * cross-shard duplicate check, never-overwrite. {@code PrepDirRemedies.resolveCorruptSidecar()}
 * records which one was chosen; validation then honors it without ever editing the shard that
 * produced the conflict.
 */
public enum CorruptSidecarResolution {
    SET_ASIDE, APPLY_ANYWAY
}
