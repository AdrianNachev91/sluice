package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortScope;

// Sort scope's Inbox files into Sorted, then run a cull over whatever that sort just populated.
// This is the two-step "curate" flow: a convenience for an implementation that wants to offer
// it as one action instead of two separate calls to SortUseCase and CullUseCase. It never commits
// Sorted to the library; that stays a distinct, explicit step.
public interface CurateUseCase {

    /**
     * Sorts scope into Sorted, then culls whatever that sort just populated.
     *
     * @param scope {@link SortScope} which Inbox files to sort
     * @return {@link CurateOutcome} the sort summary plus the resulting cull outcome, if any
     */
    CurateOutcome curate(SortScope scope);
}
