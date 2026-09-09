package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ReconcileReport;
import photos.sluice.domain.cull.ValidationReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds a prep directory's move ledger from disk state alone. It exists for when the ledger
 * itself cannot be trusted, whether lost, undecodable, or found with this run's shards intact.
 *
 * <p>The move-record file is filed into the prep dir's disaster drawer wholesale, never salvaged
 * line by line. The records are then rebuilt from scratch, purely from what disk state can prove.
 * Every already-validated decision and unreviewable file is checked against the exact destination
 * applying it would have produced, recording the hash of whatever is found there.
 *
 * <p>The choices file is left exactly where it is. A rebuild can only recover evidence disk itself
 * carries, and an answer somebody gave is not that. So a user's answers survive a
 * reconcile untouched, and a file they had already given up on is reported skipped rather than
 * swept again. The one exception is a choices file whose bytes do not decode. Its answers are
 * already gone, so it is filed away too and the report says so.
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
    public ReconcileEngine(final MediaStore mediaStore, final CullPrepPort cullPrepPort, final Sha256Port sha256Port,
                           final DisasterDrawer disasterDrawer, final CullDestinations cullDestinations,
                           final MoveLedger moveLedger,
                           final ApplyPlanner applyPlanner) {
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
     * regardless of the ledger. A file the user has already given up on is reported skipped, never
     * missing: an answer is terminal, and a rebuild is not allowed to un-ask it. NearDupChosen never
     * gets a rebuilt record: it is a copy, so a missing source can only mean the photo itself is
     * gone, never an unconfirmed move.
     *
     * @param prepDirPath {@link Path} the prep directory to reconcile
     * @return {@link ReconcileReport} what the sweep found
     * @throws ApplyException if the shard contract itself does not validate cleanly
     */
    public ReconcileReport reconcile(final Path prepDirPath) throws ApplyException {
        final PrepDir prepDir = this.cullPrepPort.readIndex(prepDirPath);
        // One snapshot for this whole reconcile, taken before either filing below - see Ledger's
        // own Javadoc. validate() and resolvedUnreviewable() both consume it, and the sweep reads
        // its skips. Taking it later would also read a filed-away choices file as simply absent,
        // so the loss would never be disclosed.
        final Ledger ledger = this.moveLedger.read(prepDirPath);
        final ValidationReport validation = this.applyPlanner.validate(prepDirPath, prepDir, new ApplyOptions(true),
                ledger);
        if (!validation.valid()) {
            throw ApplyPlanner.failure(validation.findings());
        }
        final List<Path> unreviewableFiles = this.applyPlanner.resolvedUnreviewable(prepDir, ledger);

        final Path moveRecordLog = ledger.moveRecordLog();
        this.fileAway(prepDirPath, moveRecordLog, MoveLedger.MOVE_RECORD_DRAWER_LABEL);
        if (ledger.choicesUndecodable()) {
            this.fileAway(prepDirPath, this.moveLedger.choicesLogFor(prepDirPath), MoveLedger.CHOICES_DRAWER_LABEL);
        }

        final Map<String, Path> nearDupAnchors = CullDestinations.nearDupAnchors(validation.decisions());
        final var sweep = new ReconcileSweep(prepDirPath, moveRecordLog, ledger.skipped());
        validation.decisions().forEach(decision -> this.reconcileDecision(decision, nearDupAnchors, sweep));
        unreviewableFiles.forEach(file -> this.reconcileFile(file, this.cullDestinations.unreviewableDir(file), sweep));
        this.resolvePendingMoves(sweep);

        return new ReconcileReport(sweep.reconstructed, sweep.stillPending, sweep.skipped, sweep.missingSource,
                ledger.choicesUndecodable());
    }

    /**
     * Files one of the ledger's files into the prep dir's disaster drawer, if it is there at all.
     * Either file can legitimately be absent. A run with no CHOICE remedy never writes a choices
     * file, and a lost move-record file is the very thing this rebuild exists for.
     *
     * @param prepDirPath {@link Path} the prep directory whose drawer receives the file
     * @param file {@link Path} the ledger file to file away
     * @param label {@link String} the drawer's own name for this kind of artifact
     */
    private void fileAway(final Path prepDirPath, final Path file, final String label) {
        if (this.mediaStore.exists(file)) {
            this.disasterDrawer.file(prepDirPath, file, label);
        }
    }

    /**
     * One decision's step in the sweep, dispatched by decision type. A NearDupReject resolves its
     * destination from the group's chosen keeper, not its own file - see
     * {@link CullDestinations#duplicatesDir}.
     *
     * @param decision {@link Decision} the decision to reconcile
     * @param nearDupAnchors a {@link Map} of {@link String} to {@link Path} each near-dup group's keeper file, by
     * group id
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void reconcileDecision(final Decision decision, final Map<String, Path> nearDupAnchors,
                                   final ReconcileSweep sweep) {
        switch (decision) {
            case final NearDupChosen c -> this.reconcileNearDupChosen(c, sweep);
            case final NearDupReject reject -> this.reconcileFile(reject.file(),
                    this.cullDestinations.duplicatesDir(nearDupAnchors.get(reject.group()), reject.group()), sweep);
            case final Classification c -> this.reconcileFile(c.file(), this.cullDestinations.destinationDirFor(c), sweep);
        }
    }

    /**
     * A NearDupChosen decision's own step, checked for existence right here since it is a copy. A
     * missing source is always missing-source for it, never a pending move. An answered skip still
     * settles it first, exactly as it does for every other kind.
     *
     * @param decision {@link NearDupChosen} the chosen near-dup decision to reconcile
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void reconcileNearDupChosen(final NearDupChosen decision, final ReconcileSweep sweep) {
        if (this.mediaStore.exists(decision.file())) {
            sweep.stillPending++;
        } else if (sweep.skippedByUser.contains(decision.file())) {
            sweep.skipped++;
        } else {
            sweep.missingSource.add(new Finding.MissingSource(decision.file(), sweep.moveRecordLog));
        }
    }

    /**
     * The core per-file step, shared by a move-based decision and an unreviewable file alike. A file
     * still at its original location needs no record. A missing file the user already answered for
     * is settled and stays settled. A rebuild reports that skip rather than re-deriving a
     * destination nothing was ever headed to. Otherwise it is queued as a pending move, and
     * resolvePendingMoves() decides later whether its destination can be identified unambiguously.
     *
     * <p>Existence is checked before the answer, matching how applying itself classifies. A file
     * the user gave up on and then put back simply becomes pending again.
     *
     * @param file {@link Path} the source file to reconcile
     * @param destDir {@link Path} the directory a real apply would have moved file into
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void reconcileFile(final Path file, final Path destDir, final ReconcileSweep sweep) {
        if (this.mediaStore.exists(file)) {
            sweep.stillPending++;
            return;
        }
        if (sweep.skippedByUser.contains(file)) {
            sweep.skipped++;
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
    private void resolvePendingMoves(final ReconcileSweep sweep) {
        final Map<String, List<PendingMove>> groups = new LinkedHashMap<>();
        for (final PendingMove move : sweep.pendingMoves) {
            final String key = move.destDir() + MoveLedger.RECORD_DELIMITER + move.file().getFileName();
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(move);
        }
        groups.values().forEach(claimants -> this.resolveGroup(claimants, sweep));
    }

    /**
     * One (destDir, file name) group's worth of resolvePendingMoves() - see that method's Javadoc
     * for the unambiguity rule this enforces.
     *
     * @param claimants a {@link List} of {@link PendingMove}, this group's claimants in decision order
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void resolveGroup(final List<PendingMove> claimants, final ReconcileSweep sweep) {
        final PendingMove first = claimants.getFirst();
        final List<Path> candidates = this.contiguousCandidates(first.destDir(), first.file().getFileName().toString());
        if (candidates.size() != claimants.size()) {
            claimants.forEach(claimant ->
                    sweep.missingSource.add(new Finding.MissingSource(claimant.file(), sweep.moveRecordLog)));
            return;
        }
        for (int i = 0; i < claimants.size(); i++) {
            final Path file = claimants.get(i).file();
            final Path located = candidates.get(i);
            this.moveLedger.recordReconstructed(sweep.prepDirPath, file, located, this.sha256Port.hash(located));
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
    private List<Path> contiguousCandidates(final Path destDir, final String baseName) {
        final var candidates = new ArrayList<Path>();
        int slot = 1;
        Path candidate = destDir.resolve(CullDestinations.candidateName(baseName, slot));
        while (this.mediaStore.exists(candidate)) {
            candidates.add(candidate);
            slot++;
            candidate = destDir.resolve(CullDestinations.candidateName(baseName, slot));
        }
        return candidates;
    }

    /**
     * A file the sweep could not find still sitting at its original location, carrying the exact
     * directory a real apply would have moved it into.
     *
     * @param file {@link Path} the source file that could not be found at its original location
     * @param destDir {@link Path} the directory a real apply would have moved file into
     */
    private record PendingMove(Path file, Path destDir) {
    }

    /**
     * The sweep's accumulating outcome as it walks every decision and unreviewable file in turn. It
     * carries prepDirPath, moveRecordLog and the snapshot's own skip set rather than threading them
     * through every call.
     */
    private static final class ReconcileSweep {
        final List<PendingMove> pendingMoves = new ArrayList<>();
        final List<Finding.MissingSource> missingSource = new ArrayList<>();
        final Path prepDirPath;
        final Path moveRecordLog;
        final Set<Path> skippedByUser;
        int reconstructed;
        int stillPending;
        int skipped;

        ReconcileSweep(final Path prepDirPath, final Path moveRecordLog, final Set<Path> skippedByUser) {
            this.prepDirPath = prepDirPath;
            this.moveRecordLog = moveRecordLog;
            this.skippedByUser = skippedByUser;
        }
    }
}
