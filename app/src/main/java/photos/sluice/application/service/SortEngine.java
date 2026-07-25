package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.SortUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.ImageDimensionsPort;
import photos.sluice.application.port.out.InboxScannerPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.dating.DateResolver;
import photos.sluice.domain.dating.ScopeSelector;
import photos.sluice.domain.dedup.ByteIdenticalDedup;
import photos.sluice.domain.dedup.ByteIdenticalDedup.DedupPlan;
import photos.sluice.domain.imaging.LowResGate;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.Confidence;
import photos.sluice.domain.model.DatedMedia;
import photos.sluice.domain.model.DateResult;
import photos.sluice.domain.model.Dimensions;
import photos.sluice.domain.model.HashedMedia;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.model.ScanResult;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.model.TakeoutSidecar;
import photos.sluice.domain.scan.MediaTypeDetector;
import photos.sluice.domain.scan.SidecarSweep;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// The one-pass Inbox -> Sorted/Review pipeline. Dates every scanned file, narrows to the
// requested scope, deletes what's already redundant, and routes undatable/low-res files to
// Review. Everything else gets sorted. A final whole-Inbox sweep then removes now-orphaned
// Takeout sidecars and any directory left empty of all files.
@Component
public class SortEngine implements SortUseCase {

    private static final String SIDECAR_SOURCE = "sidecar";
    private static final String REASON_UNSORTED = "unsorted-implausible-date";
    private static final String REASON_LOW_RES = "low-res";
    private static final String REASONS_FILE = "_reasons.txt";

    private final PathsPort pathsPort;
    private final InboxScannerPort inboxScanner;
    private final DateResolver dateResolver;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final ImageDimensionsPort imageDimensionsPort;
    private final MediaStore mediaStore;
    private final ByteIdenticalDedup dedup = new ByteIdenticalDedup();
    private final ScopeSelector scopeSelector = new ScopeSelector();
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();
    private final SidecarSweep sidecarSweep = new SidecarSweep();

    public SortEngine(PathsPort pathsPort, InboxScannerPort inboxScanner, DateResolver dateResolver,
            Sha256Port sha256Port, HashIndexPort hashIndexPort, ImageDimensionsPort imageDimensionsPort,
            MediaStore mediaStore) {
        this.pathsPort = pathsPort;
        this.inboxScanner = inboxScanner;
        this.dateResolver = dateResolver;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.imageDimensionsPort = imageDimensionsPort;
        this.mediaStore = mediaStore;
    }

    @Override
    public SortSummary sort(SortScope scope) {
        return sort(scope, ProgressCallback.NO_OP);
    }

    public SortSummary sort(SortScope scope, ProgressCallback progress) {
        // Every scanned file is dated before scope narrows anything, not just the files a caller
        // is about to process. OldestYear and OldestN need to compare dates across the whole
        // Inbox to pick the right subset. Scoping on partial date knowledge would pick the wrong
        // files.
        ScanResult scanResult = inboxScanner.scan(pathsPort.inbox());
        List<DatedMedia> allDated = scanResult.media().stream()
                .map(file -> new DatedMedia(file, dateResolver.resolve(file, scanResult.sidecars().get(file))))
                .toList();
        List<DatedMedia> inScope = scopeSelector.select(allDated, scope);
        Map<MediaFile, DateResult> dateByFile = new HashMap<>();
        inScope.forEach(dated -> dateByFile.put(dated.file(), dated.date()));

        // Sidecar consumption doesn't depend on what happens to its media file next - deleted as
        // a duplicate, or kept and sorted. Every in-scope file leaves the Inbox on every branch
        // below, so its sidecar is already spent regardless of which branch runs. This runs as
        // its own pass here, before dedup and routing, to keep that independence visible rather
        // than interleaving it into decisions it doesn't actually depend on.
        Set<Path> consumedSidecars = consumeSidecars(inScope, scanResult.sidecars());

        Set<String> libraryHashes = existingLibraryHashes();
        List<HashedMedia> hashed = inScope.stream()
                .map(dated -> new HashedMedia(dated.file(), sha256Port.hash(dated.file().path())))
                .toList();
        DedupPlan plan = dedup.plan(hashed, libraryHashes);

        plan.redundantVsLibrary().forEach(file -> mediaStore.delete(file.path()));
        plan.withinBatchDuplicates().forEach(file -> mediaStore.delete(file.path()));

        RoutingResult routing = routeSurvivors(plan.toSort(), dateByFile, progress);

        sweepOrphanedSidecarsAndEmptyDirectories(scanResult, inScope, consumedSidecars);

        return new SortSummary(inScope.size(), plan.redundantVsLibrary().size(), plan.withinBatchDuplicates().size(),
                routing.photosSorted, routing.videosSorted, routing.lowRes, routing.unsorted, consumedSidecars.size(),
                routing.lowConfidenceFiles, routing.unsortedFiles, routing.yearsSorted);
    }

