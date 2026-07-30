package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.DiscardReport;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.job.ProgressCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every repair a damaged prep directory can be put through, as one entry point per remedy a
 * {@link Finding} offers.
 *
 * <p>Two kinds live here. An AUTO remedy is non-destructive and provably safe, so a troubleshooter
 * runs it unprompted. Renaming an unambiguous stray shard into place is one, rebuilding a lost
 * index.json from surviving sidecars the other. A CHOICE remedy costs the user something - work,
 * money, or an audit trail - so it only ever runs on an explicit decision.
 *
 * <p>Every CHOICE remedy records itself as a disposition-ledger entry. Shards and index.json are
 * never edited. Mutating a culler's own output would destroy the record of what it actually said,
 * which is exactly what these repairs exist to reason about.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/prep-dir-remedies.md}.
 */
@Component
public class PrepDirRemedies {

    private static final String INDEX_FILE = "index.json";

    private final MediaStore mediaStore;
    private final CullPrepPort cullPrepPort;
    private final PathsPort pathsPort;
    private final DisasterDrawer disasterDrawer;
    private final MoveLedger moveLedger;

    /**
     * Creates the remedies wired to their ports.
     *
     * @param mediaStore {@link MediaStore} moves, deletes and lists prep-dir files
     * @param cullPrepPort {@link CullPrepPort} reads and writes prep-dir index, sidecars and shards
     * @param pathsPort {@link PathsPort} resolves the logs root holding the global graveyard
     * @param disasterDrawer {@link DisasterDrawer} files unsalvageable artifacts instead of deleting them
     * @param moveLedger {@link MoveLedger} records every CHOICE resolution
     */
    public PrepDirRemedies(final MediaStore mediaStore, final CullPrepPort cullPrepPort, final PathsPort pathsPort,
                           final DisasterDrawer disasterDrawer, final MoveLedger moveLedger) {
        this.mediaStore = mediaStore;
        this.cullPrepPort = cullPrepPort;
        this.pathsPort = pathsPort;
        this.disasterDrawer = disasterDrawer;
        this.moveLedger = moveLedger;
    }

    /**
     * The MissingSource finding's "skip this file" CHOICE remedy - the alternative to restoring the
     * file, which needs no engine call at all (just a re-diagnose). Records a terminal skip
     * disposition. Classifying then treats source as resolved: no move or write is carried out for
     * it, and it stops surfacing as a MissingSource finding. source itself is never touched. If it
     * ever reappears in Sorted, a future cull of that scope sees it fresh.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param source {@link Path} the missing file's original source path, as named by the MissingSource finding
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    public void skipMissingSource(final Path prepDirPath, final Path source, final String reason) {
        this.moveLedger.recordSkip(prepDirPath, source, reason);
    }

    /**
     * A DecisionUnreviewableOverlap finding's CHOICE remedy: records which of the two conflicting
     * listings wins for file. Neither the shard nor index.json is ever edited. Validation consults
     * this ledger entry instead, suppressing the finding and dropping the losing side from the
     * decisions and unreviewable files a later apply acts on.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param file {@link Path} the file this overlap concerns, as named by the DecisionUnreviewableOverlap finding
     * @param resolution {@link OverlapResolution} which listing should win
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    public void resolveOverlap(final Path prepDirPath, final Path file, final OverlapResolution resolution,
                               final String reason) {
        this.moveLedger.recordOverlap(prepDirPath, file, resolution, reason);
    }

    /**
     * A CorruptSidecar finding's CHOICE remedy records which way montage's batch was resolved. It
     * then files its own (corrupt or already-missing) sidecar file into prepDir's disaster drawer if
     * it is still present. The montage's own scope evidence is spent either way once a choice is
     * made, so there is nothing left worth preserving in place. Validation consults this ledger
     * entry to suppress the finding. It either drops the montage entirely (SET_ASIDE) or trusts its
     * shard's own decisions as their own scope (APPLY_ANYWAY).
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param montage {@link String} the montage id this resolution concerns, as named by the CorruptSidecar finding
     * @param resolution {@link CorruptSidecarResolution} which way the batch was resolved
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    public void resolveCorruptSidecar(final Path prepDirPath, final String montage,
                                      final CorruptSidecarResolution resolution, final String reason) {
        final Path sidecarPath = prepDirPath.resolve(montage + ".json");
        if (this.mediaStore.exists(sidecarPath)) {
            this.disasterDrawer.file(prepDirPath, sidecarPath, "corrupt-sidecar-" + montage);
        }
        this.moveLedger.recordCorruptSidecar(prepDirPath, montage, resolution, reason);
    }

    /**
     * A StrayShard finding's AUTO remedy: renames strayShard's own file into place as the one
     * montage currently missing a shard. That only runs when the repair is provably unambiguous.
     * Exactly one montage in prepDir must currently have no shard. Every file the stray shard's own
     * decisions name must also be a member of that one candidate montage's sidecar. Anything else is
     * left untouched, whether more than one montage is unclaimed or a decision names a file the
     * candidate montage never showed. {@link #setAsideStrayShard} is the CHOICE fallback for that
     * case.
     *
     * @param prepDirPath {@link Path} the prep directory holding the stray shard
     * @param strayShard {@link Finding.StrayShard} the finding naming the stray shard file
     * @return an {@link Optional} {@link String} the montage the shard was renamed to claim, empty if
     * the repair could not run unambiguously
     */
    public Optional<String> autoRepairStrayShard(final Path prepDirPath, final Finding.StrayShard strayShard) {
        final PrepDir prepDir = this.cullPrepPort.readIndex(prepDirPath);
        final List<String> unclaimed = prepDir.entries().stream()
                .filter(montage -> !this.cullPrepPort.hasShard(prepDirPath, montage))
                .toList();
        if (unclaimed.size() != 1) {
            return Optional.empty();
        }
        final String candidate = unclaimed.getFirst();
        final Path strayPath = prepDirPath.resolve(strayShard.shardFile());
        final DecisionShard content = this.cullPrepPort.readShardFile(strayPath);
        final Set<Path> candidateSidecarFiles = this.cullPrepPort.readSidecar(prepDirPath, candidate).stream()
                .map(SidecarPhotoEntry::src)
                .collect(Collectors.toSet());
        final boolean unambiguous = content.decisions().stream()
                .map(Decision::file)
                .allMatch(candidateSidecarFiles::contains);
        if (!unambiguous) {
            return Optional.empty();
        }
        this.mediaStore.moveTo(strayPath, prepDirPath.resolve(MontageNaming.shardFileFor(candidate)));
        return Optional.of(candidate);
    }

