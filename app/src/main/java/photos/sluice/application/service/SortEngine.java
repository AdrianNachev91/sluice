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
import photos.sluice.domain.job.CancellationSignal;
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

/**
 * The one-pass Inbox-to-Sorted/Review pipeline. Dates every scanned file, narrows to the
 * requested scope, deletes what is already redundant, and routes undatable or low-resolution
 * files to Review. Everything else gets sorted.
 *
 * <p>A final whole-Inbox sweep then removes now-orphaned Takeout sidecars and any directory left
 * empty of all files.
 */
@Component
public class SortEngine implements SortUseCase {

    private static final String SIDECAR_SOURCE = "sidecar";
    private static final String REASON_UNSORTED = "unsorted-implausible-date";
    private static final String REASON_LOW_RES = "low-res";
    private static final String REASONS_FILE = "_reasons.txt";

    // Returned when cancellation lands during dating, before any file is moved, deleted, or
    // written - a clean abort with nothing to report.
    private static final SortSummary EMPTY_SORT_SUMMARY =
            new SortSummary(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Set.of());

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

    /**
     * Creates a SortEngine wired to its ports.
     *
     * @param pathsPort {@link PathsPort} resolves the Inbox/Sorted/Review roots
     * @param inboxScanner {@link InboxScannerPort} scans the Inbox for media and sidecars
     * @param dateResolver {@link DateResolver} resolves a trusted date per file
     * @param sha256Port {@link Sha256Port} hashes file contents for dedup
     * @param hashIndexPort {@link HashIndexPort} looks up hashes already in the library
     * @param imageDimensionsPort {@link ImageDimensionsPort} reads image pixel dimensions
     * @param mediaStore {@link MediaStore} moves, deletes, and inspects files
     */
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

    /**
     * Sorts the given scope with no progress reporting or cancellation support.
     *
     * @param scope {@link SortScope} which files to sort this run
     * @return {@link SortSummary} summary of what was sorted, deduped, and routed
     */
    @Override
    public SortSummary sort(SortScope scope) {
        return sort(scope, ProgressCallback.NO_OP, CancellationSignal.NEVER);
    }

    /**
     * Sorts the given scope, reporting progress but not cancellable.
     *
     * @param scope {@link SortScope} which files to sort this run
     * @param progress {@link ProgressCallback} receives per-file progress ticks
     * @return {@link SortSummary} summary of what was sorted, deduped, and routed
     */
    public SortSummary sort(SortScope scope, ProgressCallback progress) {
        return sort(scope, progress, CancellationSignal.NEVER);
    }

    /**
     * Sorts the given scope with progress reporting and cancellation support. The full sort
     * entry point that the parameter-light overloads delegate to.
     *
     * @param scope {@link SortScope} which files to sort this run
     * @param progress {@link ProgressCallback} receives per-file progress ticks
     * @param cancellation {@link CancellationSignal} checked to allow a clean early abort
     * @return {@link SortSummary} summary of what was sorted, deduped, and routed
     */
    public SortSummary sort(SortScope scope, ProgressCallback progress, CancellationSignal cancellation) {
        // Every scanned file is dated before scope narrows anything, not just the files a caller
        // is about to process. OldestYear and OldestN need to compare dates across the whole
        // Inbox to pick the right subset. Scoping on partial date knowledge would pick the wrong
        // files.
        //
        // Dating is pure in-memory computation. Nothing is moved, deleted, or written yet. A
        // cancellation seen here is a clean abort with zero side effects. The check exists only to
        // stop wasted work quickly on the pass most likely to run long: it spans the whole Inbox,
        // not just the requested scope.
        ScanResult scanResult = inboxScanner.scan(pathsPort.inbox());
        List<DatedMedia> allDated = new ArrayList<>();
        for (MediaFile file : scanResult.media()) {
            if (cancellation.isCancelled()) {
                return EMPTY_SORT_SUMMARY;
            }
            allDated.add(new DatedMedia(file, dateResolver.resolve(file, scanResult.sidecars().get(file))));
        }
        List<DatedMedia> inScope = scopeSelector.select(allDated, scope);
        Map<MediaFile, DateResult> dateByFile = new HashMap<>();
        inScope.forEach(dated -> dateByFile.put(dated.file(), dated.date()));

        Set<String> libraryHashes = existingLibraryHashes();
        List<HashedMedia> hashed = inScope.stream()
                .map(dated -> new HashedMedia(dated.file(), sha256Port.hash(dated.file().path())))
                .toList();
        DedupPlan plan = dedup.plan(hashed, libraryHashes);

        plan.redundantVsLibrary().forEach(file -> mediaStore.delete(file.path()));
        plan.withinBatchDuplicates().forEach(file -> mediaStore.delete(file.path()));

        RoutingResult routing = routeSurvivors(plan.toSort(), dateByFile, progress, cancellation);

        // A cancelled routing pass can stop before every survivor is routed. Those unrouted files
        // never actually left the Inbox, even though scope selection picked them. This is the set
        // of files that genuinely left this run, covering every outcome bucket: library-redundant,
        // within-batch duplicate, or actually routed.
        List<MediaFile> actuallyRemoved = new ArrayList<>(plan.redundantVsLibrary());
        actuallyRemoved.addAll(plan.withinBatchDuplicates());
        actuallyRemoved.addAll(routing.routedFiles);

        // Sidecar consumption doesn't depend on which bucket a file lands in - deleted as a
        // duplicate, or routed. It only depends on the file having actually left the Inbox, so
        // this runs against actuallyRemoved, after routing, rather than the full in-scope list
        // before it. A file routing never reached keeps its sidecar for a future run.
        Set<Path> consumedSidecars = consumeSidecars(actuallyRemoved, dateByFile, scanResult.sidecars());

        sweepOrphanedSidecarsAndEmptyDirectories(scanResult, actuallyRemoved, consumedSidecars);

        return new SortSummary(actuallyRemoved.size(), plan.redundantVsLibrary().size(),
                plan.withinBatchDuplicates().size(), routing.photosSorted, routing.videosSorted, routing.lowRes,
                routing.unsorted, consumedSidecars.size(), routing.lowConfidenceFiles, routing.unsortedFiles,
                routing.yearsSorted);
    }

