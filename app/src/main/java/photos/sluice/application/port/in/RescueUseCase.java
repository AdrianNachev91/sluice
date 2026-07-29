package photos.sluice.application.port.in;

import photos.sluice.domain.rescue.RescueSummary;

/**
 * The use case for promoting keepers a user has manually weeded inside a Review folder back into
 * the library or local staging. It is how a Review folder gets emptied and removed once its
 * remaining files have been judged worth keeping.
 */
public interface RescueUseCase {

    /**
     * reviewFolder is a path relative to the Review root (e.g. "2019-06", "Food", "Unsorted").
     *
     * @param reviewFolder {@link String} path relative to the Review root
     * @return {@link RescueSummary} summary of the rescue run
     */
    RescueSummary rescue(String reviewFolder);
}
