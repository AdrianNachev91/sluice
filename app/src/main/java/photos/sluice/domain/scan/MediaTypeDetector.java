package photos.sluice.domain.scan;

import photos.sluice.domain.model.MediaType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies a file as a photo or video purely by its extension, against two fixed extension sets.
 * A path whose extension matches neither set is treated as neither, leaving classification of
 * anything else (Takeout JSON sidecars, unrecognized formats) to the caller.
 */
public final class MediaTypeDetector {

    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "heic", "heif", "webp", "bmp", "gif", "tif", "tiff", "dng",
            "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "svg", "avif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "mkv", "avi", "m4v", "3gp", "webm", "wmv", "mpg", "mpeg", "mts", "m2ts", "flv");

    /**
     * Classifies a path as a photo or video by its extension.
     *
     * @param path {@link Path} the path to classify
     * @return an {@link Optional} {@link MediaType}, if the extension is recognized
     */
    public Optional<MediaType> classify(final Path path) {
        final String extension = extensionOf(path);
        if (PHOTO_EXTENSIONS.contains(extension)) {
            return Optional.of(MediaType.PHOTO);
        }
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return Optional.of(MediaType.VIDEO);
        }
        return Optional.empty();
    }

    /**
     * Extracts a path's file extension, lowercased.
     *
     * @param path {@link Path} the path to inspect
     * @return {@link String} the lowercased extension, or an empty string if there is none
     */
    public static String extensionOf(final Path path) {
        final String name = path.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