    /**
     * Deletes the Takeout JSON sidecar of each removed file whose date the sidecar actually won,
     * returning the set of sidecar paths deleted.
     *
     * @param actuallyRemoved a {@link List} of {@link MediaFile} files that genuinely left the Inbox this run
     * @param dateByFile a {@link Map} of {@link MediaFile} to {@link DateResult} resolved date for each in-scope file
     * @param sidecars a {@link Map} of {@link MediaFile} to {@link TakeoutSidecar} sidecar JSON mapped by its owning media file
     * @return a {@link Set} of {@link Path} paths of the sidecars deleted as consumed
     */
    private Set<Path> consumeSidecars(List<MediaFile> actuallyRemoved, Map<MediaFile, DateResult> dateByFile,
            Map<MediaFile, TakeoutSidecar> sidecars) {
        // An "-edited" copy shares its original's sidecar - TakeoutSidecarPairer maps both media
        // files to the same JSON path. So the same path can come up more than once here. Set.add
        // returns false on the second occurrence, which is what keeps a shared sidecar from being
        // deleted and counted twice.
        Set<Path> deletedSidecars = new HashSet<>();
        for (MediaFile file : actuallyRemoved) {
            TakeoutSidecar sidecar = sidecars.get(file);
            DateResult date = Objects.requireNonNull(dateByFile.get(file));
            // Only a sidecar that actually won the date-resolution chain is spent. One that
            // exists but lost - invalid, or coincidentally name-matched to unrelated JSON - is
            // left alone, per the schema-validation safety rule.
            if (sidecar != null && date.source().equals(SIDECAR_SOURCE)
                    && deletedSidecars.add(sidecar.jsonPath())) {
                mediaStore.delete(sidecar.jsonPath());
            }
        }
        return deletedSidecars;
    }

