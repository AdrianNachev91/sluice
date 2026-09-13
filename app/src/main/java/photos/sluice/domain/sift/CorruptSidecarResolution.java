package photos.sluice.domain.sift;

/**
 * How a user resolved a {@link Finding.CorruptSidecar} finding. SET_ASIDE means that montage's
 * decisions are ledger-abandoned - its photos stay in Sorted for a future sift of that scope.
 * APPLY_ANYWAY means the montage's own shard is trusted at face value, its membership cross-check
 * skipped. Every other safety net still applies: files must exist, categories configured,
 * cross-shard duplicate check, never-overwrite. How an answer is recorded, and what it does not
 * touch, is in {@code app/docs/design/application/service/prep-dir-remedies.md}.
 */
public enum CorruptSidecarResolution {
    SET_ASIDE, APPLY_ANYWAY
}
