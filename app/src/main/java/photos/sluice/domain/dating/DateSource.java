package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DateSource {
    Optional<LocalDateTime> resolve(MediaFile file, @Nullable TakeoutSidecar sidecar);
}
