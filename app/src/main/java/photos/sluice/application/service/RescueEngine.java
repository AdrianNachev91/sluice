package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.RescueUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.dating.RescueDateResolver;
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
        Path reviewRoot = pathsPort.review();
        Path target = resolveWithinReview(reviewRoot, reviewFolder);
        String targetLeaf = target.getFileName().toString();
        Path libraryRoot = pathsPort.library();

        // Snapshotted once, before any move happens, and reused below to find leftover
        // _reasons.txt markers. Safe: the loop below only ever relocates recognized media files,
        // never a marker file, so this list's marker entries are still accurate afterward - no
        // need to re-walk the directory a second time.
        List<Path> allFiles = mediaStore.listFiles(target);
        var outcome = new RescueOutcome();
        for (Path file : allFiles) {
            rescueOneFile(file, targetLeaf, libraryRoot, outcome);
        }

        hashIndexPort.append(outcome.indexEntries);

        // All-or-nothing per folder: dissolving it (and the marker files inside it) only happens
        // once every file rescue looked at actually got rescued. A single skipped file anywhere
        // keeps the whole folder - and everything still in it - untouched.
        boolean folderRemoved = false;
        if (outcome.skipped.isEmpty()) {
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
    private void rescueOneFile(Path file, String targetLeaf, Path libraryRoot, RescueOutcome outcome) {
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
        outcome.indexEntries.add(new IndexEntry(hash, dest));
        outcome.rescued++;
    }

    // A caller-supplied folder name must never resolve outside Review via a ".." segment;
    // normalize first, then check containment, rather than string-matching for ".." (which a
    // legitimately dotted filename could trigger as a false positive, or a smarter traversal could
    // dodge).
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
        final List<IndexEntry> indexEntries = new ArrayList<>();
    }
}
