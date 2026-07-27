package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ReconcileReport;
import photos.sluice.domain.cull.SidecarPhotoEntry;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Flowchart + scenario table: app/docs/design/application/service/apply-engine.md.
@Component
public class ApplyEngine {

    private static final String FUNNY_CATEGORY = "funny";
    private static final String REASONS_FILE = "_reasons.txt";
    private static final String MOVE_RECORD_LOG = "move-records.log";
    // The move-record line format's one additive field. A legacy three-field line (no fourth field)
    // means WITNESSED - recorded from the source's own hash right before a real move. This exact
    // marker as the fourth field means RECONSTRUCTED - reconcile() inferred the record from disk
    // state alone, having lost the original log. Both are trusted identically by classify(); the
    // marker is provenance for a human reading the log, not a behavioral distinction.
    private static final String RECONSTRUCTED_MARKER = "RECONSTRUCTED";
    // A control character, not a printable one - guaranteed absent from any path on every
    // mainstream filesystem. A record's fields can be split back apart with zero escaping and no
    // ambiguity even when a path itself contains spaces, commas, or tabs.
    private static final String RECORD_DELIMITER = "\u001F";
    private static final String UNDATED = "0000-00";

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final DisasterDrawer disasterDrawer;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates an engine wired to its ports.
     *
     * @param pathsPort {@link PathsPort} resolves library/review/duplicates paths
     * @param mediaStore {@link MediaStore} filesystem effects (move, copy, read, write)
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir index and shards
     * @param cullSettings {@link CullSettings} configured cull categories
     * @param sha256Port {@link Sha256Port} hashes files for move verification
     * @param hashIndexPort {@link HashIndexPort} reads/appends the library hash index
     * @param disasterDrawer {@link DisasterDrawer} files a prep dir's own unsalvageable artifacts
     */
    public ApplyEngine(PathsPort pathsPort, MediaStore mediaStore, CullPrepPort cullPrepPort,
            CullSettings cullSettings, Sha256Port sha256Port, HashIndexPort hashIndexPort, DisasterDrawer disasterDrawer) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.disasterDrawer = disasterDrawer;
    }

    /**
     * Reads the prep directory's index.json and every montage's decision shard, then validates the
     * whole batch in one pass (see validate()). Every decision is then classified against the
     * move-record log (see classify()) before anything runs. A decision whose file is still on disk
     * is pending. One that's gone but hash-verifies at its recorded destination is already done, its
     * secondary write (if any) reconciled rather than redone. index.json's own unreviewable list goes
     * through the same classification, via classifyFile() - it has no shard-driven category, just a
     * plain move once carried out. Anything unresolved, decision or unreviewable file alike, aborts
     * the whole run before a single file moves. Once every decision and unreviewable file is
     * handled, the merged decisions.json is written and the montage/tile intermediates are deleted.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @return {@link ApplyReport} the applied run's summary report
     * @throws ApplyException if validation finds unresolved problems
     */
    public ApplyReport apply(Path prepDirPath, ApplyOptions options) throws ApplyException {
        return apply(prepDirPath, options, ProgressCallback.NO_OP);
    }

    /**
     * Applies with progress reporting, using a cancellation signal that never cancels.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @return {@link ApplyReport} the applied run's summary report
     * @throws ApplyException if validation finds unresolved problems
     */
    public ApplyReport apply(Path prepDirPath, ApplyOptions options, ProgressCallback progress) throws ApplyException {
        // NEVER never trips, so the cancellation-aware overload below always runs to completion
        // and returns non-null here - this just asserts that rather than silently trusting it.
        return Objects.requireNonNull(apply(prepDirPath, options, progress, CancellationSignal.NEVER));
    }

    /**
     * Applies a prep directory's decisions, cancellable mid-run.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @param cancellation {@link CancellationSignal} signal checked between file operations
     * @return {@link ApplyReport} the applied run's summary report, or null if cancelled
     * @throws ApplyException if validation finds unresolved problems
     */
    public @Nullable ApplyReport apply(Path prepDirPath, ApplyOptions options, ProgressCallback progress,
            CancellationSignal cancellation) throws ApplyException {
        PrepDir prepDir = cullPrepPort.readIndex(prepDirPath);
        ValidationReport validation = validate(prepDirPath, prepDir, options);
        if (!validation.valid()) {
            throw failure(validation.findings());
        }

        Path moveRecordLog = prepDirPath.resolve(MOVE_RECORD_LOG);
        Map<Path, MoveRecord> moveRecords = readMoveRecords(moveRecordLog);
        List<Status> statuses = validation.decisions().stream()
                .map(decision -> classify(decision, moveRecords))
                .toList();
        List<FileStatus> unreviewableStatuses = prepDir.unreviewable().stream()
                .map(file -> classifyFile(file, moveRecords))
                .toList();
        List<Finding> missingSource = new ArrayList<>();
        statuses.stream()
                .filter(Status.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.decision().file(), moveRecordLog))
                .forEach(missingSource::add);
        unreviewableStatuses.stream()
                .filter(FileStatus.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.file(), moveRecordLog))
                .forEach(missingSource::add);
        if (!missingSource.isEmpty()) {
            throw failure(missingSource);
        }

        Map<String, List<Decision>> nearDupGroups = groupNearDups(validation.decisions());
        var outcome = new ApplyOutcome();
        // Both loops below can move a file, so both count toward the total a caller is told about.
        // Otherwise progress would reach 100% while unreviewable files are still being moved.
        int total = statuses.size() + unreviewableStatuses.size();
        int current = 0;
        // Checked per decision, in both loops below. On cancel, the finalizers past this point -
        // writeMergedDecisions() and cleanupIntermediates() - must not run, so this returns null
        // outright rather than falling through to them. No decisions.json means the prep dir still
        // reads as a waiting job (see dispatchAndApply()'s own null handling).
        for (Status status : statuses) {
            if (cancellation.isCancelled()) {
                return null;
            }
            switch (status) {
                case Status.Pending p -> apply(p.decision(), moveRecordLog, nearDupGroups, outcome);
                case Status.Done d -> backfillSecondaryWrite(d.decision(), d.record());
                case Status.Unresolved _ -> {} // already aborted the whole run above
            }
            progress.tick(++current, total);
        }
        for (FileStatus status : unreviewableStatuses) {
            if (cancellation.isCancelled()) {
                return null;
            }
            if (status instanceof FileStatus.Pending(Path file)) {
                recordThenMove(file, unreviewableDir(file), moveRecordLog);
            }
            // Done: the move alone is the whole action - there's no secondary write to reconcile.
            progress.tick(++current, total);
        }

        var report = new ApplyReport(prepDir.photos(), outcome.byCategory, prepDir.unreviewable().size(),
                outcome.nearDupGroupsChosen.size(), outcome.nearDupRejects, validation.heals());
        ApplyReport persistedSummary = summarize(validation.decisions(), prepDir, validation.heals());
        cullPrepPort.writeMergedDecisions(prepDirPath, prepDir.scope(), validation.decisions(), persistedSummary);
        cleanupIntermediates(prepDirPath);
        return report;
    }

    /**
     * The persisted decisions.json embeds a fresh recount over the whole decisions array it sits
     * next to. That covers this run's decisions and every prior run's alike, not just the
     * this-run-only report returned to the caller. decisions.json is overwritten wholesale on every
     * write, never appended to, so recomputing from the full list each time carries no
     * double-counting risk.
     *
     * @param decisions a {@link List} of {@link Decision} the full decisions array, all runs
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param heals a {@link List} of {@link String} healed-shard messages to include
     * @return {@link ApplyReport} a freshly recomputed summary report
     */
    private static ApplyReport summarize(List<Decision> decisions, PrepDir prepDir, List<String> heals) {
        Map<String, Integer> byCategory = new TreeMap<>();
        Set<String> groups = new HashSet<>();
        int rejects = 0;
        for (Decision decision : decisions) {
            switch (decision) {
                case Classification c -> byCategory.merge(c.category(), 1, Integer::sum);
                case NearDupChosen c -> groups.add(c.group());
                case NearDupReject _ -> rejects++;
            }
        }
        return new ApplyReport(prepDir.photos(), byCategory, prepDir.unreviewable().size(), groups.size(), rejects, heals);
    }

    /**
     * Merges three problem sources into one report, never throwing - apply()'s own gate throws on
     * an invalid result, and PrepDirDoctor reads the identical report read-only. A missing montage
     * shard is a finding only when allowPartial waives it. PrepDirDoctor always calls with
     * allowPartial true, so a still-culling prep dir reports on the shards it already has rather
     * than drowning in "not culled yet" noise. A decisions file with no matching montage is always
     * a finding: almost always a culler numbering mistake, and its decisions would otherwise be
     * silently ignored. The shard contract itself is always checked too.
     *
     * @param prepDirPath {@link Path} the prep directory being validated
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param options {@link ApplyOptions} apply behavior flags
     * @return {@link ValidationReport} the merged validation report of decisions and findings
     */
    ValidationReport validate(Path prepDirPath, PrepDir prepDir, ApplyOptions options) {
        var extraFindings = new ArrayList<Finding>();

        Set<String> missingMontages = prepDir.entries().stream()
                .filter(montage -> !cullPrepPort.hasShard(prepDirPath, montage))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!options.allowPartial()) {
            missingMontages.forEach(montage ->
                    extraFindings.add(new Finding.MissingShard(montage, MontageNaming.shardFileFor(montage))));
        }

        Set<String> expectedShardNames = prepDir.entries().stream()
                .map(MontageNaming::shardFileFor)
                .collect(Collectors.toSet());
        mediaStore.listFiles(prepDirPath).stream()
                .map(file -> file.getFileName().toString())
                .filter(name -> name.startsWith("decisions-") && name.endsWith(".json"))
                .filter(name -> !expectedShardNames.contains(name))
                .sorted()
                .map(Finding.StrayShard::new)
                .forEach(extraFindings::add);

        List<Path> sidecarSrcs = prepDir.entries().stream()
                .flatMap(montage -> cullPrepPort.readSidecar(prepDirPath, montage).stream())
                .map(SidecarPhotoEntry::src)
                .toList();
        List<ShardFile> shardFiles = prepDir.entries().stream()
                .filter(montage -> !missingMontages.contains(montage))
                .map(montage -> new ShardFile(montage, cullPrepPort.readShard(prepDirPath, montage)))
                .toList();
        List<String> categories = cullSettings.categories().stream().map(CullCategory::name).toList();

        ValidationReport report = shardValidator.validate(shardFiles, sidecarSrcs, categories, prepDir.unreviewable());
        if (extraFindings.isEmpty()) {
            return report;
        }
        extraFindings.addAll(report.findings());
        return new ValidationReport(extraFindings, report.heals(), report.decisions());
    }

    /**
     * Read-only pass over prepDir's already-validated decisions and unreviewable files, checking
     * which of them are missing on disk with no move record verifying they were already moved.
     * Shares classify()/classifyFile() with apply()'s own gate; touches nothing. PrepDirDoctor's own
     * call site, since apply() runs this same check inline as part of its single classify() pass
     * rather than calling this method (avoiding a redundant second hash-verification pass).
     *
     * @param prepDirPath {@link Path} the prep directory being checked
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param decisions a {@link List} of {@link Decision} the validated, heal-corrected decisions
     * @return a {@link List} of {@link Finding} a MissingSource finding for each unresolved file
     */
    List<Finding> checkMissingSources(Path prepDirPath, PrepDir prepDir, List<Decision> decisions) {
        Path moveRecordLog = prepDirPath.resolve(MOVE_RECORD_LOG);
        Map<Path, MoveRecord> moveRecords = readMoveRecords(moveRecordLog);
        var findings = new ArrayList<Finding>();
        decisions.stream()
                .map(decision -> classify(decision, moveRecords))
                .filter(Status.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.decision().file(), moveRecordLog))
                .forEach(findings::add);
        prepDir.unreviewable().stream()
                .map(file -> classifyFile(file, moveRecords))
                .filter(FileStatus.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.file(), moveRecordLog))
                .forEach(findings::add);
        return findings;
    }

    /**
     * An offline repair for when move-records.log itself cannot be trusted, missing or found with
     * this run's shards otherwise intact. Any existing log is filed into prepDir's disaster drawer
     * wholesale, never salvaged line-by-line. The log is then rebuilt from scratch, purely from what
     * disk state can prove: every already-validated decision and unreviewable file is checked
     * against the exact destination applying it would have produced, recording the hash of whatever
     * is found there.
     *
     * <p>This is a name-and-location match, not a proof of identity. The original file's own hash
     * was never recorded anywhere but the log this repair is replacing, so there is nothing to
     * verify a located file against. A file already sitting untouched needs no record at all -
     * classify() already treats an existing source as pending regardless of the log. NearDupChosen
     * never gets a rebuilt record: it is a copy, so a missing source can only mean the photo itself
     * is gone, never an unconfirmed move. That is the same rule classify() already applies. A file's
     * destination is only ever reconstructed when it can be identified unambiguously - see
     * {@link #resolvePendingMoves} for the exact rule and its residual risk.
     *
     * @param prepDirPath {@link Path} the prep directory to reconcile
     * @return {@link ReconcileReport} what the sweep found
     * @throws ApplyException if the shard contract itself does not validate cleanly
     */
    public ReconcileReport reconcile(Path prepDirPath) throws ApplyException {
        PrepDir prepDir = cullPrepPort.readIndex(prepDirPath);
        ValidationReport validation = validate(prepDirPath, prepDir, new ApplyOptions(true));
        if (!validation.valid()) {
            throw failure(validation.findings());
        }

        Path moveRecordLog = prepDirPath.resolve(MOVE_RECORD_LOG);
        if (mediaStore.exists(moveRecordLog)) {
            disasterDrawer.file(prepDirPath, moveRecordLog, "move-records-log");
        }

        var sweep = new ReconcileSweep(moveRecordLog);
        validation.decisions().forEach(decision -> reconcileDecision(decision, sweep));
        prepDir.unreviewable().forEach(file -> reconcileFile(file, unreviewableDir(file), sweep));
        resolvePendingMoves(sweep);

        return new ReconcileReport(sweep.reconstructed, sweep.stillPending, sweep.missingSource);
    }

    /**
     * reconcile()'s per-decision step. A NearDupChosen decision is checked for existence right here,
     * since it is a copy. A missing source is always missing-source for it, never a pending move -
     * classify() never has a move-record path for it either, and reconcile() must not pretend
     * otherwise. Every other decision defers its own existence check entirely to reconcileFile()
     * below, which every other caller of reconcileFile() also relies on.
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
        reconcileFile(decision.file(), destinationDirFor(decision), sweep);
    }

    /**
     * reconcile()'s core per-file step, shared by a move-based decision and an unreviewable file
     * alike. A file still at its original location needs no record. Otherwise, it is queued as a
     * pending move - resolvePendingMoves() decides, once every decision has been swept, whether this
     * file's destination can be identified unambiguously.
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
     * name) - the same key two decisions that started with an identical leaf name would share.
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
     * tell that case apart from a genuine match, and that hash lived only in the log this repair is
     * replacing. This residual risk is accepted rather than chased.
     *
     * @param sweep {@link ReconcileSweep} the sweep's accumulating outcome
     */
    private void resolvePendingMoves(ReconcileSweep sweep) {
        final Map<String, List<PendingMove>> groups = new LinkedHashMap<>();
        for (PendingMove move : sweep.pendingMoves) {
            final String key = move.destDir() + RECORD_DELIMITER + move.file().getFileName();
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(move);
        }
        groups.values().forEach(claimants -> resolveGroup(claimants, sweep));
    }

    /**
     * One (destDir, file name) group's worth of resolvePendingMoves() - see that method's Javadoc for
     * the unambiguity rule this enforces.
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
            final String hash = sha256Port.hash(located);
            mediaStore.appendLine(sweep.moveRecordLog,
                    file + RECORD_DELIMITER + located + RECORD_DELIMITER + hash + RECORD_DELIMITER + RECONSTRUCTED_MARKER);
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
        Path candidate = destDir.resolve(candidateName(baseName, slot));
        while (mediaStore.exists(candidate)) {
            candidates.add(candidate);
            slot++;
            candidate = destDir.resolve(candidateName(baseName, slot));
        }
        return candidates;
    }

    /**
     * The exact destination directory apply() would move decision into - the same per-category or
     * per-near-dup-group logic applyClassification()/applyNearDupReject() themselves use. Never
     * called for NearDupChosen: reconcile() always resolves that case before reaching here, since a
     * copy's missing source has no destination to search in the first place.
     *
     * @param decision {@link Decision} the decision to resolve a destination directory for
     * @return {@link Path} the destination directory
     */
    private Path destinationDirFor(Decision decision) {
        return switch (decision) {
            case Classification c -> c.category().equals(FUNNY_CATEGORY)
                    ? pathsPort.library().resolve("Funny")
                    : pathsPort.review().resolve(c.category());
            case NearDupReject r -> duplicatesDir(r.file(), r.group());
            case NearDupChosen _ -> throw new IllegalStateException(
                    "NearDupChosen never reaches destinationDirFor - reconcileDecision() resolves it first");
        };
    }

    /**
     * The Nth collision candidate for baseName - itself unchanged for slot 1, then " (2)", " (3)",
     * ... before the extension, matching NioMediaStore's own collision-naming convention exactly.
     *
     * @param baseName {@link String} the original file name
     * @param slot int the 1-based candidate slot
     * @return {@link String} the candidate file name
     */
    private static String candidateName(String baseName, int slot) {
        if (slot == 1) {
            return baseName;
        }
        int dot = baseName.lastIndexOf('.');
        String base = dot <= 0 ? baseName : baseName.substring(0, dot);
        String extension = dot <= 0 ? "" : baseName.substring(dot);
        return base + " (" + slot + ")" + extension;
    }

    /**
     * A file reconcileFile() could not find still sitting at its original location. destDir is the
     * exact directory a real apply() would have moved it into, carried alongside so
     * resolvePendingMoves() can group and search without looking the decision back up.
     *
     * @param file {@link Path} the source file that could not be found at its original location
     * @param destDir {@link Path} the directory a real apply() would have moved file into
     */
    private record PendingMove(Path file, Path destDir) {
    }

    /**
     * reconcile()'s accumulating outcome as it sweeps every decision and unreviewable file in turn.
     * moveRecordLog is carried here so resolvePendingMoves() can append a freshly reconstructed
     * record to it. It also names the log in a MissingSource finding, without threading it through
     * every call as its own parameter.
     */
    private static final class ReconcileSweep {
        final List<PendingMove> pendingMoves = new ArrayList<>();
        final List<Finding.MissingSource> missingSource = new ArrayList<>();
        final Path moveRecordLog;
        int reconstructed;
        int stillPending;

        ReconcileSweep(Path moveRecordLog) {
            this.moveRecordLog = moveRecordLog;
        }
    }

    /**
     * ShardValidator checks a decision's file against the sidecar's in-scope set, not the
     * filesystem. Whether it still exists on disk, or was already carried out by an earlier run, is
     * this engine's job.
     *
     * <p>A decision whose source file is still on disk is always pending, regardless of the
     * move-record log. A move that never happened needs no verification - it just needs doing.
     * NearDupChosen is a copy, so its source never disappears once the decision genuinely ran. A
     * missing source for it can only mean the file was never there, never that the copy is "done
     * but unconfirmed." There is no move-record path for it.
     *
     * <p>Every other decision (Classification, NearDupReject) is a move. Once it genuinely runs, its
     * source is gone for good. That's exactly the case a plain exists() check can't tell apart from
     * "never ran" or "ran but crashed before finishing." recordThenMove() closes that gap by durably
     * recording the source's hash and its exact, already-collision-resolved destination BEFORE the
     * move. A missing source can then be positively confirmed as done by re-hashing that one
     * recorded destination and checking it matches. No guessing at possible destination names
     * required. No record, a missing destination, or a hash mismatch all mean the same thing. This
     * engine cannot tell what happened to the file, and refuses rather than guessing.
     *
     * @param decision {@link Decision} the decision to classify
     * @param moveRecords a {@link Map} of {@link Path} to {@link MoveRecord} move records keyed by source path
     * @return {@link Status} this decision's pending/done/unresolved status
     */
    private Status classify(Decision decision, Map<Path, MoveRecord> moveRecords) {
        if (mediaStore.exists(decision.file())) {
            return new Status.Pending(decision);
        }
        if (decision instanceof NearDupChosen) {
            return new Status.Unresolved(decision);
        }
        Optional<MoveRecord> record = verifiedMoveRecord(decision.file(), moveRecords);
        return record.isPresent()
                ? new Status.Done(decision, record.get())
                : new Status.Unresolved(decision);
    }

    /**
     * classify()'s sibling for an unreviewable file. It has no shard-driven category and no
     * NearDupChosen-shaped copy exception - every unreviewable file is a plain move. So a missing
     * source is Pending only when a verified move record explains it, Unresolved otherwise.
     *
     * @param file {@link Path} the unreviewable file to classify
     * @param moveRecords a {@link Map} of {@link Path} to {@link MoveRecord} move records keyed by source path
     * @return {@link FileStatus} this file's pending/done/unresolved status
     */
    private FileStatus classifyFile(Path file, Map<Path, MoveRecord> moveRecords) {
        if (mediaStore.exists(file)) {
            return new FileStatus.Pending(file);
        }
        return verifiedMoveRecord(file, moveRecords).isPresent()
                ? new FileStatus.Done(file)
                : new FileStatus.Unresolved(file);
    }

    /**
     * The move-record log only proves a move happened when its recorded destination still exists
     * and still hashes to the recorded value. A record alone is never trusted on its own.
     *
     * @param file {@link Path} the source path a move record might exist for
     * @param moveRecords a {@link Map} of {@link Path} to {@link MoveRecord} move records keyed by source path
     * @return an {@link Optional} {@link MoveRecord} the verified move record, if one hash-verifies
     */
    private Optional<MoveRecord> verifiedMoveRecord(Path file, Map<Path, MoveRecord> moveRecords) {
        MoveRecord record = moveRecords.get(file);
        boolean verified = record != null && mediaStore.exists(record.dest())
                && sha256Port.hash(record.dest()).equals(record.hash());
        return verified ? Optional.of(record) : Optional.empty();
    }

    /**
     * Runs only for a decision classify() already hash-verified as done. It never re-decides the
     * move itself. It only backfills the one write that could have landed after it and is still
     * missing: a funny decision's library hash-index row, or a review category's _reasons.txt
     * line. NearDupReject has no write beyond the move, already fully confirmed by classify()
     * alone.
     *
     * @param decision {@link Decision} the already-verified-done decision
     * @param record {@link MoveRecord} the verified move record proving it ran
     */
    private void backfillSecondaryWrite(Decision decision, MoveRecord record) {
        if (decision instanceof Classification c) {
            backfillClassificationWrite(c, record);
        }
    }

    /**
     * Backfills a classification's secondary write: a funny hash-index row, or a review reason line.
     *
     * @param c {@link Classification} the classification decision
     * @param record {@link MoveRecord} the verified move record
     */
    private void backfillClassificationWrite(Classification c, MoveRecord record) {
        if (c.category().equals(FUNNY_CATEGORY)) {
            // HashIndexPort.contains(hash) alone isn't enough. The index legitimately allows several
            // paths under one hash (byte-identical files kept in more than one place). Another entry
            // sharing this hash would wrongly read as "this decision's own row is already there" -
            // the path has to match too.
            boolean alreadyIndexed = hashIndexPort.load().getOrDefault(record.hash(), List.of()).contains(record.dest());
            if (!alreadyIndexed) {
                hashIndexPort.append(List.of(new IndexEntry(record.hash(), record.dest())));
            }
        } else {
            Path reasonsFile = pathsPort.review().resolve(c.category()).resolve(REASONS_FILE);
            String line = c.file().getFileName() + " - " + c.reason();
            if (!mediaStore.readLines(reasonsFile).contains(line)) {
                mediaStore.appendLine(reasonsFile, line);
            }
        }
    }

    /**
     * Parses the move-record log into a map keyed by source path.
     *
     * @param moveRecordLog {@link Path} the move-record log file
     * @return a {@link Map} of {@link Path} to {@link MoveRecord} move records keyed by source path
     */
    private Map<Path, MoveRecord> readMoveRecords(Path moveRecordLog) {
        Map<Path, MoveRecord> records = new HashMap<>();
        for (String line : mediaStore.readLines(moveRecordLog)) {
            String[] fields = line.split(RECORD_DELIMITER, -1);
            boolean witnessed = fields.length == 3;
            boolean reconstructed = fields.length == 4 && RECONSTRUCTED_MARKER.equals(fields[3]);
            if (witnessed || reconstructed) {
                records.put(Path.of(fields[0]), new MoveRecord(Path.of(fields[1]), fields[2]));
            }
        }
        return records;
    }

    /**
     * Builds the aggregated exception for a list of findings.
     *
     * @param findings a {@link List} of {@link Finding} the findings to report
     * @return {@link ApplyException} the exception describing all findings
     */
    private static ApplyException failure(List<Finding> findings) {
        List<String> messages = findings.stream().map(Finding::describe).toList();
        return new ApplyException("Shard validation failed - " + messages.size()
                + " problem(s), nothing applied:\n  - " + String.join("\n  - ", messages), findings);
    }

    /**
     * Every near-dup decision (chosen or reject), keyed by group, regardless of whether it will be
     * skipped this run. A resumed run's chosen-note must still list every reject, including ones a
     * prior run already moved.
     *
     * @param decisions a {@link List} of {@link Decision} the full decisions list
     * @return a {@link Map} of {@link String} to a {@link List} of {@link Decision} near-dup decisions grouped by group id
     */
    private static Map<String, List<Decision>> groupNearDups(List<Decision> decisions) {
        Map<String, List<Decision>> byGroup = new HashMap<>();
        for (Decision decision : decisions) {
            switch (decision) {
                case NearDupChosen c -> byGroup.computeIfAbsent(c.group(), _ -> new ArrayList<>()).add(decision);
                case NearDupReject r -> byGroup.computeIfAbsent(r.group(), _ -> new ArrayList<>()).add(decision);
                case Classification _ -> {
                }
            }
        }
        return byGroup;
    }

    /**
     * Dispatches a pending decision to its type-specific apply method.
     *
     * @param decision {@link Decision} the pending decision to apply
     * @param moveRecordLog {@link Path} the move-record log to append to
     * @param nearDupGroups a {@link Map} of {@link String} to a {@link List} of {@link Decision} near-dup decisions grouped by group id
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void apply(Decision decision, Path moveRecordLog, Map<String, List<Decision>> nearDupGroups, ApplyOutcome outcome) {
        switch (decision) {
            case Classification c -> applyClassification(c, moveRecordLog, outcome);
            case NearDupChosen c -> applyNearDupChosen(c, nearDupGroups.get(c.group()), outcome);
            case NearDupReject r -> applyNearDupReject(r, moveRecordLog, outcome);
        }
    }

    /**
     * funny is the one category with a fixed destination: the library's flat Funny/ folder. It's
     * hashed into the library index and gets no reason note, since it's being kept, not set aside
     * for review. Every other category, junk included, routes generically to Review/<category>/
     * with a _reasons.txt note. There is no per-category destination configuration yet.
     *
     * <p>The index append happens immediately, not batched after the loop. A decision an earlier,
     * crashed run already carried out is skipped on resume (backfillSecondaryWrite() handles it
     * instead), so it never reaches this method again. A batched append collected only from this
     * run's own outcome would then permanently lose that file's index row.
     *
     * @param c {@link Classification} the classification decision
     * @param moveRecordLog {@link Path} the move-record log to append to
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyClassification(Classification c, Path moveRecordLog, ApplyOutcome outcome) {
        outcome.byCategory.merge(c.category(), 1, Integer::sum);
        boolean funny = c.category().equals(FUNNY_CATEGORY);
        Path destDir = destinationDirFor(c);
        MoveOutcome moved = recordThenMove(c.file(), destDir, moveRecordLog);
        if (funny) {
            hashIndexPort.append(List.of(new IndexEntry(moved.hash(), moved.dest())));
        } else {
            mediaStore.appendLine(destDir.resolve(REASONS_FILE), c.file().getFileName() + " - " + c.reason());
        }
    }

    /**
     * The keeper is copied, not moved. It stays a normal Sorted keeper, with a courtesy copy left
     * for context alongside the rejects it was chosen over.
     *
     * <p>Unlike every other decision type, its source file is never removed, so classify() never routes
     * it through the move-record path. A resumed run would otherwise re-copy it, landing a stray
     * " (2)" duplicate in Duplicates/, and re-appending a now-duplicated note line.
     *
     * <p>Guarded explicitly here instead: the copy is skipped when the exact destination this decision
     * would produce already exists. The note is always (re)written wholesale, never appended to.
     * That makes re-running safe regardless of how far a prior attempt got.
     *
     * @param c {@link NearDupChosen} the chosen near-dup decision
     * @param group a {@link List} of {@link Decision} all decisions in this near-dup group
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyNearDupChosen(NearDupChosen c, List<Decision> group, ApplyOutcome outcome) {
        Path dupDir = duplicatesDir(c.file(), c.group());
        Path dest = dupDir.resolve(c.file().getFileName().toString());
        if (!mediaStore.exists(dest)) {
            mediaStore.copy(c.file(), dupDir);
        }
        mediaStore.write(dupDir.resolve(c.file().getFileName() + ".txt"), chosenNote(c, group));
        outcome.nearDupGroupsChosen.add(c.group());
    }

    /**
     * Moves a rejected near-dup file into its duplicates group folder.
     *
     * @param r {@link NearDupReject} the rejected near-dup decision
     * @param moveRecordLog {@link Path} the move-record log to append to
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyNearDupReject(NearDupReject r, Path moveRecordLog, ApplyOutcome outcome) {
        recordThenMove(r.file(), destinationDirFor(r), moveRecordLog);
        outcome.nearDupRejects++;
    }

    /**
     * Resolves a near-dup group's destination folder under Duplicates/.
     *
     * @param file {@link Path} a file in the group, used to derive year-month
     * @param group {@link String} the near-dup group id
     * @return {@link Path} the group's duplicates folder
     */
    private Path duplicatesDir(Path file, String group) {
        return pathsPort.duplicates().resolve(yearMonthOf(file) + "_" + group);
    }

    /**
     * Unlike every other category, which routes flatly to Review/<category>/, an unreviewable file
     * carries no category or reason to group by. So it keeps the <yyyy>/<mm> structure its Sorted
     * location already had - the same segments yearMonthOf() reads off for Duplicates.
     *
     * @param file {@link Path} the unreviewable file
     * @return {@link Path} its destination folder under the unreviewable root
     */
    private Path unreviewableDir(Path file) {
        String[] yearMonth = yearMonthOf(file).split("-", 2);
        return pathsPort.unreviewable().resolve(yearMonth[0]).resolve(yearMonth[1]);
    }

    /**
     * Reserves the exact destination and durably records source-hash-plus-destination BEFORE
     * moving. That covers a crash any time after this point, whether it lands during the move
     * itself or during whatever write normally follows it. classify() can then always tell the
     * move already happened, hash-verified rather than a guess. The hash is computed once and
     * reused by the caller (e.g. for a funny decision's index row) instead of re-hashing the same
     * bytes twice.
     *
     * @param source {@link Path} the file to move
     * @param destDir {@link Path} the destination directory
     * @param moveRecordLog {@link Path} the move-record log to append to
     * @return {@link MoveOutcome} the resolved destination and source hash
     */
    private MoveOutcome recordThenMove(Path source, Path destDir, Path moveRecordLog) {
        Path dest = mediaStore.resolveDestination(source, destDir);
        String hash = sha256Port.hash(source);
        mediaStore.appendLine(moveRecordLog, source + RECORD_DELIMITER + dest + RECORD_DELIMITER + hash);
        mediaStore.moveTo(source, dest);
        return new MoveOutcome(dest, hash);
    }

    /**
     * Builds the note text recording which file was chosen and why, plus its rejects.
     *
     * @param chosen {@link NearDupChosen} the chosen near-dup decision
     * @param group a {@link List} of {@link Decision} all decisions in this near-dup group
     * @return {@link String} the note's text
     */
    private static String chosenNote(NearDupChosen chosen, List<Decision> group) {
        String rejects = group.stream()
                .filter(NearDupReject.class::isInstance)
                .map(NearDupReject.class::cast)
                .map(r -> r.file().getFileName() + " - " + r.reason())
                .collect(Collectors.joining("; "));
        return "Chose " + chosen.file().getFileName() + " - " + chosen.chosenReason() + ". Rejects: " + rejects;
    }

    /**
     * A Sorted-relative file always sits under a .../<yyyy>/<MM>/ pair of directories. Read off the
     * path segments directly rather than pattern-matching the string form. Pattern-matching a string
     * is separator-sensitive across platforms, and unnecessary here - this app's Sorted layout
     * already guarantees the segments. Falls back to a clearly-undated marker if that guarantee
     * somehow doesn't hold (e.g. a file sitting directly under the scope's base path).
     *
     * @param file {@link Path} the file to derive year-month from
     * @return {@link String} the "yyyy-MM" string, or an undated marker
     */
    private static String yearMonthOf(Path file) {
        Path monthDir = file.getParent();
        Path yearDir = monthDir == null ? null : monthDir.getParent();
        if (yearDir == null) {
            return UNDATED;
        }
        String month = monthDir.getFileName().toString();
        String year = yearDir.getFileName().toString();
        return year.matches("\\d{4}") && month.matches("\\d{2}") ? year + "-" + month : UNDATED;
    }

    /**
     * Drops the montage contact sheets and tile images once every decision has been carried out -
     * always, even when zero decisions exist. index.json, the per-montage shards, the move-record
     * log, and the merged decisions.json are all left in place.
     *
     * @param prepDirPath {@link Path} the prep directory to clean up
     */
    private void cleanupIntermediates(Path prepDirPath) {
        for (Path file : mediaStore.listFiles(prepDirPath)) {
            String name = file.getFileName().toString();
            if (name.startsWith("montage-") || name.startsWith("tile-")) {
                mediaStore.delete(file);
            }
        }
    }

    private static final class ApplyOutcome {
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Set<String> nearDupGroupsChosen = new HashSet<>();
        int nearDupRejects;
    }

    // One line in the move-record log: the exact, already-collision-resolved destination a move-
    // based decision's source was hashed and headed for, recorded before the move itself ran (or,
    // for a RECONSTRUCTED record, the destination reconcile() located and hashed after the fact).
    // classify() trusts a WITNESSED and a RECONSTRUCTED record identically. The marker distinguishing
    // them in the log's own text is provenance for a human reader, never a behavioral distinction.
    // It is not itself a field here.
    private record MoveRecord(Path dest, String hash) {}

    // The destination and hash recordThenMove() just produced. Handed back so a caller (a funny
    // decision's index row) can reuse the same hash instead of re-hashing the file a second time.
    private record MoveOutcome(Path dest, String hash) {}

    // classify()'s verdict for one decision. Done carries the MoveRecord that proved it, as a
    // non-null component. Unlike a single status-plus-nullable-record shape, a decision that
    // isn't Done simply has no Done case to carry one - there is nothing for a caller to
    // null-check.
    private sealed interface Status {
        /**
         * The decision this status describes.
         *
         * @return {@link Decision} the decision
         */
        Decision decision();

        record Pending(Decision decision) implements Status {}

        record Done(Decision decision, MoveRecord record) implements Status {}

        record Unresolved(Decision decision) implements Status {}
    }

    // classifyFile()'s verdict for one unreviewable file - Status's sibling for a plain Path with no
    // Decision behind it. Done carries no record: unlike a Classification or NearDupReject, an
    // unreviewable file has no secondary write to reconcile, so confirming the move alone is enough.
    private sealed interface FileStatus {
        /**
         * The unreviewable file this status describes.
         *
         * @return {@link Path} the file path
         */
        Path file();

        record Pending(Path file) implements FileStatus {}

        record Done(Path file) implements FileStatus {}

        record Unresolved(Path file) implements FileStatus {}
    }
}
