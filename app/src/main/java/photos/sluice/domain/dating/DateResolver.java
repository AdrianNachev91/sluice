package photos.sluice.domain.dating;

import photos.sluice.application.port.out.DateSource;
import photos.sluice.domain.model.Confidence;
import photos.sluice.domain.model.DateResult;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

// Chain order (sidecar -> exif -> filename -> mtime) and the confidence assigned to each position
// are fixed here, not configurable - a DateSource only ever answers "what date, if any", never how
// much to trust it.
public class DateResolver {

    private static final int PLAUSIBLE_MIN_YEAR = 2000;

    private final DateSource sidecarSource;
    private final DateSource exifSource;
    private final DateSource filenameSource;
    private final DateSource mtimeSource;

    public DateResolver(DateSource sidecarSource, DateSource exifSource, DateSource filenameSource,
            DateSource mtimeSource) {
        this.sidecarSource = sidecarSource;
        this.exifSource = exifSource;
        this.filenameSource = filenameSource;
        this.mtimeSource = mtimeSource;
    }

    public DateResult resolve(MediaFile file, TakeoutSidecar sidecar) {
        DateResult result = tryResolve(sidecarSource, "sidecar", Confidence.TRUSTED, file, sidecar)
                .or(() -> tryResolve(exifSource, "exif", Confidence.TRUSTED, file, sidecar))
                .or(() -> tryResolve(filenameSource, "filename", Confidence.TRUSTED, file, sidecar))
                .or(() -> tryResolve(mtimeSource, "mtime", Confidence.LOW, file, sidecar))
                // Every source failing (e.g. the file vanished mid-scan) still needs a DateResult:
                // the caller routes on the confidence marker, not on this placeholder date.
                .orElseGet(() -> new DateResult(LocalDateTime.now(), Confidence.UNSORTABLE, "none"));

        if (result.confidence() == Confidence.LOW && !isPlausible(result.when())) {
            return new DateResult(result.when(), Confidence.UNSORTABLE, result.source());
        }
        return result;
    }

    private static Optional<DateResult> tryResolve(DateSource source, String name, Confidence confidence,
            MediaFile file, TakeoutSidecar sidecar) {
        return source.resolve(file, sidecar).map(when -> new DateResult(when, confidence, name));
    }

    private static boolean isPlausible(LocalDateTime when) {
        return when.getYear() >= PLAUSIBLE_MIN_YEAR && !when.isAfter(LocalDateTime.now());
    }
}
