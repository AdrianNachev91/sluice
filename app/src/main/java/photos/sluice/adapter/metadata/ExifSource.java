package photos.sluice.adapter.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.domain.dating.DateSource;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.TakeoutSidecar;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * A {@link DateSource} that reads a capture date from a media file's embedded EXIF metadata,
 * preferring the original capture timestamp and falling back to the digitized timestamp.
 */
@Component
public class ExifSource implements DateSource {

    // metadata-extractor's raw Exif date string ("yyyy:MM:dd HH:mm:ss") carries no timezone.
    // Parsed directly to LocalDateTime rather than via Directory#getDate(TimeZone), which would
    // treat the naive value as if it were already in that zone and convert it.
    private static final DateTimeFormatter EXIF_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    /**
     * Resolves a capture date from the file's EXIF metadata.
     *
     * @param file {@link MediaFile} the media file to read EXIF data from
     * @param sidecar {@link TakeoutSidecar} unused for this source
     * @return an {@link Optional} {@link LocalDateTime}, the parsed EXIF date, if present and valid
     */
    @Override
    public Optional<LocalDateTime> resolve(MediaFile file, @Nullable TakeoutSidecar sidecar) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.path().toFile());
        } catch (ImageProcessingException | IOException | RuntimeException _) {
            // metadata-extractor throws unchecked exceptions (e.g. ArrayIndexOutOfBoundsException)
            // on some malformed/corrupt real-world EXIF blocks, not just its checked exception type.
            // One bad file must fall through to the next DateSource, not abort the whole batch.
            return Optional.empty();
        }
        ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        // getFirstDirectoryOfType's own signature claims non-null. It returns null in practice
        // when no directory of that type is present - the IDE can't model that runtime behavior.
        //noinspection ConstantValue
        if (directory == null) {
            return Optional.empty();
        }
        return parse(directory.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
                .or(() -> parse(directory.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED)));
    }

    /**
     * Parses a raw EXIF date string into a {@link LocalDateTime}.
     *
     * @param raw {@link String} the raw EXIF date string, or null
     * @return an {@link Optional} {@link LocalDateTime}, the parsed date, if the string is present and well-formed
     */
    private static Optional<LocalDateTime> parse(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(raw, EXIF_DATE_FORMAT));
        } catch (DateTimeParseException _) {
            return Optional.empty();
        }
    }
}
