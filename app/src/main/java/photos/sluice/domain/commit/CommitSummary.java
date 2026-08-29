package photos.sluice.domain.commit;

import java.util.Map;

/**
 * Outcome counters from one commit run.
 *
 * <p>{@code committed} should equal the sum of {@code byBucket}'s values: every in-scope file lands
 * in exactly one {@link LibraryBucket} and contributes exactly one appended index row.
 *
 * <p>{@code cancelled} is the run's own account of whether it stopped short.
 *
 * <p>{@code leftBehind} counts the in-scope files still in Sorted. It is what the run was asked for
 * minus what it moved, so an out-of-scope tail is not in it.
 */
public record CommitSummary(int committed, int leftBehind, Map<LibraryBucket, Integer> byBucket,
                            boolean cancelled) {

    /**
     * Makes the bucket-count map immutable.
     *
     * @param committed int total number of files committed
     * @param leftBehind int in-scope files this run did not reach, still in Sorted
     * @param byBucket a {@link Map} of {@link LibraryBucket} to {@link Integer} per-bucket committed file counts
     * @param cancelled boolean whether the run gave up before reaching the end of its scope
     */
    public CommitSummary {
        byBucket = Map.copyOf(byBucket);
    }
}
