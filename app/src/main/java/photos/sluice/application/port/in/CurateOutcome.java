package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortSummary;

// Curate is sort immediately followed by a cull over whatever that sort just populated.
// cullOutcome is the same CullJobOutcome a standalone cull() call would produce - Applied or
// Waiting - so a caller handles it identically regardless of which use case produced it.
public record CurateOutcome(SortSummary sortSummary, CullJobOutcome cullOutcome) {
}
