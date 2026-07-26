package photos.sluice.application.port.in;

import photos.sluice.domain.rescue.RescueSummary;

public interface RescueUseCase {

    /**
     * reviewFolder is a path relative to the Review root (e.g. "2019-06", "Food", "Unsorted").
     *
     * @param reviewFolder {@link String} path relative to the Review root
     * @return {@link RescueSummary} summary of the rescue run
     */
    RescueSummary rescue(String reviewFolder);
}
