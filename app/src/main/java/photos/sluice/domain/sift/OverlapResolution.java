package photos.sluice.domain.sift;

/**
 * How a user resolved a {@link Finding.VerdictUnreviewableOverlap} finding. TRUST_DECISION means
 * the shard's own verdict applies, and the file is no longer treated as unreviewable at all.
 * TREAT_AS_UNREVIEWABLE means the verdict is dropped instead, and the file goes to the unreviewable
 * folder. How an answer is recorded, and what it does not touch, is in
 * {@code app/docs/design/application/service/prep-dir-remedies.md}.
 */
public enum OverlapResolution {
    TRUST_DECISION, TREAT_AS_UNREVIEWABLE
}
