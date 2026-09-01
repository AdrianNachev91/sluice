package photos.sluice.domain.dating;

import org.jspecify.annotations.Nullable;
import photos.sluice.domain.model.MediaFile;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a plausible date for a file being rescued, using its own chain rather than
 * {@link DateResolver}'s. By rescue time no Takeout sidecar survives. A file with no real date
 * signal answers empty rather than being dated from mtime. A rescue never fabricates a date, not
 * even from the filesystem clock.
 *
 * <p>The order also differs: what the folder's own note recorded comes first, then the date the
 * file's path spells, and only then exif and the filename. The note is the answer this app reached
 * when it filed the photo, off a chain wider than anything still readable now. The path is that
 * same answer with the day dropped. A folder named for a category carries no date, and a note
 * written before dates were recorded carries none either, so both fall through.
 */
public class RescueDateResolver {

    // A folder named for one month, which is how a sort files what a sift will not look at.
    private static final Pattern DASHED_MONTH = Pattern.compile("(?:^|/)(\\d{4})-(\\d{2})/");

    // A year folder holding month folders, which is how a sift files what it could not judge.
    private static final Pattern NESTED_MONTH = Pattern.compile("(?:^|/)(\\d{4})/(\\d{2})/");

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
     * Resolves a plausible date for a file being rescued, preferring what its folder's note says.
     *
     * <p>An implausible noted date is not the end of the chain. It is text a reader can edit, so a
     * bad one falls through to what the file itself still carries.
     *
     * @param file {@link MediaFile} the media file to date
     * @param within {@link String} the file's path below the root it is being rescued from, written
     *     with {@code /} whatever the platform uses
     * @param noted {@link LocalDateTime} what the folder's note recorded for this file, or null
     * @return an {@link Optional} {@link LocalDateTime}, if any source produced a plausible one
     */
    public Optional<LocalDateTime> resolve(final MediaFile file, final String within,
                                           final @Nullable LocalDateTime noted) {
        return Optional.ofNullable(noted)
                .filter(DatePlausibility::isPlausible)
                .or(() -> pathDate(within)
                        .or(() -> this.exifSource.resolve(file, null))
                        .or(() -> this.filenameSource.resolve(file, null))
                        .filter(DatePlausibility::isPlausible));
    }

    /**
     * The month a file's own path names, in either of the two shapes a job writes.
     *
     * <p>Both are read as the first of that month. A folder names a month and nothing finer, so any
     * other day would be invented.
     *
     * @param within {@link String} the file's path below the root it is being rescued from
     * @return an {@link Optional} {@link LocalDateTime}, empty where the path names no month
     */
    private static Optional<LocalDateTime> pathDate(final String within) {
        final Matcher dashed = DASHED_MONTH.matcher(within);
        if (dashed.find()) {
            return firstOfMonth(dashed.group(1), dashed.group(2));
        }
        final Matcher nested = NESTED_MONTH.matcher(within);
        return nested.find() ? firstOfMonth(nested.group(1), nested.group(2)) : Optional.empty();
    }

    /**
     * The first of the month two matched groups name.
     *
     * @param year {@link String} four digits
     * @param month {@link String} two digits, which the pattern does not bound to 1 through 12
     * @return an {@link Optional} {@link LocalDateTime}, empty where those digits name no real month
     */
    private static Optional<LocalDateTime> firstOfMonth(final String year, final String month) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), 1).atStartOfDay());
        } catch (DateTimeException _) {
            return Optional.empty();
        }
    }
}
