package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <prepDir>/disasters/} is the audit drawer for a prep dir's own recovery events. A
 * move-records.log or index.json this app decided was too damaged to salvage gets filed here via
 * {@link #file}. Every {@link Troubleshooter} report is written here directly via {@link #write}.
 * Every entry embeds its own filing time in its filename rather than relying on file mtime, which
 * this product already treats as untrustworthy metadata elsewhere. Retention reads that embedded
 * time, never mtime, and leaves any filename it cannot parse alone rather than guessing whether it
 * is safe to delete.
 */
@Component
public class DisasterDrawer {

    private static final String DRAWER_DIR = "disasters";
    private static final Duration RETENTION = Duration.ofDays(30);
    // No colons in the pattern - Windows path segments can't contain them. Seconds resolution is
    // plenty for a human audit trail; nothing ever parses this back to more precision than "is this
    // older than 30 days."
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})-.*");

    private final MediaStore mediaStore;

    /**
     * Creates a drawer wired to its port.
     *
     * @param mediaStore {@link MediaStore} filesystem effects (move, list, delete)
     */
    public DisasterDrawer(MediaStore mediaStore) {
        this.mediaStore = mediaStore;
    }

    /**
     * Files source into prepDir's disaster drawer, renamed to embed the current time and what. A
     * name collision (two events filed the same second under the same what) gets a numeric suffix,
     * the same way a real filesystem collision would.
     *
     * @param prepDir {@link Path} the prep directory whose drawer receives this file
     * @param source {@link Path} the file to file away
     * @param what {@link String} a short slug naming what this file is
     * @return {@link Path} the path source was filed to
     */
    public Path file(Path prepDir, Path source, String what) {
        Path drawer = prepDir.resolve(DRAWER_DIR);
        String extension = extensionOf(source.getFileName().toString());
        String stamp = TIMESTAMP_FORMAT.format(Instant.now());
        return mediaStore.moveTo(source, uniqueName(drawer, stamp, what, extension));
    }

    /**
     * Writes content as a new drawer entry, named the same way {@link #file} names a filed original -
     * stamped with the current time and what. For a generated artifact (a troubleshoot report) rather
     * than an existing file being moved out of the way.
     *
     * @param prepDir {@link Path} the prep directory whose drawer receives this entry
     * @param what {@link String} a short slug naming what this entry is
     * @param content {@link String} the entry's full text content
     * @return {@link Path} the path content was written to
     */
    public Path write(Path prepDir, String what, String content) {
        Path drawer = prepDir.resolve(DRAWER_DIR);
        mediaStore.ensureDirectory(drawer);
        String stamp = TIMESTAMP_FORMAT.format(Instant.now());
        Path dest = uniqueName(drawer, stamp, what, ".txt");
        mediaStore.write(dest, content);
        return dest;
    }

    /**
     * Deletes every disaster-drawer entry under cullPrepRoot whose filename-embedded filing time is
     * older than the 30-day retention window. A file whose name doesn't parse as a filed entry is
     * left alone, never deleted - this drawer only ever removes what it is certain it filed itself.
     *
     * @param cullPrepRoot {@link Path} the root directory holding every prep dir
     * @return int the number of entries deleted
     */
    public int sweepExpired(Path cullPrepRoot) {
        if (!mediaStore.exists(cullPrepRoot)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(RETENTION);
        List<Path> expired = mediaStore.listFiles(cullPrepRoot).stream()
                .filter(DisasterDrawer::isDrawerEntry)
                .filter(file -> isExpired(file, cutoff))
                .toList();
        expired.forEach(mediaStore::delete);
        return expired.size();
    }

    /**
     * Whether file sits directly inside a disasters/ drawer.
     *
     * @param file {@link Path} the candidate file
     * @return boolean true if file's parent directory is a disasters/ drawer
     */
    private static boolean isDrawerEntry(Path file) {
        Path parent = file.getParent();
        return parent != null && DRAWER_DIR.equals(parent.getFileName().toString());
    }

    /**
     * Whether file's filename-embedded filing time is older than cutoff. A name that doesn't parse
     * is never expired - it is left alone rather than guessed at.
     *
     * @param file {@link Path} the candidate file
     * @param cutoff {@link Instant} the retention cutoff
     * @return boolean true if file was filed before cutoff
     */
    private static boolean isExpired(Path file, Instant cutoff) {
        return parseFiledAt(file.getFileName().toString()).filter(filedAt -> filedAt.isBefore(cutoff)).isPresent();
    }

    /**
     * Builds the first candidate name not already occupied under drawer, trying the plain
     * timestamp-what name first, then a numeric suffix - the same collision idiom MediaStore itself
     * uses for a real move.
     *
     * @param drawer {@link Path} the drawer directory
     * @param stamp {@link String} the formatted filing timestamp
     * @param what {@link String} a short slug naming what this file is
     * @param extension {@link String} the file's extension, including its leading dot
     * @return {@link Path} a path in drawer that does not currently exist
     */
    private Path uniqueName(Path drawer, String stamp, String what, String extension) {
        Path candidate = drawer.resolve(stamp + "-" + what + extension);
        if (!mediaStore.exists(candidate)) {
            return candidate;
        }
        int n = 2;
        Path numbered;
        do {
            numbered = drawer.resolve(stamp + "-" + what + "-" + n + extension);
            n++;
        } while (mediaStore.exists(numbered));
        return numbered;
    }

    /**
     * Parses the leading timestamp off a filed entry's name, if it has one.
     *
     * @param filename {@link String} the file name to parse
     * @return an {@link Optional} {@link Instant} the embedded filing time, if the name parses
     */
    private static Optional<Instant> parseFiledAt(String filename) {
        Matcher matcher = TIMESTAMP_PREFIX.matcher(filename);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Extracts a file name's extension, including the leading dot.
     *
     * @param leaf {@link String} file name
     * @return {@link String} the extension including its leading dot, or empty string if none
     */
    private static String extensionOf(String leaf) {
        int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? "" : leaf.substring(dot);
    }
}
