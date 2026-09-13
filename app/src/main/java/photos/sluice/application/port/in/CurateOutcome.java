package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.SortSummary;

/**
 * The result of a curate run: sort immediately followed by a sift over whatever that sort just
 * populated.
 *
 * <p>{@code siftOutcome} is the same {@link SiftJobOutcome} a standalone {@code sift()} call
 * would produce, so every variant is reachable here and a caller handles it identically regardless
 * of which use case produced it.
 *
 * <p>It is null in exactly two cases where the sift stage never ran at all. Cancellation was
 * requested between the sort and sift stages. Or scope was {@code OldestYear}, and its sort found
 * nothing to route into Sorted, so there was no year left to sift. An explicit {@code Year} or
 * {@code OldestN} scope always sifts once sorted, even when this run added nothing new under it.
 *
 * @param sortSummary {@link SortSummary} summary of the sort stage
 * @param siftOutcome {@link SiftJobOutcome} the resulting sift outcome, or null if the sift stage
 * never ran
 */
public record CurateOutcome(SortSummary sortSummary, @Nullable SiftJobOutcome siftOutcome) {
}
