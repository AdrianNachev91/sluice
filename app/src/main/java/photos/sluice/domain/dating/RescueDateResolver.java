package photos.sluice.domain.dating;

import photos.sluice.domain.model.MediaFile;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a plausible date for a file being rescued out of a {@code Review} folder, using its own
 * chain rather than {@link DateResolver}'s. By rescue time no Takeout sidecar survives. A file
 * with no real date signal is skipped rather than dated from mtime. A rescue never fabricates a
 * date, not even from the filesystem clock.
 *
 * <p>The order also differs: a folder-derived date is tried first, not last. The {@code Review}
 * folder a file already sits in encodes a dated {@code "yyyy-mm"} leaf, which is more trustworthy
 * than a two-source exif/filename re-derivation could produce. Only Food, Scenery, and Unsorted
 * folders, whose names carry no date, actually fall through to exif and filename.
 */
public class RescueDateResolver {

    // Matches the whole target leaf, e.g. "2019-06".
    private static final Pattern DATED_LEAF = Pattern.compile("^(\\d{4})-(\\d{2})$");

    private final DateSource exifSource;
    private final DateSource filenameSource;

    /**
     * Builds the resolver from its two fallback date sources.
     *
     * @param exifSource {@link DateSource} resolves from EXIF metadata
     * @param filenameSource {@link DateSource} resolves from the filename pattern
     */
    public RescueDateResolver(final DateSource exifSource, final DateSource filenameSource) {
        this.exifSource = exifSource;
        this.filenameSource = filenameSource;
    }

    /**
     * Resolves a plausible date for a rescued file, preferring the target folder's own date.
     *
     * @param file {@link MediaFile} the media file to date
     * @param targetLeaf {@link String} the rescue destination's leaf folder name
     * @return an {@link Optional} {@link LocalDateTime}, if any source produced a plausible one
     */
    public Optional<LocalDateTime> resolve(final MediaFile file, final String targetLeaf) {
        return folderDate(targetLeaf)
                .or(() -> this.exifSource.resolve(file, null))
                .or(() -> this.filenameSource.resolve(file, null))
                .filter(DatePlausibility::isPlausible);
    }

    /**
     * Parses a dated "yyyy-mm" leaf folder name into its first-of-month date.
     *
     * @param targetLeaf {@link String} the rescue destination's leaf folder name
     * @return an {@link Optional} {@link LocalDateTime}, if the leaf matches the dated pattern
     */
    private static Optional<LocalDateTime> folderDate(final String targetLeaf) {
        final Matcher leaf = DATED_LEAF.matcher(targetLeaf);
        if (!leaf.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(leaf.group(1)), Integer.parseInt(leaf.group(2)), 1)
                    .atStartOfDay());
        } catch (DateTimeException _) {
            return Optional.empty();
        }
    }
}
