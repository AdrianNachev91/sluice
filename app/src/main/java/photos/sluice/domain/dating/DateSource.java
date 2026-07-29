package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * A single strategy for deriving a media file's capture date from one specific signal, such as a
 * Takeout sidecar, EXIF metadata, a filename pattern, or filesystem mtime. Each implementation
 * only answers whether it found a date; {@link DateResolver} decides which source's answer to
 * trust.
 */
public interface DateSource {

    /**
     * Attempts to resolve a capture date for a media file.
     *
     * @param file {@link MediaFile} the media file to date
     * @param sidecar {@link TakeoutSidecar} the file's Takeout sidecar, if any
     * @return an {@link Optional} {@link LocalDateTime}, if this source found one
     */
    Optional<LocalDateTime> resolve(MediaFile file, @Nullable TakeoutSidecar sidecar);
}
