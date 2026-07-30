package photos.sluice.adapter.metadata;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link DateSource} that reads a capture date from a date pattern embedded in the file's own
 * name, matching common camera and messaging-app export naming conventions.
 */
@Component
public class FilenameSource implements DateSource {

    // Accepts run-together (YYYYMMDD) and separated (YYYY-MM-DD / YYYY_MM_DD / YYYY.MM.DD) forms,
    // matching filenames like IMG_20210315_103000.jpg or IMG-20210315-WA0001.jpg. Folder routing
    // only needs year/month/day, so any embedded time component is deliberately not captured.
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(20\\d{2}|19\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})");

    /**
     * Resolves a capture date from a date pattern embedded in the filename.
     *
     * @param file {@link MediaFile} the media file whose filename is scanned for a date
     * @param sidecar {@link TakeoutSidecar} unused for this source
     * @return an {@link Optional} {@link LocalDateTime}, the parsed date, if the filename matched the date pattern
     */
    @Override
    public Optional<LocalDateTime> resolve(final MediaFile file, final @Nullable TakeoutSidecar sidecar) {
        final Matcher matcher = DATE_PATTERN.matcher(file.path().getFileName().toString());
        if (!matcher.find()) {
            return Optional.empty();
        }
        final int year = Integer.parseInt(matcher.group(1));
        final int month = Integer.parseInt(matcher.group(2));
        final int day = Integer.parseInt(matcher.group(3));
        try {
            return Optional.of(LocalDate.of(year, month, day).atStartOfDay());
        } catch (DateTimeException _) {
            return Optional.empty();
        }
    }
}
