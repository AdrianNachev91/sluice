package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

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
