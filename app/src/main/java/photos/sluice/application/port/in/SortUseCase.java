package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

public interface SortUseCase {

    /**
     * Dates, dedupes, and moves scope's Inbox files into Sorted.
     *
     * @param scope {@link SortScope} which Inbox files to sort
     * @return {@link SortSummary} summary of the sort run
     */
    SortSummary sort(SortScope scope);
}
