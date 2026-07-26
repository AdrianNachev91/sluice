package photos.sluice.application.port.in;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.SortSummary;

// Curate is sort immediately followed by a cull over whatever that sort just populated.
// cullOutcome is the same CullJobOutcome a standalone cull() call would produce - Applied,
// Waiting, or Cancelled. A caller handles it identically regardless of which use case produced
// it.
//
// It is null in exactly two cases where the cull stage never ran at all. Cancellation was
// requested between the sort and cull stages. Or scope was OldestYear, and its sort found nothing
// to route into Sorted - so there was no year left to cull. An explicit Year or OldestN scope
// always culls once sorted, even when this particular run added nothing new under it.
public record CurateOutcome(SortSummary sortSummary, @Nullable CullJobOutcome cullOutcome) {
}
