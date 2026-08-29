package photos.sluice.adapter.cli;

import photos.sluice.domain.commit.CommitSummary;
import photos.sluice.domain.commit.LibraryBucket;

import java.util.Map;

/**
 * The wire shape for what a move-to-library run did, and the reading that builds it.
 *
 * <p>Every field comes from what the engine answered. Nothing here parses a message or invents a
 * number. So a caller acts on what the commit decided rather than on how this surface happened to
 * word it.
 */
public final class CommitPayloads {

    /**
     * Prevents instantiation of this static utility class.
     */
    private CommitPayloads() {
    }

    /**
     * What one move-to-library run did.
     *
     * @param committed int files moved into the library
     * @param leftBehind int in-scope files this run did not reach, still in Sorted
     * @param byBucket a {@link Map} of {@link LibraryBucket} to {@link Integer} per-bucket committed
     *        file counts
     * @param stopped boolean whether the run gave up before the end of its scope
     */
    public record CommittedPayload(int committed, int leftBehind, Map<LibraryBucket, Integer> byBucket,
                                   boolean stopped) {
    }

    /**
     * Reads what a move-to-library run did onto the wire.
     *
     * @param summary {@link CommitSummary} what the run did
     * @return {@link CommittedPayload} its machine-readable shape
     */
    public static CommittedPayload committed(final CommitSummary summary) {
        return new CommittedPayload(summary.committed(), summary.leftBehind(), summary.byBucket(),
                summary.cancelled());
    }
}
