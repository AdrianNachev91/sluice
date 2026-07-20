package photos.sluice.domain.dating;

import java.time.LocalDateTime;

// Shared by every date-resolution chain (DateResolver, RescueDateResolver): a resolved date is
// only trustworthy if it falls within a real, already-past window - not a corrupt pre-2000 EXIF
// value, and not a clock-skewed future one.
final class DatePlausibility {

    // No real photo in a personal library predates consumer digital cameras. A date reported
    // before this floor is far more likely a misconfigured camera clock or a misread metadata
    // field than a genuine capture date.
    private static final int PLAUSIBLE_MIN_YEAR = 2000;

    private DatePlausibility() {
    }

    static boolean isPlausible(LocalDateTime when) {
        return when.getYear() >= PLAUSIBLE_MIN_YEAR && !when.isAfter(LocalDateTime.now());
    }
}
