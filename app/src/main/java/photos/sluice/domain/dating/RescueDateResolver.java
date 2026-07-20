package photos.sluice.domain.dating;

import photos.sluice.domain.model.MediaFile;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Rescue's own date-resolution chain, deliberately not DateResolver: Takeout sidecars are long
// gone by rescue time, and a file with no real date signal must be skipped rather than dated from
// mtime - a rescue never fabricates a date, not even from the filesystem clock. The order also
// diverges - folder-derived date first, not last - because the Review folder a file already sits
// in (a dated "yyyy-mm" leaf) encodes a more trustworthy date than a 2-source exif/filename
// re-derivation could produce; only Food/Scenery/Unsorted (no date in the folder name) actually
// fall through to exif/filename.
public class RescueDateResolver {

    // Matches the whole target leaf, e.g. "2019-06".
    private static final Pattern DATED_LEAF = Pattern.compile("^(\\d{4})-(\\d{2})$");

    private final DateSource exifSource;
    private final DateSource filenameSource;

    public RescueDateResolver(DateSource exifSource, DateSource filenameSource) {
        this.exifSource = exifSource;
        this.filenameSource = filenameSource;
    }

    public Optional<LocalDateTime> resolve(MediaFile file, String targetLeaf) {
        return folderDate(targetLeaf)
                .or(() -> exifSource.resolve(file, null))
                .or(() -> filenameSource.resolve(file, null))
                .filter(DatePlausibility::isPlausible);
    }

    private static Optional<LocalDateTime> folderDate(String targetLeaf) {
        Matcher leaf = DATED_LEAF.matcher(targetLeaf);
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
