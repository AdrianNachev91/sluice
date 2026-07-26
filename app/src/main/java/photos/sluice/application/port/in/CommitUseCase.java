package photos.sluice.application.port.in;

import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;

public interface CommitUseCase {

    /**
     * Commits the given scope from local staging into the library.
     *
     * @param scope {@link CommitScope} which staged items to commit
     * @return {@link CommitSummary} summary of the commit run
     */
    CommitSummary commit(CommitScope scope);
}
