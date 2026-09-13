package photos.sluice.domain.sift;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One entry in a montage sidecar's {@code photos[]} array.
 *
 * <p>{@code time} is the file's raw mtime, not a resolved local date. A sift run orders by
 * filesystem mtime, deliberately not routed through
 * {@link photos.sluice.domain.dating.DateResolver}. {@code received} is a carried flag rather than
 * one computed here, the filename pattern behind it having been matched before this entry is
 * built.
 */
public record SidecarPhotoEntry(Path src, String name, Instant time, boolean received) {
}
