package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortScope;

/**
 * The use case for the two-step "curate" flow: sort scope's Inbox files into Sorted, then run a
 * cull over whatever that sort just populated.
 *
 * <p>It is a convenience for a caller that wants to offer curate as one action instead of two
 * separate calls to {@link SortUseCase} and {@link CullUseCase}. It never commits Sorted to the
 * library; that stays a distinct, explicit step.
 */
// Nothing implements this port yet.
@SuppressWarnings("unused")
public interface CurateUseCase {

    /**
     * Sorts scope into Sorted, then culls whatever that sort just populated.
     *
     * @param scope {@link SortScope} which Inbox files to sort
     * @return {@link CurateOutcome} the sort summary plus the resulting cull outcome, if any
     */
    CurateOutcome curate(SortScope scope);
}