    private Set<Path> consumeSidecars(List<DatedMedia> inScope, Map<MediaFile, TakeoutSidecar> sidecars) {
        // An "-edited" copy shares its original's sidecar - TakeoutSidecarPairer maps both media
        // files to the same JSON path. So the same path can come up more than once here. Set.add
        // returns false on the second occurrence, which is what keeps a shared sidecar from being
        // deleted and counted twice.
        Set<Path> deletedSidecars = new HashSet<>();
        for (DatedMedia dated : inScope) {
            TakeoutSidecar sidecar = sidecars.get(dated.file());
            // Only a sidecar that actually won the date-resolution chain is spent. One that
            // exists but lost - invalid, or coincidentally name-matched to unrelated JSON - is
            // left alone, per the schema-validation safety rule.
            if (sidecar != null && dated.date().source().equals(SIDECAR_SOURCE)
                    && deletedSidecars.add(sidecar.jsonPath())) {
                mediaStore.delete(sidecar.jsonPath());
            }
        }
        return deletedSidecars;
    }

    // Independent of consumeSidecars above. That method only spends a sidecar whose date actually
    // won for its file. This sweep instead treats a sidecar as spent purely because its owning
    // media is gone from its directory now, regardless of why. That catches unmatched sidecars
    // and ones whose media was deleted as a duplicate, so sidecars never pile up across
    // incremental year-by-year runs. A directory left empty of all files afterward is then
    // removed.
    //
    // "Remaining" is derived from the original scan rather than observed directly. Every in-scope
    // file is guaranteed to have left the Inbox by this point. dedup.plan's three buckets are a
    // total partition of inScope, and every one of toSort/redundantVsLibrary/withinBatchDuplicates
    // is either moved or deleted above. So the original scan's media and JSON lists, minus what
    // this run itself removed, already describe what's left.
    private void sweepOrphanedSidecarsAndEmptyDirectories(ScanResult scanResult, List<DatedMedia> inScope,
            Set<Path> consumedSidecars) {
        Set<Path> removedMediaPaths = inScope.stream().map(dated -> dated.file().path()).collect(Collectors.toSet());
        List<Path> remainingMediaPaths = scanResult.media().stream()
                .map(MediaFile::path)
                .filter(path -> !removedMediaPaths.contains(path))
                .toList();
        List<Path> remainingJsonPaths = scanResult.jsonPaths().stream()
                .filter(path -> !consumedSidecars.contains(path))
                .toList();
        for (Path orphaned : sidecarSweep.findOrphaned(remainingMediaPaths, remainingJsonPaths)) {
            mediaStore.delete(orphaned);
        }
        mediaStore.removeEmptyDirectories(pathsPort.inbox());
    }

