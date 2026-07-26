package photos.sluice.adapter.metadata;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

// Last resort in the chain: filesystem modification time reflects when the file was last touched,
// not when it was captured, so DateResolver assigns it LOW confidence.
@Component
public class MtimeSource implements DateSource {

    /**
     * Resolves a capture date from the file's last-modified timestamp.
     *
     * @param file {@link MediaFile} the media file to read the modification time from
     * @param sidecar {@link TakeoutSidecar} unused for this source
     * @return an {@link Optional} {@link LocalDateTime}, the file's last-modified time, if it could be read
     */
    @Override
    public Optional<LocalDateTime> resolve(MediaFile file, @Nullable TakeoutSidecar sidecar) {
        try {
            FileTime mtime = Files.getLastModifiedTime(file.path());
            return Optional.of(LocalDateTime.ofInstant(mtime.toInstant(), ZoneId.systemDefault()));
        } catch (IOException _) {
            return Optional.empty();
        }
    }
}
