package photos.sluice.application.service;

import photos.sluice.domain.sift.ApplyReport;

/**
 * How one apply ended, and what it moved either way.
 *
 * <p>The two arms count {@code unreviewable} differently, and each says which rule it used.
 * Everything else in the report means what {@link ApplyReport} says it means.
 */
sealed interface ApplyEnding {

    /**
     * The apply worked through every decision and wrote its merged record.
     *
     * @param report {@link ApplyReport} what it moved
     */
    record Finished(ApplyReport report) implements ApplyEnding {
    }

    /**
     * The apply gave up between two files, so its finalizers never ran and no merged record was
     * written.
     *
     * @param report {@link ApplyReport} what it moved before giving up. Its {@code unreviewable} is
     *     a count of files this leg actually moved, rather than the scope figure a finished run
     *     reports
     */
    record StoppedMidRun(ApplyReport report) implements ApplyEnding {
    }

    /**
     * @return {@link ApplyReport} the report this ending carries
     */
    ApplyReport report();
}
