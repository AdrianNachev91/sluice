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

// Promotes every media file still present under a Review folder into the library, re-dated via
// RescueDateResolver, then dissolves the folder if nothing was left behind. A file with no
// plausible date is skipped in place, never given a fabricated one.
@Component
public class RescueEngine implements RescueUseCase {

    private static final String REASONS_FILE = "_reasons.txt";

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final RescueDateResolver rescueDateResolver;
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    public RescueEngine(PathsPort pathsPort, MediaStore mediaStore, Sha256Port sha256Port,
            HashIndexPort hashIndexPort, RescueDateResolver rescueDateResolver) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.rescueDateResolver = rescueDateResolver;
    }

    @Override
    public RescueSummary rescue(String reviewFolder) {
        return rescue(reviewFolder, ProgressCallback.NO_OP, CancellationSignal.NEVER);
    }

    public RescueSummary rescue(String reviewFolder, ProgressCallback progress) {
        return rescue(reviewFolder, progress, CancellationSignal.NEVER);
    }

    public RescueSummary rescue(String reviewFolder, ProgressCallback progress, CancellationSignal cancellation) {
        Path reviewRoot = pathsPort.review();
        Path target = resolveWithinReview(reviewRoot, reviewFolder);
        String targetLeaf = target.getFileName().toString();
        Path libraryRoot = pathsPort.library();

        // Snapshotted once, before any move happens, and reused below to find leftover
        // _reasons.txt markers. The loop below only ever relocates recognized media files, never a
        // marker file, so this list's marker entries are still accurate afterward. No need to
        // re-walk the directory a second time.
        List<Path> allFiles = mediaStore.listFiles(target);
        int total = allFiles.size();
        int current = 0;
        var outcome = new RescueOutcome();
        // One session for the whole rescue loop. Each rescued file's index row is written and
        // flushed immediately, so a crash mid-run never leaves an already-moved file with no index
        // row. The header/leading-newline checks still only run once, instead of once per file.
        try (HashIndexPort.Session session = hashIndexPort.openSession()) {
            // Checked after each file, so an in-flight file is never interrupted; already-rescued
            // files stay rescued, matching the no-undo model.
            while (current < total && !cancellation.isCancelled()) {
                rescueOneFile(allFiles.get(current), targetLeaf, libraryRoot, outcome, session);
                progress.tick(++current, total);
            }
        }

        // All-or-nothing per folder: dissolving it (and the marker files inside it) only happens
        // once the pass reached every file AND none of them were skipped. Checking skipped alone
        // isn't enough once a pass can stop early. A cancelled run with zero skips so far would
        // otherwise delete the _reasons.txt markers while unvisited media still sits in the folder.
        boolean ranToCompletion = current == total;
        boolean folderRemoved = false;
        if (ranToCompletion && outcome.skipped.isEmpty()) {
            allFiles.stream()
                    .filter(file -> file.getFileName().toString().equals(REASONS_FILE))
                    .forEach(mediaStore::delete);
            mediaStore.removeIfEmptyOfFiles(target);
            folderRemoved = !mediaStore.exists(target);
        }

        return new RescueSummary(outcome.rescued, outcome.skipped, folderRemoved);
    }

    // Checked in this order, and only this order. A non-media file (a stray _reasons.txt, or
    // anything else left in the folder) is ignored outright - neither rescued nor skipped. Only a
    // real media file that also has no resolvable date counts as skipped.
    private void rescueOneFile(Path file, String targetLeaf, Path libraryRoot, RescueOutcome outcome,
            HashIndexPort.Session session) {
        Optional<MediaType> type = mediaTypeDetector.classify(file);
        if (type.isEmpty()) {
            return;
        }
        Optional<LocalDateTime> date = rescueDateResolver.resolve(new MediaFile(file), targetLeaf);
        if (date.isEmpty()) {
            outcome.skipped.add(file.getFileName().toString());
            return;
        }
        Path destDir = libraryRoot.resolve(type.get() == MediaType.VIDEO ? "Videos" : "Photos")
                .resolve(yearFolder(date.get())).resolve(monthFolder(date.get()));
        String hash = sha256Port.hash(file);
        Path dest = mediaStore.move(file, destDir);
        session.append(new IndexEntry(hash, dest));
        outcome.rescued++;
    }

    // A caller-supplied folder name must never resolve outside Review via a ".." segment. Normalize
    // first, then check containment, rather than string-matching for "..". A legitimately dotted
    // filename could trigger that as a false positive, and a smarter traversal could dodge it.
    private static Path resolveWithinReview(Path reviewRoot, String reviewFolder) {
        Path target = reviewRoot.resolve(reviewFolder).normalize();
        if (!target.startsWith(reviewRoot)) {
            throw new IllegalArgumentException("reviewFolder must stay under Review: " + reviewFolder);
        }
        return target;
    }

    private static String yearFolder(LocalDateTime when) {
        return "%04d".formatted(when.getYear());
    }

    private static String monthFolder(LocalDateTime when) {
        return "%02d".formatted(when.getMonthValue());
    }

    private static final class RescueOutcome {
        int rescued;
        final List<String> skipped = new ArrayList<>();
    }
}