    // A hash the index remembers is only treated as "already in the library" if at least one of
    // its recorded paths still exists on disk. The library lives outside this process's control -
    // it can be resynced, moved, or pruned independently. A stale index entry pointing at a
    // since-vanished file must never cause an otherwise-unique Inbox file to be deleted as a false
    // duplicate. The whole point of the index is that the bytes survive somewhere before anything
    // gets deleted on its word alone.
    private Set<String> existingLibraryHashes() {
        Set<String> result = new HashSet<>();
        hashIndexPort.load().forEach((hash, paths) -> {
            if (paths.stream().anyMatch(mediaStore::exists)) {
                result.add(hash);
            }
        });
        return result;
    }

    private RoutingResult routeSurvivors(List<MediaFile> toSort, Map<MediaFile, DateResult> dateByFile,
            ProgressCallback progress) {
        var routing = new RoutingResult();
        int total = toSort.size();
        int current = 0;
        for (MediaFile file : toSort) {
            // Every file here came from inScope, and dateByFile was built from that same list. So
            // this lookup always hits. requireNonNull asserts that invariant rather than silently
            // trusting it.
            DateResult date = Objects.requireNonNull(dateByFile.get(file));
            routeOneSurvivor(file, date, routing);
            progress.tick(++current, total);
        }
        return routing;
    }

    // Checked in this order, and only this order. An undatable file is routed to Review before
    // anything else even looks at it - "where should this land by date" is meaningless without
    // a usable date. The low-res gate only ever runs on a file that already has one.
    private void routeOneSurvivor(MediaFile file, DateResult date, RoutingResult routing) {
        String leaf = file.path().getFileName().toString();

        if (date.confidence() == Confidence.UNSORTABLE) {
            routeToReview(file, leaf, pathsPort.review().resolve("Unsorted"), REASON_UNSORTED);
            routing.unsorted++;
            routing.unsortedFiles.add(leaf);
            return;
        }

        // The scanner already filtered to recognized extensions, so classification always
        // succeeds for a file that reached this point.
        MediaType type = mediaTypeDetector.classify(file.path()).orElseThrow();
        boolean isVideo = type == MediaType.VIDEO;
        String extension = MediaTypeDetector.extensionOf(file.path());

        if (!isVideo && isLowRes(file, type, extension)) {
            routeToReview(file, leaf, pathsPort.review().resolve(yearMonthDash(date.when())), REASON_LOW_RES);
            routing.lowRes++;
            return;
        }

        String mediaFolder = isVideo ? "Videos" : "Photos";
        Path destDir = pathsPort.sorted().resolve(mediaFolder)
                .resolve(yearFolder(date.when())).resolve(monthFolder(date.when()));
        mediaStore.move(file.path(), destDir);
        routing.yearsSorted.add(date.when().getYear());
        if (isVideo) {
            routing.videosSorted++;
        } else {
            routing.photosSorted++;
        }
        if (date.confidence() == Confidence.LOW) {
            routing.lowConfidenceFiles.add(leaf + " (mtime " + date.when().toLocalDate() + ")");
        }
    }

    private boolean isLowRes(MediaFile file, MediaType type, String extension) {
        long size = mediaStore.size(file.path());
        Dimensions dimensions = imageDimensionsPort.read(file.path()).orElse(null);
        return LowResGate.isLowRes(size, dimensions, type, extension);
    }

    private void routeToReview(MediaFile file, String leaf, Path destDir, String reason) {
        mediaStore.move(file.path(), destDir);
        mediaStore.appendLine(destDir.resolve(REASONS_FILE), leaf + " - " + reason);
    }

    private static String yearFolder(LocalDateTime when) {
        return "%04d".formatted(when.getYear());
    }

    private static String monthFolder(LocalDateTime when) {
        return "%02d".formatted(when.getMonthValue());
    }

    private static String yearMonthDash(LocalDateTime when) {
        return yearFolder(when) + "-" + monthFolder(when);
    }

    private static final class RoutingResult {
        int photosSorted;
        int videosSorted;
        int lowRes;
        int unsorted;
        final List<String> lowConfidenceFiles = new ArrayList<>();
        final List<String> unsortedFiles = new ArrayList<>();
        final Set<Integer> yearsSorted = new HashSet<>();
    }
}
