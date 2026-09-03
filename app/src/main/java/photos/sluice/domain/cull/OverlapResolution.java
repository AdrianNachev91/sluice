package photos.sluice.domain.cull;

/**
 * How a user resolved a {@link Finding.VerdictUnreviewableOverlap} finding. TRUST_DECISION means
 * the shard's own verdict applies, and the file is no longer treated as unreviewable at all.
 * TREAT_AS_UNREVIEWABLE means the verdict is dropped instead, and the file goes to the unreviewable
 * folder.
 * {@code PrepDirRemedies.resolveOverlap()} records which one was chosen; validation then honors it
 * without ever editing the shard or index.json that produced the conflict.
 */
public enum OverlapResolution {
    TRUST_DECISION, TREAT_AS_UNREVIEWABLE
}
