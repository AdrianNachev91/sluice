package photos.sluice.domain.model;

import java.nio.file.Path;

/**
 * A single photo or video, identified by its filesystem path. Wrapping the raw {@link Path} in a
 * domain type keeps it distinct from other paths the app handles, such as a sidecar JSON or a
 * montage image.
 */
public record MediaFile(Path path) {
}
