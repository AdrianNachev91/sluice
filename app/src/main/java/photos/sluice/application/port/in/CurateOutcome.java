package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.SortSummary;

/**
 * The result of a curate run: sort immediately followed by a cull over whatever that sort just
 * populated.
 *
 * <p>{@code cullOutcome} is the same {@link CullJobOutcome} a standalone {@code cull()} call
 * would produce, so every variant is reachable here and a caller handles it identically regardless
 * of which use case produced it.
 *
 * <p>It is null in exactly two cases where the cull stage never ran at all. Cancellation was
 * requested between the sort and cull stages. Or scope was {@code OldestYear}, and its sort found
 * nothing to route into Sorted, so there was no year left to cull. An explicit {@code Year} or
 * {@code OldestN} scope always culls once sorted, even when this run added nothing new under it.
 *
 * @param sortSummary {@link SortSummary} summary of the sort stage
 * @param cullOutcome {@link CullJobOutcome} the resulting cull outcome, or null if the cull stage
 * never ran
 */
public record CurateOutcome(SortSummary sortSummary, @Nullable CullJobOutcome cullOutcome) {
}
