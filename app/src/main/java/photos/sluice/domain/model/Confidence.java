package photos.sluice.domain.model;

import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.DateSource;

/**
 * How much a resolved {@link DateResult} should be trusted. A {@link DateSource} only ever answers
 * what date a file has. It never says how much to trust it. The chain that calls it assigns the
 * confidence for each source position.
 *
 * <p>{@code UNSORTABLE} is also assigned by {@link DateResolver} itself, never by a source, when a
 * {@code LOW}-confidence date fails the plausibility guard (future or pre-2000).
 */
public enum Confidence {
    TRUSTED,
    LOW,
    UNSORTABLE
}
