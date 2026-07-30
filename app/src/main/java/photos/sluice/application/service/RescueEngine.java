package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.RescueUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.rescue.RescueSummary;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Promotes every media file still present under a Review folder into the library, re-dated via
 * {@link RescueDateResolver}, then dissolves the folder if nothing was left behind. A file with
 * no plausible date is skipped in place and never given a fabricated one.
 */
@Component
public class RescueEngine implements RescueUseCase {

    private static final String REASONS_FILE = "_reasons.txt";

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final RescueDateResolver rescueDateResolver;
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    /**
     * Creates a rescue engine backed by the given ports.
     *
     * @param pathsPort {@link PathsPort} resolves Review and library roots
     * @param mediaStore {@link MediaStore} file operations on Review and library files
     * @param sha256Port {@link Sha256Port} hashes rescued files for the index
     * @param hashIndexPort {@link HashIndexPort} records rescued files in the hash index
     * @param rescueDateResolver {@link RescueDateResolver} resolves a rescue date per file
     */
    public RescueEngine(final PathsPort pathsPort, final MediaStore mediaStore, final Sha256Port sha256Port,
                        final HashIndexPort hashIndexPort, final RescueDateResolver rescueDateResolver) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.rescueDateResolver = rescueDateResolver;
    }

    /**
     * Rescues a Review folder using no-op progress and cancellation.
     *
     * @param reviewFolder {@link String} folder name under Review to rescue
     * @return {@link RescueSummary} summary of rescued and skipped files
     */
    @Override
    public RescueSummary rescue(final String reviewFolder) {
        return this.rescue(reviewFolder, ProgressCallback.NO_OP, CancellationSignal.NEVER);
    }

    /**
     * Rescues a Review folder, reporting progress, never cancellable.
     *
     * @param reviewFolder {@link String} folder name under Review to rescue
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @return {@link RescueSummary} summary of rescued and skipped files
     */
    public RescueSummary rescue(final String reviewFolder, final ProgressCallback progress) {
        return this.rescue(reviewFolder, progress, CancellationSignal.NEVER);
    }

    /**
     * Promotes every recognized media file still in the named Review folder into the library,
     * then dissolves the folder if the whole pass completed with nothing skipped.
     *
     * @param reviewFolder {@link String} folder name under Review to rescue
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @param cancellation {@link CancellationSignal} checked between files to allow early stop
     * @return {@link RescueSummary} summary of rescued and skipped files, and whether the folder was removed
     */
    public RescueSummary rescue(final String reviewFolder, final ProgressCallback progress,
                                final CancellationSignal cancellation) {
        final Path reviewRoot = this.pathsPort.review();
        final Path target = resolveWithinReview(reviewRoot, reviewFolder);
        final String targetLeaf = target.getFileName().toString();
        final Path libraryRoot = this.pathsPort.library();

        // Snapshotted once, before any move happens, and reused below to find leftover
        // _reasons.txt markers. The loop below only ever relocates recognized media files, never a
        // marker file, so this list's marker entries are still accurate afterward. No need to
        // re-walk the directory a second time.
        final List<Path> allFiles = this.mediaStore.listFiles(target);
        final int total = allFiles.size();
        int current = 0;
        final var outcome = new RescueOutcome();
        // One session for the whole rescue loop. Each rescued file's index row is written and
        // flushed immediately, so a crash mid-run never leaves an already-moved file with no index
        // row. The header/leading-newline checks still only run once, instead of once per file.
        try (final HashIndexPort.Session session = this.hashIndexPort.openSession()) {
            // Checked after each file, so an in-flight file is never interrupted; already-rescued
            // files stay rescued, matching the no-undo model.
            while (current < total && !cancellation.isCancelled()) {
                this.rescueOneFile(allFiles.get(current), targetLeaf, libraryRoot, outcome, session);
                progress.tick(++current, total);
            }
        }

        // All-or-nothing per folder: dissolving it (and the marker files inside it) only happens
        // once the pass reached every file AND none of them were skipped. Checking skipped alone
        // isn't enough once a pass can stop early. A cancelled run with zero skips so far would
        // otherwise delete the _reasons.txt markers while unvisited media still sits in the folder.
        final boolean ranToCompletion = current == total;
        boolean folderRemoved = false;
        if (ranToCompletion && outcome.skipped.isEmpty()) {
            allFiles.stream()
                    .filter(file -> file.getFileName().toString().equals(REASONS_FILE))
                    .forEach(this.mediaStore::delete);
            this.mediaStore.removeIfEmptyOfFiles(target);
            folderRemoved = !this.mediaStore.exists(target);
        }

        return new RescueSummary(outcome.rescued, outcome.skipped, folderRemoved);
    }

    /**
     * Checked in this order, and only this order. A non-media file (a stray _reasons.txt, or
     * anything else left in the folder) is ignored outright - neither rescued nor skipped. Only a
     * real media file that also has no resolvable date counts as skipped.
     *
     * @param file {@link Path} candidate file from the Review folder
     * @param targetLeaf {@link String} name of the Review folder being rescued
     * @param libraryRoot {@link Path} root of the library to move rescued files into
     * @param outcome {@link RescueOutcome} accumulator for rescued count and skipped names
     * @param session {@link HashIndexPort.Session} hash-index session to append rescued entries
     */
    private void rescueOneFile(final Path file, final String targetLeaf, final Path libraryRoot,
                               final RescueOutcome outcome,
                               final HashIndexPort.Session session) {
        final Optional<MediaType> type = this.mediaTypeDetector.classify(file);
        if (type.isEmpty()) {
            return;
        }
        final Optional<LocalDateTime> date = this.rescueDateResolver.resolve(new MediaFile(file), targetLeaf);
        if (date.isEmpty()) {
            outcome.skipped.add(file.getFileName().toString());
            return;
        }
        final Path destDir = libraryRoot.resolve(type.get() == MediaType.VIDEO ? "Videos" : "Photos")
                .resolve(yearFolder(date.get())).resolve(monthFolder(date.get()));
        final String hash = this.sha256Port.hash(file);
        final Path dest = this.mediaStore.move(file, destDir);
        session.append(new IndexEntry(hash, dest));
        outcome.rescued++;
    }

    /**
     * A caller-supplied folder name must never resolve outside Review via a ".." segment. Normalize
     * first, then check containment, rather than string-matching for "..". A legitimately dotted
     * filename could trigger that as a false positive, and a smarter traversal could dodge it.
     *
     * @param reviewRoot {@link Path} root of the Review folder
     * @param reviewFolder {@link String} caller-supplied folder name to resolve
     * @return {@link Path} normalized path guaranteed to stay under reviewRoot
     */
    private static Path resolveWithinReview(final Path reviewRoot, final String reviewFolder) {
        final Path target = reviewRoot.resolve(reviewFolder).normalize();
        if (!target.startsWith(reviewRoot)) {
            throw new IllegalArgumentException("reviewFolder must stay under Review: " + reviewFolder);
        }
        return target;
    }

    /**
     * Formats the year as a four-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the four-digit year folder name
     */
    private static String yearFolder(final LocalDateTime when) {
        return "%04d".formatted(when.getYear());
    }

    /**
     * Formats the month as a two-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the two-digit month folder name
     */
    private static String monthFolder(final LocalDateTime when) {
        return "%02d".formatted(when.getMonthValue());
    }

    /**
     * Accumulates one rescue pass's outcome as it goes: how many files were rescued, and the file
     * names of any skipped for lacking a plausible date.
     */
    private static final class RescueOutcome {
        int rescued;
        final List<String> skipped = new ArrayList<>();
    }
}
