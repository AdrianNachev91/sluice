package photos.sluice.domain.model;

import photos.sluice.domain.dating.ScopeSelector;

/**
 * A media file paired with its resolved {@link DateResult}. The
 * {@code photos.sluice.application.service.SortEngine} builds one of these per scanned file. It
 * then hands the list to {@link ScopeSelector}, which filters and orders by {@code date} to decide
 * which files fall within the requested {@link SortScope}.
 */
public record DatedMedia(MediaFile file, DateResult date) {
}
