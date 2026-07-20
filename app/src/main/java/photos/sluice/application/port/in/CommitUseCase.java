package photos.sluice.application.port.in;

import photos.sluice.domain.commit.CommitScope;
import photos.sluice.domain.commit.CommitSummary;

public interface CommitUseCase {

    CommitSummary commit(CommitScope scope);
}