    /**
     * A StrayShard finding's CHOICE fallback when {@link #autoRepairStrayShard} cannot resolve it
     * unambiguously. Files the stray shard's own file into prepDir's disaster drawer, never a true
     * delete, so the culler can redo that montage from a clean slate.
     *
     * @param prepDirPath {@link Path} the prep directory holding the stray shard
     * @param strayShard {@link Finding.StrayShard} the finding naming the stray shard file
     * @return {@link Path} the path the stray shard was filed to
     */
    public Path setAsideStrayShard(final Path prepDirPath, final Finding.StrayShard strayShard) {
        return this.disasterDrawer.file(prepDirPath, prepDirPath.resolve(strayShard.shardFile()), "stray-shard");
    }

    /**
     * A CorruptIndex finding's AUTO remedy: rebuilds index.json from whatever sidecars survive on
     * disk. Only runs when every montage sidecar can be accounted for - a contiguous
     * montage-001..NNN run, every one of them actually parseable. A gap or an unparseable sidecar
     * means the sidecars themselves are also damaged. A silently-smaller rebuilt index would make
     * healthy shards look stray, so that combined case degrades to needing the last-resort
     * {@link #discard} instead. Sidecar health is judged before this rebuild, per the locked
     * dependency order.
     *
     * <p>The unreviewable list is genuinely unrecoverable - no sidecar or shard mentions it, since
     * it was never montaged at all. So a rebuilt index always reports it empty. Losing it costs a
     * report line, never safety: an unreviewable photo is never moved, so it stays in Sorted for a
     * future cull to see fresh. The scope is read straight off the prep dir's own folder name,
     * which is the on-disk convention every real index.json already mirrors. The basePath is
     * reconstructed as the deepest common parent of every surviving sidecar's own src files. That's
     * exact for a Year scope, an approximation for OldestN spanning a single year. The field is
     * purely a display value no engine logic ever consults, so the approximation costs nothing
     * beyond a slightly less precise report line.
     *
     * <p>Any existing index.json is filed into prepDir's disaster drawer first, wholesale, mirroring
     * the same "never salvage a corrupt artifact line-by-line" treatment the move ledger gets. That
     * only happens once every guard above has already passed, so a refused rebuild never disturbs
     * the original.
     *
     * @param prepDirPath {@link Path} the prep directory whose index to rebuild
     * @return an {@link Optional} {@link PrepDir} the rebuilt index, empty if the guard refused
     */
    public Optional<PrepDir> rebuildIndex(final Path prepDirPath) {
        final var montageNumbers = new ArrayList<Integer>();
        for (final Path file : this.mediaStore.listFiles(prepDirPath)) {
            MontageNaming.sidecarMontageNumber(file.getFileName().toString()).ifPresent(montageNumbers::add);
        }
        montageNumbers.sort(null);
        if (montageNumbers.isEmpty() || !isContiguousFromOne(montageNumbers)) {
            return Optional.empty();
        }
        final List<String> entries = montageNumbers.stream().map(MontageNaming::montageIdFor).toList();

        final var allSrcs = new ArrayList<Path>();
        int photos = 0;
        for (final String montage : entries) {
            final Optional<List<Path>> srcs = Sidecars.srcsOf(this.cullPrepPort, prepDirPath, montage);
            if (srcs.isEmpty()) {
                return Optional.empty();
            }
            allSrcs.addAll(srcs.get());
            photos += srcs.get().size();
        }

        final Path indexPath = prepDirPath.resolve(INDEX_FILE);
        if (this.mediaStore.exists(indexPath)) {
            this.disasterDrawer.file(prepDirPath, indexPath, "index-json");
        }
        final var rebuilt = new PrepDir(prepDirPath.getFileName().toString(), commonParent(allSrcs), photos,
                List.of(), entries.size(), prepDirPath, entries);
        this.cullPrepPort.writeIndex(prepDirPath, rebuilt);
        return Optional.of(rebuilt);
    }

