package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

/**
 * The use case for dating, de-duplicating, and moving a scope of Inbox files into local staging.
 * This is the mechanical first stage of the pipeline, run before any vision-based culling.
 */
public interface SortUseCase {

    /**
     * Dates, dedupes, and moves scope's Inbox files into Sorted.
     *
     * @param scope {@link SortScope} which Inbox files to sort
     * @return {@link SortSummary} summary of the sort run
     */
    SortSummary sort(SortScope scope);
}
