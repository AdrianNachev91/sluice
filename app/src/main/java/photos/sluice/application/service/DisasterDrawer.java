package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.job.CancellationSignal;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <prepDir>/disasters/} is the audit drawer for a prep dir's own recovery events. A
 * ledger file or index.json this app decided was too damaged to salvage gets filed here via
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
    // older than 30 days." Shared with PrepDirRemedies.discard()'s global graveyard folders via
    // DisasterTimestamp, so both stay parseable by the same future retention sweep.
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})-.*");
    // A discard() graveyard folder's own name is <scope>-<timestamp>, not <timestamp>-<what> - the
    // timestamp is a trailing, not leading, segment, and scope itself may contain hyphens. Anchored
    // on the fixed-length timestamp shape at the end of the name rather than splitting on a hyphen.
    private static final Pattern TIMESTAMP_SUFFIX = Pattern.compile(".*-(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})$");

    private final MediaStore mediaStore;

    /**
     * Creates a drawer wired to its port.
     *
     * @param mediaStore {@link MediaStore} filesystem effects (move, list, delete)
     */
    public DisasterDrawer(final MediaStore mediaStore) {
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
    public Path file(final Path prepDir, final Path source, final String what) {
        final Path drawer = prepDir.resolve(DRAWER_DIR);
        final String extension = extensionOf(source.getFileName().toString());
        final String stamp = DisasterTimestamp.now();
        // The drawer sits inside the prep dir this file already lives in, so the move is a rename
        // with nothing to interrupt and nothing to report.
        return this.mediaStore.moveTo(source, this.uniqueName(drawer, stamp, what, extension),
                CancellationSignal.NEVER, TransferProgress.NONE);
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
    public Path write(final Path prepDir, final String what, final String content) {
        final Path drawer = prepDir.resolve(DRAWER_DIR);
        this.mediaStore.ensureDirectory(drawer);
        final String stamp = DisasterTimestamp.now();
        final Path dest = this.uniqueName(drawer, stamp, what, ".txt");
        this.mediaStore.write(dest, content);
        return dest;
    }

    /**
     * Deletes every disaster-drawer entry under siftPrepRoot whose filename-embedded filing time is
     * older than the 30-day retention window. Recognized by two things together: sitting directly
     * inside a {@code disasters/} folder, and carrying the leading timestamp this drawer writes. A
     * file failing either is left alone, never deleted.
     *
     * @param siftPrepRoot {@link Path} the root directory holding every prep dir
     * @return int the number of entries deleted
     */
    public int sweepExpired(final Path siftPrepRoot) {
        if (!this.mediaStore.exists(siftPrepRoot)) {
            return 0;
        }
        final Instant cutoff = Instant.now().minus(RETENTION);
        final List<Path> expired = this.mediaStore.listFiles(siftPrepRoot).stream()
                .filter(DisasterDrawer::isDrawerEntry)
                .filter(file -> isExpired(file, cutoff, TIMESTAMP_PREFIX))
                .toList();
        expired.forEach(this.mediaStore::delete);
        return expired.size();
    }

    /**
     * Deletes every {@code PrepDirRemedies.discard()} graveyard folder under graveyardRoot
     * ({@code logs/archives/}) whose own {@code <scope>-<timestamp>} name is older than the 30-day
     * retention window. This is the directory-level counterpart to {@link #sweepExpired}. A folder
     * whose name doesn't parse is left alone, never deleted. An expired folder has every file under it
     * deleted, then the folder itself (and any now-empty subfolder, e.g. a graveyarded
     * {@code disasters/} drawer) pruned via {@link MediaStore#removeIfEmptyOfFiles}.
     *
     * <p>A graveyard folder holding zero files is never discovered here - it is invisible to this
     * file-based scan. {@code discard()} always creates the folder up front, even when a mangled
     * prep dir turns out to have nothing worth keeping. Left unswept, but harmless; an empty folder
     * costs nothing.
     *
     * @param graveyardRoot {@link Path} the discard graveyard root ({@code logs/archives/})
     * @return int the number of graveyard folders deleted
     */
    public int sweepExpiredGraveyard(final Path graveyardRoot) {
        if (!this.mediaStore.exists(graveyardRoot)) {
            return 0;
        }
        final List<Path> allFiles = this.mediaStore.listFiles(graveyardRoot);
        final List<Path> graveyards = allFiles.stream()
                .map(file -> graveyardRoot.resolve(graveyardRoot.relativize(file).getName(0)))
                .distinct()
                .toList();
        final Instant cutoff = Instant.now().minus(RETENTION);
        final List<Path> expired = graveyards.stream()
                .filter(dir -> isExpired(dir, cutoff, TIMESTAMP_SUFFIX)).toList();
        expired.forEach(dir -> this.deleteGraveyard(dir, allFiles));
        return expired.size();
    }

    /**
     * Deletes every file under dir (drawn from the already-listed allFiles, never re-walked), then
     * prunes dir itself once empty of files.
     *
     * @param dir {@link Path} the graveyard folder to delete
     * @param allFiles a {@link List} of {@link Path} every file found under the graveyard root
     */
    private void deleteGraveyard(final Path dir, final List<Path> allFiles) {
        allFiles.stream().filter(file -> file.startsWith(dir)).forEach(this.mediaStore::delete);
        this.mediaStore.removeIfEmptyOfFiles(dir);
    }

    /**
     * Whether a name's own embedded timestamp is older than cutoff. A name that doesn't parse is
     * never expired - it is left alone rather than guessed at.
     *
     * @param path {@link Path} the candidate file or folder
     * @param cutoff {@link Instant} the retention cutoff
     * @param timestampPattern {@link Pattern} where in the name the timestamp sits, capturing it in
     *         group 1
     * @return boolean true if the name carries a time before cutoff
     */
    private static boolean isExpired(final Path path, final Instant cutoff, final Pattern timestampPattern) {
        return timestampIn(path.getFileName().toString(), timestampPattern)
                .filter(at -> at.isBefore(cutoff)).isPresent();
    }

    /**
     * Parses the timestamp out of a name, if it has one.
     *
     * @param name {@link String} the file or folder's own name
     * @param timestampPattern {@link Pattern} where in the name the timestamp sits, capturing it in
     *         group 1
     * @return an {@link Optional} {@link Instant} the embedded time, if the name parses
     */
    private static Optional<Instant> timestampIn(final String name, final Pattern timestampPattern) {
        final Matcher matcher = timestampPattern.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(matcher.group(1), DisasterTimestamp.FORMAT).toInstant(ZoneOffset.UTC));
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether file sits directly inside a disasters/ drawer.
     *
     * @param file {@link Path} the candidate file
     * @return boolean true if file's parent directory is a disasters/ drawer
     */
    private static boolean isDrawerEntry(final Path file) {
        final Path parent = file.getParent();
        return parent != null && DRAWER_DIR.equals(parent.getFileName().toString());
    }

    /**
     * Builds the first candidate name not already occupied under drawer, trying the plain
     * timestamp-what name first, then a numeric suffix. That's the same collision idiom MediaStore
     * itself uses for a real move.
     *
     * @param drawer {@link Path} the drawer directory
     * @param stamp {@link String} the formatted filing timestamp
     * @param what {@link String} a short slug naming what this file is
     * @param extension {@link String} the file's extension, including its leading dot
     * @return {@link Path} a path in drawer that does not currently exist
     */
    private Path uniqueName(final Path drawer, final String stamp, final String what, final String extension) {
        final Path candidate = drawer.resolve(stamp + "-" + what + extension);
        if (!this.mediaStore.exists(candidate)) {
            return candidate;
        }
        int n = 2;
        Path numbered;
        do {
            numbered = drawer.resolve(stamp + "-" + what + "-" + n + extension);
            n++;
        } while (this.mediaStore.exists(numbered));
        return numbered;
    }

    /**
     * Extracts a file name's extension, including the leading dot.
     *
     * @param leaf {@link String} file name
     * @return {@link String} the extension including its leading dot, or empty string if none
     */
    private static String extensionOf(final String leaf) {
        final int dot = leaf.lastIndexOf('.');
        return dot <= 0 ? "" : leaf.substring(dot);
    }
}
