package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.SortUseCase;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.ImageDimensionsPort;
import photos.sluice.application.port.out.InboxScannerPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.ProgressPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
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
import photos.sluice.domain.model.Numerals;
import photos.sluice.domain.model.ScanResult;
import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.model.TakeoutSidecar;
import photos.sluice.domain.paths.SortFolderNames;
import photos.sluice.domain.review.ReasonNotes;
import photos.sluice.domain.scan.MediaTypeDetector;
import photos.sluice.domain.scan.SidecarSweep;

import java.nio.file.Path;
import java.time.LocalDate;
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
    // Read by whoever opens the folder, so these are words rather than slugs. Short, because one is
    // repeated on every line of a note.
    private static final String REASON_UNSORTED = "no date could be read";
    private static final String REASON_LOW_RES = "too small to sift";

    // The pairing canary's threshold. Google's own sidecar naming scheme changes at export time,
    // not per-photo. A real change lands across the whole export at once rather than mixing, so
    // there is no "mostly paired" middle ground once the scheme itself has moved. Picked to be
    // unmistakable rather than clever: this flags a collapse, not a quality metric.
    private static final double PAIRING_COLLAPSE_THRESHOLD = 0.05;

    private static final String FINDING_DATES = "Finding dates...";
    private static final String CHECKING_COPIES = "Checking for duplicates...";
    private static final String SORTING = "Sorting...";

    // What a sort reports, in order.
    static final List<String> PHASES = List.of(FINDING_DATES, CHECKING_COPIES, SORTING);

    // Returned when cancellation lands during dating or hashing. Those are the two passes before
    // anything is moved, deleted or written, so it is a clean abort with nothing to report. Its
    // counts are what an empty Inbox also produces, and the cancelled flag is what tells them apart.
    private static final SortSummary STOPPED_BEFORE_ANYTHING_MOVED =
            new SortSummary(0, 0, 0, 0, 0, 0, 0, 0, List.of(), SortSummary.Guessed.NONE, List.of(),
                    Set.of(), List.of(), true, 0);

    private final PathsPort pathsPort;
    private final InboxScannerPort inboxScanner;
    private final DateResolver dateResolver;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final ImageDimensionsPort imageDimensionsPort;
    private final MediaStore mediaStore;
    private final PhaseRunner phaseRunner;
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
     * @param progressPort {@link ProgressPort} where this engine reports its own stages
     */
    public SortEngine(final PathsPort pathsPort, final InboxScannerPort inboxScanner, final DateResolver dateResolver,
                      final Sha256Port sha256Port, final HashIndexPort hashIndexPort,
                      final ImageDimensionsPort imageDimensionsPort,
                      final MediaStore mediaStore, final ProgressPort progressPort) {
        this.pathsPort = pathsPort;
        this.inboxScanner = inboxScanner;
        this.dateResolver = dateResolver;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.imageDimensionsPort = imageDimensionsPort;
        this.mediaStore = mediaStore;
        this.phaseRunner = new PhaseRunner(progressPort);
    }

    /**
     * Sorts the given scope with no cancellation support.
     *
     * @param scope {@link SortScope} which files to sort this run
     * @return {@link SortSummary} summary of what was sorted, deduped, and routed
     */
    @Override
    public SortSummary sort(final SortScope scope) {
        return this.sort(scope, CancellationSignal.NEVER);
    }

    /**
     * Sorts the given scope with cancellation support.
     *
     * <p>Takes no progress callback. This engine reports its own three stages, so a caller
     * bracketing the whole call in one phase would report a single stage over all of them.
     *
     * @param scope {@link SortScope} which files to sort this run
     * @param cancellation {@link CancellationSignal} checked to allow a clean early abort
     * @return {@link SortSummary} summary of what was sorted, deduped, and routed
     */
    public SortSummary sort(final SortScope scope,
                            final CancellationSignal cancellation) {
        // Every scanned file is dated before scope narrows anything, not just the files a caller
        // is about to process. OldestYear and OldestN need to compare dates across the whole
        // Inbox to pick the right subset. Scoping on partial date knowledge would pick the wrong
        // files.
        //
        // Dating is pure in-memory computation. Nothing is moved, deleted, or written yet. A
        // cancellation seen here is a clean abort with zero side effects. The check exists only to
        // stop wasted work quickly on the pass most likely to run long: it spans the whole Inbox,
        // not just the requested scope.
        // Three stages rather than one, because the two before routing are where a sort spends most
        // of its time and neither moves a file. Reported as one phase, the bar ran indeterminate
        // through the reading and the hashing and then measured only the fast part.
        final ScanResult scanResult = this.inboxScanner.scan(this.pathsPort.inbox());
        final List<DatedMedia> allDated = this.phaseRunner.around(FINDING_DATES,
                dating -> this.dateEveryFile(scanResult, cancellation, dating));
        if (allDated == null) {
            return STOPPED_BEFORE_ANYTHING_MOVED;
        }
        final List<DatedMedia> inScope = this.scopeSelector.select(allDated, scope);
        final Map<MediaFile, DateResult> dateByFile = new HashMap<>();
        inScope.forEach(dated -> dateByFile.put(dated.file(), dated.date()));

        final DedupPlan plan = this.phaseRunner.around(CHECKING_COPIES,
                hashing -> this.dedupPlanFor(inScope, hashing, cancellation));
        // Asked again after the plan lands, because the two loops below are the first deletions of
        // the run. A stop arriving in the gap between hashing and them still finds nothing gone.
        if (plan == null || cancellation.isCancelled()) {
            return STOPPED_BEFORE_ANYTHING_MOVED;
        }

        plan.redundantVsLibrary().forEach(file -> this.mediaStore.delete(file.path()));
        plan.withinBatchDuplicates().forEach(file -> this.mediaStore.delete(file.path()));

        // This pass has no cancelled answer: it catches the abandon and reports what it routed. The
        // phase runner's return is nullable for the stages that do have one.
        final RoutingResult routing = Objects.requireNonNull(this.phaseRunner.around(SORTING,
                moving -> this.routeSurvivors(plan.toSort(), dateByFile, moving, cancellation)));

        // A cancelled routing pass can stop before every survivor is routed. Those unrouted files
        // never actually left the Inbox, even though scope selection picked them. This is the set
        // of files that genuinely left this run, covering every outcome bucket: library-redundant,
        // within-batch duplicate, or actually routed.
        final List<MediaFile> actuallyRemoved = new ArrayList<>(plan.redundantVsLibrary());
        actuallyRemoved.addAll(plan.withinBatchDuplicates());
        actuallyRemoved.addAll(routing.routedFiles);

        // Sidecar consumption doesn't depend on which bucket a file lands in - deleted as a
        // duplicate, or routed. It only depends on the file having actually left the Inbox, so
        // this runs against actuallyRemoved, after routing, rather than the full in-scope list
        // before it. A file routing never reached keeps its sidecar for a future run.
        final Set<Path> consumedSidecars = this.consumeSidecars(actuallyRemoved, dateByFile, scanResult.sidecars());

        // A stop asked for as the last file routed still routes it, and that run left nothing in the
        // Inbox. So this is read off the count, not off the signal, and everything below reads it
        // rather than asking the signal a second time. The two answers differ exactly when the stop
        // lands after the last file, and that run is finished.
        final boolean stoppedShort = routing.routedFiles.size() < plan.toSort().size();

        // The sweep below reaches files this run never touched. A sidecar already orphaned before
        // it started, and a directory left empty. That is housekeeping rather than the run's own
        // work, so a run that stopped short skips it and the next run takes it instead.
        //
        // consumeSidecars above needs no such check. It only ever spends the sidecar of a file
        // that did leave, and a cancelled routing pass leaves the rest where they were.
        if (!stoppedShort) {
            this.sweepOrphanedSidecarsAndEmptyDirectories(scanResult, actuallyRemoved, consumedSidecars);
        }

        return new SortSummary(actuallyRemoved.size(), plan.redundantVsLibrary().size(),
                plan.withinBatchDuplicates().size(), routing.photosSorted, routing.videosSorted, routing.lowRes,
                routing.unsorted, consumedSidecars.size(), routing.lowConfidenceFiles,
                new SortSummary.Guessed(routing.photosSortedGuessed, routing.videosSortedGuessed,
                        routing.lowResGuessed),
                routing.unsortedFiles,
                routing.yearsSorted, pairingWarnings(scanResult), stoppedShort,
                plan.toSort().size() - routing.routedFiles.size());
    }

    /**
     * Deletes the Takeout JSON sidecar of each removed file whose date the sidecar actually won,
     * returning the set of sidecar paths deleted.
     *
     * @param actuallyRemoved a {@link List} of {@link MediaFile} files that genuinely left the Inbox this run
     * @param dateByFile a {@link Map} of {@link MediaFile} to {@link DateResult} resolved date for each in-scope file
     * @param sidecars a {@link Map} of {@link MediaFile} to {@link TakeoutSidecar} sidecar JSON mapped by its owning
     * media file
     * @return a {@link Set} of {@link Path} paths of the sidecars deleted as consumed
     */
    private Set<Path> consumeSidecars(final List<MediaFile> actuallyRemoved,
                                      final Map<MediaFile, DateResult> dateByFile,
                                      final Map<MediaFile, TakeoutSidecar> sidecars) {
        // An "-edited" copy shares its original's sidecar - TakeoutSidecarPairer maps both media
        // files to the same JSON path. So a sidecar can have more than one owner, and it is only
        // spent once every one of them has left. A co-owner the run never reached keeps the JSON
        // alive for the run that finally takes it. That happens when the co-owner is out of scope,
        // or when routing was cancelled before reaching it.
        final Map<Path, Set<MediaFile>> coOwners = new HashMap<>();
        sidecars.forEach((media, sidecar) ->
                coOwners.computeIfAbsent(sidecar.jsonPath(), _ -> new HashSet<>()).add(media));
        final Set<MediaFile> removed = new HashSet<>(actuallyRemoved);

        // When every co-owner did leave, the same path comes up once per co-owner. Set.add returns
        // false on the second occurrence, which is what keeps a shared sidecar from being deleted
        // and counted twice.
        final Set<Path> deletedSidecars = new HashSet<>();
        for (final MediaFile file : actuallyRemoved) {
            final TakeoutSidecar sidecar = sidecars.get(file);
            final DateResult date = Objects.requireNonNull(dateByFile.get(file));
            // Only a sidecar that actually won the date-resolution chain is spent here. One that
            // exists but lost - invalid, or coincidentally name-matched to unrelated JSON - is
            // not this method's to judge. The orphan sweep decides its fate afterward, by name,
            // against whatever media remains in its directory.
            if (sidecar != null && date.source().equals(SIDECAR_SOURCE)
                    && removed.containsAll(coOwners.get(sidecar.jsonPath()))
                    && deletedSidecars.add(sidecar.jsonPath())) {
                this.mediaStore.delete(sidecar.jsonPath());
            }
        }
        return deletedSidecars;
    }

    /**
     * Independent of consumeSidecars above. That method only spends a sidecar whose date actually
     * won for its file. This sweep instead treats a sidecar as spent purely because no media file
     * left in its directory owns it, regardless of why. That catches unmatched sidecars and ones
     * whose media was deleted as a duplicate, so sidecars never pile up across incremental
     * year-by-year runs. A directory left empty of all files afterward is then removed.
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
    private void sweepOrphanedSidecarsAndEmptyDirectories(final ScanResult scanResult,
                                                          final List<MediaFile> actuallyRemoved,
                                                          final Set<Path> consumedSidecars) {
        final Set<Path> removedMediaPaths = actuallyRemoved.stream().map(MediaFile::path).collect(Collectors.toSet());
        final List<Path> remainingMediaPaths = scanResult.media().stream()
                .map(MediaFile::path)
                .filter(path -> !removedMediaPaths.contains(path))
                .toList();
        final List<Path> remainingJsonPaths = scanResult.jsonPaths().stream()
                .filter(path -> !consumedSidecars.contains(path))
                .toList();
        // The scan's own pairing, unwrapped back to raw paths. The sweep needs to know which
        // sidecar each media file actually reads its date from, and this is the only place that
        // relationship was ever computed.
        final Map<Path, Path> sidecarsByMediaPath = new HashMap<>();
        scanResult.sidecars().forEach((media, sidecar) -> sidecarsByMediaPath.put(media.path(), sidecar.jsonPath()));
        for (final Path orphaned : this.sidecarSweep.findOrphaned(remainingMediaPaths, remainingJsonPaths,
                sidecarsByMediaPath)) {
            this.mediaStore.delete(orphaned);
        }
        this.mediaStore.removeEmptyDirectories(this.pathsPort.inbox());
    }

    /**
     * The pairing canary. {@link ScanResult#takeoutMode()} already tells whether Takeout sidecars
     * were found at all. This adds the other half: whether they actually paired to anything.
     *
     * <p>A changed sidecar *suffix* still pairs via the prefix fallback, so a genuine naming-scheme
     * tweak degrades safely on its own. Two changes would not: a scheme whose sidecar name does not
     * start with the media filename at all, or a renamed JSON key
     * {@link photos.sluice.adapter.metadata.TakeoutJsonSource} reads. Either way pairing collapses
     * toward zero, every file falls through the whole date chain to mtime, and a run summary alone
     * would never surface that.
     *
     * @param scanResult {@link ScanResult} the whole-Inbox scan this run read
     * @return a {@link List} of {@link String} zero or one warning, non-empty only on a collapse
     */
    private static List<String> pairingWarnings(final ScanResult scanResult) {
        if (!scanResult.takeoutMode() || scanResult.media().isEmpty()) {
            return List.of();
        }
        final double pairingRate = (double) scanResult.sidecars().size() / scanResult.media().size();
        if (pairingRate >= PAIRING_COLLAPSE_THRESHOLD) {
            return List.of();
        }
        return List.of(("Takeout sidecars are present but only %.1f%% of %d scanned media files paired to one - "
                + "the export's sidecar naming or JSON shape may have changed.")
                .formatted(pairingRate * 100, scanResult.media().size()));
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
        final Set<String> result = new HashSet<>();
        this.hashIndexPort.load().forEach((hash, paths) -> {
            if (paths.stream().anyMatch(this.mediaStore::exists)) {
                result.add(hash);
            }
        });
        return result;
    }

    /**
     * Dates every scanned file, counting as it goes.
     *
     * @param scanResult {@link ScanResult} what the Inbox scan found
     * @param cancellation {@link CancellationSignal} asked between files
     * @param progress {@link ProgressCallback} ticked per file dated
     * @return a {@link List} of {@link DatedMedia} every file with its date, or null where the
     *     run was cancelled before any of it mattered
     */
    private @Nullable List<DatedMedia> dateEveryFile(final ScanResult scanResult,
                                                     final CancellationSignal cancellation,
                                                     final ProgressCallback progress) {
        final List<MediaFile> media = scanResult.media();
        final int total = media.size();
        final List<DatedMedia> dated = new ArrayList<>();
        for (final MediaFile file : media) {
            if (cancellation.isCancelled()) {
                return null;
            }
            dated.add(new DatedMedia(file, this.dateResolver.resolve(file, scanResult.sidecars().get(file))));
            progress.tick(dated.size(), total);
        }
        return dated;
    }

    /**
     * Hashes what is in scope and plans what is already held elsewhere.
     *
     * <p>Reads every byte of every file in scope, which on a year of media is the longest a reader
     * waits with nothing yet moved. So it asks between files.
     *
     * @param inScope a {@link List} of {@link DatedMedia} the files this run covers
     * @param progress {@link ProgressCallback} ticked per file hashed
     * @param cancellation {@link CancellationSignal} asked between files
     * @return {@link DedupPlan} what to delete and what to sort, or null if the run was cancelled
     */
    private @Nullable DedupPlan dedupPlanFor(final List<DatedMedia> inScope, final ProgressCallback progress,
                                             final CancellationSignal cancellation) {
        final Set<String> libraryHashes = this.existingLibraryHashes();
        final int total = inScope.size();
        final List<HashedMedia> hashed = new ArrayList<>();
        for (final DatedMedia dated : inScope) {
            if (cancellation.isCancelled()) {
                return null;
            }
            hashed.add(new HashedMedia(dated.file(), this.sha256Port.hash(dated.file().path())));
            progress.tick(hashed.size(), total);
        }
        return this.dedup.plan(hashed, libraryHashes);
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
    private RoutingResult routeSurvivors(final List<MediaFile> toSort, final Map<MediaFile, DateResult> dateByFile,
                                         final ProgressCallback progress, final CancellationSignal cancellation) {
        final var routing = new RoutingResult();
        final int total = toSort.size();
        int current = 0;
        // Checked after the move so an in-flight file is never interrupted; already-moved files
        // stay moved, matching the no-undo model.
        try {
            while (current < total && !cancellation.isCancelled()) {
                final MediaFile file = toSort.get(current);
                // Every file here came from inScope, and dateByFile was built from that same list.
                // So this lookup always hits. requireNonNull asserts that invariant rather than
                // silently trusting it.
                final DateResult date = Objects.requireNonNull(dateByFile.get(file));
                this.routeOneSurvivor(file, date, routing, cancellation,
                        TransferProgress.within(progress, current, total));
                routing.routedFiles.add(file);
                progress.tick(++current, total);
            }
        } catch (final TransferAbandonedException e) {
            // The abandoned file is still in the Inbox, and the part the store wrote at its
            // destination is gone. Every tally here is stepped after its own move, so the file
            // appears in none of them and what is returned is exactly what did land.
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
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param watching {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void routeOneSurvivor(final MediaFile file, final DateResult date, final RoutingResult routing,
                                  final CancellationSignal cancellation, final TransferProgress watching) {
        final String leaf = file.path().getFileName().toString();

        if (date.confidence() == Confidence.UNSORTABLE) {
            this.routeToReview(file, this.pathsPort.review().resolve(SortFolderNames.UNDATED),
                    null, false, REASON_UNSORTED, cancellation, watching);
            routing.unsorted++;
            routing.unsortedFiles.add(leaf);
            return;
        }

        final boolean guessed = date.confidence() == Confidence.LOW;

        // The scanner already filtered to recognized extensions, so classification always
        // succeeds for a file that reached this point.
        final MediaType type = this.mediaTypeDetector.classify(file.path()).orElseThrow();
        final boolean isVideo = type == MediaType.VIDEO;
        final String extension = MediaTypeDetector.extensionOf(file.path());

        if (!isVideo && this.isLowRes(file, type, extension)) {
            this.routeToReview(file, this.pathsPort.review().resolve(yearMonthDash(date.when())),
                    date.when().toLocalDate(), guessed, REASON_LOW_RES, cancellation, watching);
            routing.lowRes++;
            tallyGuess(routing, guessed, leaf, date, GuessBucket.LOW_RES);
            return;
        }

        final String mediaFolder = isVideo ? "Videos" : "Photos";
        final Path destDir = this.pathsPort.sorted().resolve(mediaFolder)
                .resolve(yearFolder(date.when())).resolve(monthFolder(date.when()));
        this.mediaStore.move(file.path(), destDir, cancellation, watching);
        routing.yearsSorted.add(date.when().getYear());
        if (isVideo) {
            routing.videosSorted++;
        } else {
            routing.photosSorted++;
        }
        tallyGuess(routing, guessed, leaf, date, isVideo ? GuessBucket.VIDEOS : GuessBucket.PHOTOS);
    }

    /**
     * Records a file whose date came off its own timestamp rather than off the photo.
     *
     * <p>Called after the move, like every other tally here, so a file the caller stopped mid-move
     * appears in none of them.
     *
     * @param routing {@link RoutingResult} tallies updated with this file's outcome
     * @param guessed boolean true where the date came off the timestamp
     * @param leaf {@link String} the file's name
     * @param date {@link DateResult} its resolved date
     * @param bucket {@link GuessBucket} where this file went, which the caller knows and this does
     *     not
     */
    private static void tallyGuess(final RoutingResult routing, final boolean guessed, final String leaf,
                                   final DateResult date, final GuessBucket bucket) {
        if (!guessed) {
            return;
        }
        routing.lowConfidenceFiles.add(leaf + " (" + date.source() + " " + date.when().toLocalDate() + ")");
        switch (bucket) {
            case PHOTOS -> routing.photosSortedGuessed++;
            case VIDEOS -> routing.videosSortedGuessed++;
            case LOW_RES -> routing.lowResGuessed++;
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
    private boolean isLowRes(final MediaFile file, final MediaType type, final String extension) {
        final long size = this.mediaStore.size(file.path());
        final Dimensions dimensions = this.imageDimensionsPort.read(file.path()).orElse(null);
        return LowResGate.isLowRes(size, dimensions, type, extension);
    }

    /**
     * Moves a file into a Review destination and appends its note line.
     *
     * <p>The line names the file by where it landed rather than where it came from. A name already
     * taken in the destination lands the file as a " (2)".
     *
     * @param file {@link MediaFile} the file being set aside
     * @param destDir {@link Path} the Review destination folder
     * @param taken {@link LocalDate} when the photo was taken, or null where nothing could date it
     * @param guessed boolean true where that date came off the file's timestamp
     * @param reason {@link String} why it is here, as the note says it to a reader
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param watching {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void routeToReview(final MediaFile file, final Path destDir, final @Nullable LocalDate taken,
                               final boolean guessed, final String reason,
                               final CancellationSignal cancellation, final TransferProgress watching) {
        final Path landed = this.mediaStore.move(file.path(), destDir, cancellation, watching);
        final String name = landed.getFileName().toString();
        this.mediaStore.appendLine(destDir.resolve(ReasonNotes.FILE_NAME), taken == null
                ? ReasonNotes.line(name, reason)
                : ReasonNotes.line(name, taken, guessed, reason));
    }

    /**
     * Formats the year as a four-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the four-digit year folder name
     */
    private static String yearFolder(final LocalDateTime when) {
        return Numerals.padded(when.getYear(), 4);
    }

    /**
     * Formats the month as a two-digit folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the two-digit month folder name
     */
    private static String monthFolder(final LocalDateTime when) {
        return Numerals.padded(when.getMonthValue(), 2);
    }

    /**
     * Formats the date as a dashed year-and-month folder name.
     *
     * @param when {@link LocalDateTime} the date to format
     * @return {@link String} the dashed year-and-month folder name
     */
    private static String yearMonthDash(final LocalDateTime when) {
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
        int photosSortedGuessed;
        int videosSortedGuessed;
        int lowResGuessed;
        final List<String> lowConfidenceFiles = new ArrayList<>();
        final List<String> unsortedFiles = new ArrayList<>();
        final Set<Integer> yearsSorted = new HashSet<>();
        final List<MediaFile> routedFiles = new ArrayList<>();
    }

    /**
     * Which bucket a file with a guessed date went to.
     *
     * <p>Only the three that can hold one. Nothing else here is dated at all.
     */
    private enum GuessBucket {
        PHOTOS, VIDEOS, LOW_RES
    }
}
