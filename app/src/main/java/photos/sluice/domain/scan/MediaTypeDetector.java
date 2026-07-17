package photos.sluice.domain.scan;

import photos.sluice.domain.model.MediaType;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class MediaTypeDetector {

    private static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "heic", "heif", "webp", "bmp", "gif", "tif", "tiff", "dng",
            "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "svg", "avif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "mkv", "avi", "m4v", "3gp", "webm", "wmv", "mpg", "mpeg", "mts", "m2ts", "flv");

    public Optional<MediaType> classify(Path path) {
        String extension = extensionOf(path);
        if (PHOTO_EXTENSIONS.contains(extension)) {
            return Optional.of(MediaType.PHOTO);
        }
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return Optional.of(MediaType.VIDEO);
        }
        return Optional.empty();
    }

    public static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
