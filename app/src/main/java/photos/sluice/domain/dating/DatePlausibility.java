package photos.sluice.domain.dating;

import java.time.LocalDateTime;

/**
 * Shared by every date-resolution chain ({@link DateResolver}, {@link RescueDateResolver}). A
 * resolved date is only trustworthy if it falls within a real, already-past window: not a corrupt
 * pre-2000 EXIF value, and not a clock-skewed future one.
 */
final class DatePlausibility {

    // No real photo in a personal library predates consumer digital cameras. A date reported
    // before this floor is far more likely a misconfigured camera clock or a misread metadata
    // field than a genuine capture date.
    private static final int PLAUSIBLE_MIN_YEAR = 2000;

    /**
     * Prevents instantiation; this is a static-only utility class.
     */
    private DatePlausibility() {
    }

    /**
     * Checks whether a resolved date falls within a real, already-past window.
     *
     * @param when {@link LocalDateTime} the date to check
     * @return boolean true if the date is plausible
     */
    static boolean isPlausible(final LocalDateTime when) {
        return when.getYear() >= PLAUSIBLE_MIN_YEAR && !when.isAfter(LocalDateTime.now());
    }
}