    /**
     * Independent of consumeSidecars above. That method only spends a sidecar whose date actually
     * won for its file. This sweep instead treats a sidecar as spent purely because its owning
     * media is gone from its directory now, regardless of why. That catches unmatched sidecars
     * and ones whose media was deleted as a duplicate, so sidecars never pile up across
     * incremental year-by-year runs. A directory left empty of all files afterward is then
     * removed.
     *
     * <p>"Remaining" is derived from the original scan rather than observed directly, so the caller
     * passes exactly the files that actually left the Inbox this run, not every in-scope file. A
     * cancelled routing pass can stop partway through plan.toSort(). Files still sitting in the
     * Inbox after that must not be treated as gone, or their sidecar gets deleted out from under
     * them while they're still there awaiting a future run.
     *
     * @param scanResult {@link ScanResult} the original whole-Inbox scan
     * @param actuallyRemoved a {@link List} of {@link MediaFile} files that genuinely left the Inbox this run
     * @param consumedSidecars a {@link Set} of {@link Path} sidecars already deleted as consumed
     */
    private void sweepOrphanedSidecarsAndEmptyDirectories(ScanResult scanResult, List<MediaFile> actuallyRemoved,
            Set<Path> consumedSidecars) {
        Set<Path> removedMediaPaths = actuallyRemoved.stream().map(MediaFile::path).collect(Collectors.toSet());
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

    /**
     * A hash the index remembers is only treated as "already in the library" if at least one of
     * its recorded paths still exists on disk. The library lives outside this process's control -
     * it can be resynced, moved, or pruned independently. A stale index entry pointing at a
     * since-vanished file must never cause an otherwise-unique Inbox file to be deleted as a false
     * duplicate. The whole point of the index is that the bytes survive somewhere before anything
     * gets deleted on its word alone.
     *
     * @return a {@link Set} of {@link String} hashes from the index confirmed still present on disk
     */
    private Set<String> existingLibraryHashes() {
        Set<String> result = new HashSet<>();
        hashIndexPort.load().forEach((hash, paths) -> {
            if (paths.stream().anyMatch(mediaStore::exists)) {
                result.add(hash);
            }
        });
        return result;
    }

    /**
     * Routes each survivor to its destination in turn, stopping early on cancellation.
     *
     * @param toSort a {@link List} of {@link MediaFile} files that survived dedup and are ready to route
     * @param dateByFile a {@link Map} of {@link MediaFile} to {@link DateResult} resolved date for each in-scope file
     * @param progress {@link ProgressCallback} receives per-file progress ticks
     * @param cancellation {@link CancellationSignal} checked between files to allow early stop
     * @return {@link RoutingResult} tally of the routing outcomes
     */
    private RoutingResult routeSurvivors(List<MediaFile> toSort, Map<MediaFile, DateResult> dateByFile,
            ProgressCallback progress, CancellationSignal cancellation) {
        var routing = new RoutingResult();
        int total = toSort.size();
        int current = 0;
        // Checked after the move so an in-flight file is never interrupted; already-moved files
        // stay moved, matching the no-undo model.
        while (current < total && !cancellation.isCancelled()) {
            MediaFile file = toSort.get(current);
            // Every file here came from inScope, and dateByFile was built from that same list. So
            // this lookup always hits. requireNonNull asserts that invariant rather than silently
            // trusting it.
            DateResult date = Objects.requireNonNull(dateByFile.get(file));
            routeOneSurvivor(file, date, routing);
            routing.routedFiles.add(file);
            progress.tick(++current, total);
        }
        return routing;
    }

    /**
     * Checked in this order, and only this order. An undatable file is routed to Review before
     * anything else even looks at it - "where should this land by date" is meaningless without
     * a usable date. The low-res gate only ever runs on a file that already has one.
     *
     * @param file {@link MediaFile} the survivor being routed
     * @param date {@link DateResult} its resolved date
     * @param routing {@link RoutingResult} tallies updated with this file's outcome
     */
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

    /**
     * Checks whether a file falls below the low-resolution threshold for its type.
     *
     * @param file {@link MediaFile} the file to check
     * @param type {@link MediaType} its media type
     * @param extension {@link String} its file extension
     * @return boolean true if the file counts as low-res
     */
    private boolean isLowRes(MediaFile file, MediaType type, String extension) {
        long size = mediaStore.size(file.path());
        Dimensions dimensions = imageDimensionsPort.read(file.path()).orElse(null);
        return LowResGate.isLowRes(size, dimensions, type, extension);
    }

    /**
     * Moves a file into a Review destination and appends its reason line.
     *
     * @param file {@link MediaFile} the file being set aside
     * @param leaf {@link String} its file name
     * @param destDir {@link Path} the Review destination folder
     * @param reason {@link String} short label recorded in the reasons file
     */
    private void routeToReview(MediaFile file, String leaf, Path destDir, String reason) {
        mediaStore.move(file.path(), destDir);
        mediaStore.appendLine(destDir.resolve(REASONS_FILE), leaf + " - " + reason);
    }

    /**
     * Formats the year as a four-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the four-digit year folder name
     */
    private static String yearFolder(LocalDateTime when) {
        return "%04d".formatted(when.getYear());
    }

    /**
     * Formats the month as a two-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the two-digit month folder name
     */
    private static String monthFolder(LocalDateTime when) {
        return "%02d".formatted(when.getMonthValue());
    }

    /**
     * Formats the date as a dashed year-and-month folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the dashed year-and-month folder name
     */
    private static String yearMonthDash(LocalDateTime when) {
        return yearFolder(when) + "-" + monthFolder(when);
    }

    /**
     * Accumulates one routing pass's tallies as it goes. Counts per outcome bucket, file names
     * worth flagging back to the caller (low-confidence dates, unsorted files), the distinct
     * years actually sorted, and every file the pass routed.
     */
    private static final class RoutingResult {
        int photosSorted;
        int videosSorted;
        int lowRes;
        int unsorted;
        final List<String> lowConfidenceFiles = new ArrayList<>();
        final List<String> unsortedFiles = new ArrayList<>();
        final Set<Integer> yearsSorted = new HashSet<>();
        final List<MediaFile> routedFiles = new ArrayList<>();
    }
}
