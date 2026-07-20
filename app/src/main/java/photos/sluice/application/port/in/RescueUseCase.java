package photos.sluice.application.port.in;

import photos.sluice.domain.rescue.RescueSummary;

public interface RescueUseCase {

    // reviewFolder is a path relative to the Review root (e.g. "2019-06", "Food", "Unsorted").
    RescueSummary rescue(String reviewFolder);
}
