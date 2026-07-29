package photos.sluice.domain.model;

import photos.sluice.domain.dating.DateResolver;

import java.time.LocalDateTime;

/**
 * The date resolved for a media file, along with how much to trust it and which source produced
 * it. A {@link DateResolver} builds this by walking a fixed chain of sources (sidecar, exif,
 * filename, mtime). The {@code source} field names that source. The {@code confidence} field is
 * the trust level assigned to its position in the chain.
 */
public record DateResult(LocalDateTime when, Confidence confidence, String source) {
}
