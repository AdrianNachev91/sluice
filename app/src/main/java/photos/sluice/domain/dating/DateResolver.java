package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.Confidence;
import photos.sluice.domain.model.DateResult;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Resolves the best available capture date for a media file by walking a fixed chain of
 * {@link DateSource}s: sidecar, then exif, then filename, then mtime.
 *
 * <p>The chain order and the confidence assigned to each position are fixed here, not
 * configurable. A {@link DateSource} only ever answers what date, if any, it found. It never says
 * how much to trust that date.
 */
public class DateResolver {

    private final DateSource sidecarSource;
    private final DateSource exifSource;
    private final DateSource filenameSource;
    private final DateSource mtimeSource;

    /**
     * Builds the resolver from its four chained date sources.
     *
     * @param sidecarSource {@link DateSource} resolves from a Takeout JSON sidecar
     * @param exifSource {@link DateSource} resolves from EXIF metadata
     * @param filenameSource {@link DateSource} resolves from the filename pattern
     * @param mtimeSource {@link DateSource} resolves from filesystem mtime
     */
    public DateResolver(final DateSource sidecarSource, final DateSource exifSource, final DateSource filenameSource,
                        final DateSource mtimeSource) {
        this.sidecarSource = sidecarSource;
        this.exifSource = exifSource;
        this.filenameSource = filenameSource;
        this.mtimeSource = mtimeSource;
    }

    /**
     * Resolves the best available date for a media file, walking the fixed source chain.
     *
     * @param file {@link MediaFile} the media file to date
     * @param sidecar {@link TakeoutSidecar} the file's Takeout sidecar, if any
     * @return {@link DateResult} the resolved date, confidence, and source
     */
    public DateResult resolve(final MediaFile file, final @Nullable TakeoutSidecar sidecar) {
        final DateResult result = tryResolve(this.sidecarSource, "sidecar", Confidence.TRUSTED, file, sidecar)
                .or(() -> tryResolve(this.exifSource, "exif", Confidence.TRUSTED, file, sidecar))
                .or(() -> tryResolve(this.filenameSource, "filename", Confidence.TRUSTED, file, sidecar))
                .or(() -> tryResolve(this.mtimeSource, "mtime", Confidence.LOW, file, sidecar))
                // Every source failing (e.g. the file vanished mid-scan) still needs a DateResult:
                // the caller routes on the confidence marker, not on this placeholder date.
                .orElseGet(() -> new DateResult(LocalDateTime.now(), Confidence.UNSORTABLE, "none"));

        if (result.confidence() == Confidence.LOW && !DatePlausibility.isPlausible(result.when())) {
            return new DateResult(result.when(), Confidence.UNSORTABLE, result.source());
        }
        return result;
    }

    /**
     * Wraps a single source's resolution attempt into a DateResult, if it produced a date.
     *
     * @param source {@link DateSource} the date source to try
     * @param name {@link String} the source's label
     * @param confidence {@link Confidence} the confidence to assign on a hit
     * @param file {@link MediaFile} the media file to date
     * @param sidecar {@link TakeoutSidecar} the file's Takeout sidecar, if any
     * @return an {@link Optional} {@link DateResult}, if the source resolved a date
     */
    private static Optional<DateResult> tryResolve(final DateSource source, final String name,
                                                   final Confidence confidence,
                                                   final MediaFile file,
                                                   final @Nullable TakeoutSidecar sidecar) {
        return source.resolve(file, sidecar).map(when -> new DateResult(when, confidence, name));
    }

}