    /**
     * Whether sortedNumbers runs 1, 2, 3, ... with no gaps.
     *
     * @param sortedNumbers a {@link List} of {@link Integer}, ascending
     * @return boolean true if the sequence is contiguous starting from 1
     */
    private static boolean isContiguousFromOne(final List<Integer> sortedNumbers) {
        for (int i = 0; i < sortedNumbers.size(); i++) {
            if (sortedNumbers.get(i) != i + 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * The deepest directory every file's own parent shares in common. Assumes every file shares a
     * root - they all come from this one prep dir's own scope, always a single Sorted tree. Two
     * files on unrelated roots would shrink common past its own root into a null parent.
     *
     * @param files a {@link List} of {@link Path}, non-empty
     * @return {@link Path} the deepest common parent directory
     */
    private static Path commonParent(final List<Path> files) {
        Path common = files.getFirst().getParent();
        for (final Path file : files) {
            final Path parent = file.getParent();
            while (!parent.startsWith(common)) {
                common = common.getParent();
            }
        }
        return common;
    }

    /**
     * The last-resort CHOICE remedy for a prep dir mangled beyond every other repair. It gives up on
     * the run entirely, filing everything worth keeping into a global graveyard and deleting the
     * rest. Every non-image file is moved wholesale into
     * {@code logs/disasters/<scope>-<timestamp>/}, keeping its own relative layout. That covers
     * shards, sidecars, index.json, the move ledger, and any disaster drawer. The graveyard gets the
     * same 30-day retention window every other disaster-drawer artifact does. Only the montage and
     * tile contact-sheet images are truly deleted, since they cost cents to re-render on a fresh
     * cull of the same scope. Library media is never touched - this only ever reaches into the prep
     * dir itself.
     *
     * <p>scope is read straight off the prep dir's own folder name, never index.json. The whole
     * point of this remedy is that index.json, or anything else, might be too damaged to read at
     * all.
     *
     * <p>Callers should gate this on {@link PrepDirDoctor} reporting anything but COMPLETE - this is
     * the raw mechanism, ungated. {@code Pipeline.discard()} adds that gate, plus watcher-disarming
     * and JobRunner wiring, for its own two entry points.
     *
     * @param prepDirPath {@link Path} the prep directory to discard
     * @return {@link DiscardReport} the graveyard directory and how many shards were set aside
     */
    public DiscardReport discard(final Path prepDirPath) {
        return this.discard(prepDirPath, ProgressCallback.NO_OP);
    }

    /**
     * Discards with progress reporting, ticked once per file moved or deleted.
     *
     * @param prepDirPath {@link Path} the prep directory to discard
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @return {@link DiscardReport} the graveyard directory and how many shards were set aside
     */
    public DiscardReport discard(final Path prepDirPath, final ProgressCallback progress) {
        final String scope = prepDirPath.getFileName().toString();
        final Path graveyard =
                this.pathsPort.logs().resolve("disasters").resolve(scope + "-" + DisasterTimestamp.now());
        this.mediaStore.ensureDirectory(graveyard);
        final List<Path> files = this.mediaStore.listFiles(prepDirPath);
        final int total = files.size();
        int current = 0;
        int shardsSetAside = 0;
        for (final Path file : files) {
            final String name = file.getFileName().toString();
            if (MontageNaming.isMontageImage(name)) {
                this.mediaStore.delete(file);
            } else {
                this.mediaStore.moveTo(file, graveyard.resolve(prepDirPath.relativize(file)));
                if (MontageNaming.isShardFile(name)) {
                    shardsSetAside++;
                }
            }
            progress.tick(++current, total);
        }
        this.mediaStore.removeIfEmptyOfFiles(prepDirPath);
        return new DiscardReport(graveyard, shardsSetAside);
    }
}
