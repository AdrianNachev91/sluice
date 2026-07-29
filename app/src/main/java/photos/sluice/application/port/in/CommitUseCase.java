package photos.sluice.application.port.in;

import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;

/**
 * The use case for promoting locally staged media into the permanent library. A caller invokes
 * this once sorting has produced keepers ready to become durable, moving them out of local
 * staging and into the library's dated folders.
 */
public interface CommitUseCase {

    /**
     * Commits the given scope from local staging into the library.
     *
     * @param scope {@link CommitScope} which staged items to commit
     * @return {@link CommitSummary} summary of the commit run
     */
    CommitSummary commit(CommitScope scope);
}
