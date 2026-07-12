package photos.sluice.application.port.out;

import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

public interface DateSource {
    // sidecar is nullable, not Optional - Optional is a return-type tool, not a parameter type
    // (every DateSource call site would pay wrap/unwrap cost for a value most sources ignore).
    Optional<LocalDateTime> resolve(MediaFile file, TakeoutSidecar sidecar);
}
