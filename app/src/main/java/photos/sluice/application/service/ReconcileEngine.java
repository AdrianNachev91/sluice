package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ReconcileReport;
import photos.sluice.domain.cull.ValidationReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a prep directory's move ledger from disk state alone. It exists for when the ledger
 * itself cannot be trusted, whether lost, unreadable, or found with this run's shards intact.
 *
 * <p>Any existing ledger is filed into the prep dir's disaster drawer wholesale, never salvaged
 * line by line. It is then rebuilt from scratch, purely from what disk state can prove. Every
 * already-validated decision and unreviewable file is checked against the exact destination
 * applying it would have produced, recording the hash of whatever is found there.
 *
 * <p>This is a name-and-location match, not a proof of identity. The original file's own hash lived
 * only in the ledger being replaced, so there is nothing left to verify a located file against.
 * That is why a destination is only ever reconstructed when it can be identified unambiguously -
 * see {@link #resolvePendingMoves} for the exact rule and its residual risk.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/reconcile-engine.md}.
 */
@Component
public class ReconcileEngine {

    private final MediaStore mediaStore;
    private final CullPrepPort cullPrepPort;
    private final Sha256Port sha256Port;
    private final DisasterDrawer disasterDrawer;
    private final CullDestinations cullDestinations;
    private final MoveLedger moveLedger;
    private final ApplyPlanner applyPlanner;

    /**
     * Creates a reconcile engine wired to its collaborators.
     *
     * @param mediaStore {@link MediaStore} checks which files exist on disk
     * @param cullPrepPort {@link CullPrepPort} reads the prep dir's index
     * @param sha256Port {@link Sha256Port} hashes a located destination for the rebuilt record
     * @param disasterDrawer {@link DisasterDrawer} files the untrusted ledger away before rebuilding
     * @param cullDestinations {@link CullDestinations} resolves where each decision would have gone
     * @param moveLedger {@link MoveLedger} appends the reconstructed records
     * @param applyPlanner {@link ApplyPlanner} validates the shard contract before any rebuild
     */
    public ReconcileEngine(MediaStore mediaStore, CullPrepPort cullPrepPort, Sha256Port sha256Port,
            DisasterDrawer disasterDrawer, CullDestinations cullDestinations, MoveLedger moveLedger,
            ApplyPlanner applyPlanner) {
        this.mediaStore = mediaStore;
        this.cullPrepPort = cullPrepPort;
        this.sha256Port = sha256Port;
        this.disasterDrawer = disasterDrawer;
        this.cullDestinations = cullDestinations;
        this.moveLedger = moveLedger;
        this.applyPlanner = applyPlanner;
    }

    /**
     * Sweeps prepDir and rebuilds its move ledger, reporting what each decision turned out to be:
     * reconstructed, still pending, or genuinely missing.
     *
     * <p>A file already sitting untouched needs no record at all - a still-present source is pending
     * regardless of the ledger. NearDupChosen never gets a rebuilt record: it is a copy, so a
     * missing source can only mean the photo itself is gone, never an unconfirmed move.
     *
     * @param prepDirPath {@link Path} the prep directory to reconcile
     * @return {@link ReconcileReport} what the sweep found
     * @throws ApplyException if the shard contract itself does not validate cleanly
     */
    public ReconcileReport reconcile(Path prepDirPath) throws ApplyException {
        final PrepDir prepDir = cullPrepPort.readIndex(prepDirPath);
        final ValidationReport validation = applyPlanner.validate(prepDirPath, prepDir, new ApplyOptions(true));
        if (!validation.valid()) {
            throw ApplyPlanner.failure(validation.findings());
        }
        // Read before the ledger itself gets filed away below. Once filed, it holds no entries to
        // resolve against, and an already-resolved overlap must not revert to unresolved here.
        final List<Path> unreviewableFiles = applyPlanner.resolvedUnreviewable(prepDirPath, prepDir);

        final Path moveRecordLog = moveLedger.logFor(prepDirPath);
        if (mediaStore.exists(moveRecordLog)) {
            disasterDrawer.file(prepDirPath, moveRecordLog, "move-records-log");
        }

        final var sweep = new ReconcileSweep(prepDirPath, moveRecordLog);
        validation.decisions().forEach(decision -> reconcileDecision(decision, sweep));
        unreviewableFiles.forEach(file -> reconcileFile(file, cullDestinations.unreviewableDir(file), sweep));
        resolvePendingMoves(sweep);

        return new ReconcileReport(sweep.reconstructed, sweep.stillPending, sweep.missingSource);
    }

    /**
     * One decision's step in the sweep. A NearDupChosen decision is checked for existence right
     * here, since it is a copy. A missing source is always missing-source for it, never a pending
     * move. Classifying never has a move-record path for it either, and this must not pretend
     * otherwise. Every other decision defers its own existence check to reconcileFile().
     *
     * @param decision {@link Decision} the decision to reconcile
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void reconcileDecision(Decision decision, ReconcileSweep sweep) {
        if (decision instanceof NearDupChosen) {
            if (mediaStore.exists(decision.file())) {
                sweep.stillPending++;
            } else {
                sweep.missingSource.add(new Finding.MissingSource(decision.file(), sweep.moveRecordLog));
            }
            return;
        }
        reconcileFile(decision.file(), cullDestinations.destinationDirFor(decision), sweep);
    }

    /**
     * The core per-file step, shared by a move-based decision and an unreviewable file alike. A file
     * still at its original location needs no record. Otherwise it is queued as a pending move, and
     * resolvePendingMoves() decides later whether its destination can be identified unambiguously.
     *
     * @param file {@link Path} the source file to reconcile
     * @param destDir {@link Path} the directory a real apply would have moved file into
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void reconcileFile(Path file, Path destDir, ReconcileSweep sweep) {
        if (mediaStore.exists(file)) {
            sweep.stillPending++;
            return;
        }
        sweep.pendingMoves.add(new PendingMove(file, destDir));
    }

    /**
     * Resolves every pending move queued by reconcileFile(), grouped by (destDir, original file
     * name). That's the same key two decisions that started with an identical leaf name would share.
     *
     * <p>A permanent flat destination like {@code Funny/} accumulates files across every run the
     * app has ever applied, not just this sweep. So a group's on-disk collision candidates can
     * outnumber or fall short of this sweep's own claimants. Reconstruction only happens when the
     * two counts match exactly. Candidates are then zipped to claimants in decision order, the same
     * order a real move would have produced them in. Any mismatch means the group is ambiguous. A
     * mystery file present is a surplus; the genuinely-moved file gone without trace is a deficit.
     * Either way, every claimant in the group is reported missing rather than guessed at.
     *
     * <p>One coincidence this cannot catch: a stranger's file arriving at the exact moment ours
     * vanishes without a trace still restores count parity. Only the original file's own hash could
     * tell that case apart from a genuine match, and that hash lived only in the ledger this repair
     * is replacing. This residual risk is accepted rather than chased.
     *
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void resolvePendingMoves(ReconcileSweep sweep) {
        final Map<String, List<PendingMove>> groups = new LinkedHashMap<>();
        for (PendingMove move : sweep.pendingMoves) {
            final String key = move.destDir() + MoveLedger.RECORD_DELIMITER + move.file().getFileName();
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(move);
        }
        groups.values().forEach(claimants -> resolveGroup(claimants, sweep));
    }

    /**
     * One (destDir, file name) group's worth of resolvePendingMoves() - see that method's Javadoc
     * for the unambiguity rule this enforces.
     *
     * @param claimants a {@link List} of {@link PendingMove}, this group's claimants in decision order
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void resolveGroup(List<PendingMove> claimants, ReconcileSweep sweep) {
        final PendingMove first = claimants.getFirst();
        final List<Path> candidates = contiguousCandidates(first.destDir(), first.file().getFileName().toString());
        if (candidates.size() != claimants.size()) {
            claimants.forEach(claimant ->
                    sweep.missingSource.add(new Finding.MissingSource(claimant.file(), sweep.moveRecordLog)));
            return;
        }
        for (int i = 0; i < claimants.size(); i++) {
            final Path file = claimants.get(i).file();
            final Path located = candidates.get(i);
            moveLedger.recordReconstructed(sweep.prepDirPath, file, located, sha256Port.hash(located));
            sweep.reconstructed++;
        }
    }

    /**
     * Every destDir candidate for baseName that exists on disk without a gap - the plain name first,
     * then " (2)", " (3)", ... stopping at the first missing slot. This is exactly the collision
     * order a real move would have produced.
     *
     * @param destDir {@link Path} the directory to search
     * @param baseName {@link String} the file's own original name
     * @return a {@link List} of {@link Path}, every contiguous candidate found, in slot order
     */
    private List<Path> contiguousCandidates(Path destDir, String baseName) {
        final var candidates = new ArrayList<Path>();
        int slot = 1;
        Path candidate = destDir.resolve(CullDestinations.candidateName(baseName, slot));
        while (mediaStore.exists(candidate)) {
            candidates.add(candidate);
            slot++;
            candidate = destDir.resolve(CullDestinations.candidateName(baseName, slot));
        }
        return candidates;
    }

    /**
     * A file the sweep could not find still sitting at its original location. destDir is the exact
     * directory a real apply would have moved it into. It is carried alongside so
     * resolvePendingMoves() can group and search without looking the decision back up.
     *
     * @param file {@link Path} the source file that could not be found at its original location
     * @param destDir {@link Path} the directory a real apply would have moved file into
     */
    private record PendingMove(Path file, Path destDir) {
    }

    /**
     * The sweep's accumulating outcome as it walks every decision and unreviewable file in turn.
     * prepDirPath and moveRecordLog are carried here rather than threaded through every call. One
     * lets a freshly reconstructed record be appended. The other lets a MissingSource finding name
     * the ledger it failed to find its proof in.
     */
    private static final class ReconcileSweep {
        final List<PendingMove> pendingMoves = new ArrayList<>();
        final List<Finding.MissingSource> missingSource = new ArrayList<>();
        final Path prepDirPath;
        final Path moveRecordLog;
        int reconstructed;
        int stillPending;

        ReconcileSweep(Path prepDirPath, Path moveRecordLog) {
            this.prepDirPath = prepDirPath;
            this.moveRecordLog = moveRecordLog;
        }
    }
}
