package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullCategory;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.CullSettings;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.application.service.MoveLedger.MoveRecord;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.DecisionShard;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.OverlapResolution;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ShardValidator;
import photos.sluice.domain.cull.ShardValidator.ShardFile;
import photos.sluice.domain.cull.ValidationReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides what a prep directory's run still has left to do, before anything moves. It answers two
 * questions and changes nothing while answering them.
 *
 * <p>The first is whether the shards form a valid batch at all: every montage accounted for, every
 * decision on contract, no file acted on twice. The second is where each decision already stands -
 * pending, already done, skipped by the user, or unresolvable. Both answers are read-only, which is
 * what lets a proactive diagnosis and a failing apply describe the identical set of findings.
 *
 * <p>That read-only property is structural, not a convention. This class holds a
 * {@link MediaReader} rather than a {@code MediaStore}, so no move, copy, write, or delete is
 * reachable from here at all. Every method here also takes its caller's own {@link Ledger}
 * snapshot rather than reading one itself. This class cannot even read the ledger file on its
 * own, let alone append to it.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/apply-planner.md}.
 */
@Component
public class ApplyPlanner {

    private final MediaReader mediaReader;
    private final CullPrepPort cullPrepPort;
    private final CullSettings cullSettings;
    private final Sha256Port sha256Port;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates a planner wired to its ports.
     *
     * @param mediaReader {@link MediaReader} checks file existence and lists prep-dir files
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir sidecars and shards
     * @param cullSettings {@link CullSettings} configured cull categories
     * @param sha256Port {@link Sha256Port} hashes a destination to verify a recorded move
     */
    public ApplyPlanner(MediaReader mediaReader, CullPrepPort cullPrepPort, CullSettings cullSettings,
            Sha256Port sha256Port) {
        this.mediaReader = mediaReader;
        this.cullPrepPort = cullPrepPort;
        this.cullSettings = cullSettings;
        this.sha256Port = sha256Port;
    }

    /**
     * Merges three problem sources into one report, never throwing. A caller that must not proceed
     * throws on an invalid result itself, while a read-only diagnosis reads the identical report.
     *
     * <p>A missing montage shard is a finding only when allowPartial waives it. A diagnosis always
     * passes allowPartial, so a still-culling prep dir reports on the shards it already has rather
     * than drowning in "not culled yet" noise. A decisions file with no matching montage is always a
     * finding: almost always a culler numbering mistake, and its decisions would otherwise be
     * silently ignored. The shard contract itself is always checked too.
     *
     * <p>The shard contract is checked ONCE, over every montage's shards together. Several of its
     * rules only exist across shards. A group id reused by two montages, a file acted on by two
     * different montages, a basename that only heals while unique across the whole scope. Validating
     * one montage at a time would silently disable every one of them.
     *
     * @param prepDirPath {@link Path} the prep directory being validated
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param options {@link ApplyOptions} apply behavior flags
     * @param ledger {@link Ledger} the caller's own move-ledger snapshot
     * @return {@link ValidationReport} the merged validation report of decisions and findings
     */
    ValidationReport validate(Path prepDirPath, PrepDir prepDir, ApplyOptions options, Ledger ledger) {
        final var extraFindings = new ArrayList<Finding>();

        final Set<String> missingMontages = prepDir.entries().stream()
                .filter(montage -> !cullPrepPort.hasShard(prepDirPath, montage))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!options.allowPartial()) {
            missingMontages.forEach(montage ->
                    extraFindings.add(new Finding.MissingShard(montage, MontageNaming.shardFileFor(montage))));
        }

        final Set<String> expectedShardNames = prepDir.entries().stream()
                .map(MontageNaming::shardFileFor)
                .collect(Collectors.toSet());
        mediaReader.listFiles(prepDirPath).stream()
                .map(file -> file.getFileName().toString())
                .filter(MontageNaming::isShardFile)
                .filter(name -> !expectedShardNames.contains(name))
                .sorted()
                .map(Finding.StrayShard::new)
                .forEach(extraFindings::add);

        final var sidecarSrcs = new ArrayList<Path>();
        final var shardFiles = new ArrayList<ShardFile>();
        for (String montage : prepDir.entries()) {
            collectMontage(prepDirPath, montage, !missingMontages.contains(montage), ledger,
                    sidecarSrcs, shardFiles, extraFindings);
        }
        final List<String> categories = cullSettings.categories().stream().map(CullCategory::name).toList();

        final ValidationReport report = resolveOverlaps(ledger,
                shardValidator.validate(shardFiles, sidecarSrcs, categories, prepDir.unreviewable()));
        if (extraFindings.isEmpty()) {
            return report;
        }
        extraFindings.addAll(report.findings());
        return new ValidationReport(extraFindings, report.heals(), report.decisions());
    }

    /**
     * One montage's worth of validate()'s sidecar/shard collection. A readable sidecar always
     * contributes its srcs to the in-scope pool, and its shard too once the montage actually has
     * one. An unreadable sidecar for a montage that hasn't been culled yet is silently skipped - not
     * yet actionable, the same reasoning missingMontages already gets. Otherwise the disposition
     * ledger decides. APPLY_ANYWAY trusts the shard's own decisions as their own scope, no sidecar
     * needed to corroborate them. SET_ASIDE drops the montage entirely - no shard, no srcs, exactly
     * like a ledger-skipped file. No resolution yet reports a fresh {@link Finding.CorruptSidecar}.
     *
     * @param prepDirPath {@link Path} the prep directory being validated
     * @param montage {@link String} the montage id to collect
     * @param hasShard boolean whether this montage currently has a shard
     * @param ledger {@link Ledger} the parsed disposition ledger
     * @param sidecarSrcs a {@link List} of {@link Path} accumulated in-scope files
     * @param shardFiles a {@link List} of {@link ShardFile} accumulated shards to validate
     * @param extraFindings a {@link List} of {@link Finding} accumulated findings beyond the shard contract
     */
    private void collectMontage(Path prepDirPath, String montage, boolean hasShard, Ledger ledger,
            List<Path> sidecarSrcs, List<ShardFile> shardFiles, List<Finding> extraFindings) {
        final Optional<List<Path>> srcs = Sidecars.srcsOf(cullPrepPort, prepDirPath, montage);
        if (srcs.isPresent()) {
            sidecarSrcs.addAll(srcs.get());
            if (hasShard) {
                shardFiles.add(new ShardFile(montage, cullPrepPort.readShard(prepDirPath, montage)));
            }
            return;
        }
        if (!hasShard) {
            return;
        }
        final CorruptSidecarResolution resolution = ledger.corruptSidecars().get(montage);
        if (resolution == CorruptSidecarResolution.APPLY_ANYWAY) {
            final DecisionShard shard = cullPrepPort.readShard(prepDirPath, montage);
            shard.decisions().forEach(decision -> sidecarSrcs.add(decision.file()));
            shardFiles.add(new ShardFile(montage, shard));
        } else if (resolution != CorruptSidecarResolution.SET_ASIDE) {
            extraFindings.add(new Finding.CorruptSidecar(montage));
        }
    }

    /**
     * Suppresses a {@link Finding.DecisionUnreviewableOverlap} finding once the disposition ledger
     * records how the user resolved it, dropping the losing side from the decisions this run acts
     * on. Shards and index.json are never edited. TREAT_AS_UNREVIEWABLE only removes the decision
     * from this in-memory list. A TRUST_DECISION resolution suppresses the other side instead,
     * wherever {@link #resolvedUnreviewable} is consulted.
     *
     * @param ledger {@link Ledger} the parsed disposition ledger
     * @param report {@link ValidationReport} the shard validator's own report, before ledger resolution
     * @return {@link ValidationReport} the same report, with resolved overlaps suppressed
     */
    private static ValidationReport resolveOverlaps(Ledger ledger, ValidationReport report) {
        final Map<Path, OverlapResolution> overlaps = ledger.overlaps();
        if (overlaps.isEmpty()) {
            return report;
        }
        final var findings = new ArrayList<Finding>();
        final var decisions = new ArrayList<>(report.decisions());
        for (Finding finding : report.findings()) {
            if (finding instanceof Finding.DecisionUnreviewableOverlap(Decision decision)
                    && overlaps.containsKey(decision.file())) {
                if (overlaps.get(decision.file()) == OverlapResolution.TREAT_AS_UNREVIEWABLE) {
                    decisions.remove(decision);
                }
            } else {
                findings.add(finding);
            }
        }
        return new ValidationReport(findings, report.heals(), decisions);
    }

    /**
     * prepDir's own unreviewable list, minus any file the disposition ledger has resolved with
     * TRUST_DECISION. The shard's own decision wins for those, so the file is no longer treated as
     * unreviewable at all. index.json itself is never edited; this filtering happens purely in
     * memory, every time the list is consulted.
     *
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param ledger {@link Ledger} the caller's own move-ledger snapshot
     * @return a {@link List} of {@link Path} prepDir's unreviewable files, TRUST_DECISION-resolved ones excluded
     */
    List<Path> resolvedUnreviewable(PrepDir prepDir, Ledger ledger) {
        final Map<Path, OverlapResolution> overlaps = ledger.overlaps();
        if (overlaps.isEmpty()) {
            return prepDir.unreviewable();
        }
        return prepDir.unreviewable().stream()
                .filter(file -> overlaps.get(file) != OverlapResolution.TRUST_DECISION)
                .toList();
    }

    /**
     * Read-only pass over prepDir's already-validated decisions and unreviewable files, checking
     * which of them are missing on disk with no move record verifying they were already moved.
     * Shares classify()/classifyFile() with an apply's own gate; touches nothing. An apply runs this
     * same check inline, as part of its single classify() pass, rather than calling this method -
     * avoiding a redundant second hash-verification pass.
     *
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param decisions a {@link List} of {@link Decision} the validated, heal-corrected decisions
     * @param ledger {@link Ledger} the caller's own move-ledger snapshot
     * @return a {@link List} of {@link Finding} a MissingSource finding for each unresolved file
     */
    List<Finding> checkMissingSources(PrepDir prepDir, List<Decision> decisions, Ledger ledger) {
        final var findings = new ArrayList<Finding>();
        decisions.stream()
                .map(decision -> classify(decision, ledger))
                .filter(Status.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.decision().file(), ledger.log()))
                .forEach(findings::add);
        resolvedUnreviewable(prepDir, ledger).stream()
                .map(file -> classifyFile(file, ledger))
                .filter(FileStatus.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.file(), ledger.log()))
                .forEach(findings::add);
        return findings;
    }

    /**
     * ShardValidator checks a decision's file against the sidecar's in-scope set, not the
     * filesystem. Whether it still exists on disk, or was already carried out by an earlier run, is
     * decided here.
     *
     * <p>A decision whose source file is still on disk is always pending, regardless of the
     * move-record log. A move that never happened needs no verification - it just needs doing. That
     * check comes first, so a file the user gave up on and then restored simply applies normally.
     *
     * <p>NearDupChosen is a copy, so its source never disappears once the decision genuinely ran. A
     * missing source for it can only mean the file was never there. It never means the copy is done
     * but unconfirmed. There is no move-record path for it, and a record naming one is not trusted.
     *
     * <p>Every other decision (Classification, NearDupReject) is a move. Once it genuinely runs, its
     * source is gone for good. A plain exists() check cannot tell that apart from "never ran" or
     * "ran but crashed before finishing." A move record written BEFORE the move closes that gap. It
     * captures the source's hash and its exact, already-collision-resolved destination. A missing
     * source can then be positively confirmed as done by re-hashing that one recorded destination
     * and checking it matches. No guessing at possible destination names required. No record, a
     * missing destination, or a hash mismatch all mean the same thing. Which of those happened
     * cannot be told apart, so this refuses rather than guessing.
     *
     * <p>A file the disposition ledger records as skipped is Skipped regardless of decision type,
     * checked before the NearDupChosen case above it. The user gave up on it, so there is nothing
     * left to move or verify.
     *
     * @param decision {@link Decision} the decision to classify
     * @param ledger {@link Ledger} the parsed disposition ledger
     * @return {@link Status} this decision's pending/done/skipped/unresolved status
     */
    Status classify(Decision decision, Ledger ledger) {
        if (mediaReader.exists(decision.file())) {
            return new Status.Pending(decision);
        }
        if (ledger.skipped().contains(decision.file())) {
            return new Status.Skipped(decision);
        }
        if (decision instanceof NearDupChosen) {
            return new Status.Unresolved(decision);
        }
        final Optional<MoveRecord> record = verifiedMoveRecord(decision.file(), ledger.moves());
        return record.isPresent()
                ? new Status.Done(decision, record.get())
                : new Status.Unresolved(decision);
    }

    /**
     * classify()'s sibling for an unreviewable file. It has no shard-driven category and no
     * NearDupChosen-shaped copy exception - every unreviewable file is a plain move. So a missing
     * source is Done only when a verified move record explains it, Skipped when the user gave up on
     * it, Unresolved otherwise.
     *
     * @param file {@link Path} the unreviewable file to classify
     * @param ledger {@link Ledger} the parsed disposition ledger
     * @return {@link FileStatus} this file's pending/done/skipped/unresolved status
     */
    FileStatus classifyFile(Path file, Ledger ledger) {
        if (mediaReader.exists(file)) {
            return new FileStatus.Pending(file);
        }
        if (ledger.skipped().contains(file)) {
            return new FileStatus.Skipped(file);
        }
        return verifiedMoveRecord(file, ledger.moves()).isPresent()
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
        final MoveRecord record = moveRecords.get(file);
        final boolean verified = record != null && mediaReader.exists(record.dest())
                && sha256Port.hash(record.dest()).equals(record.hash());
        return verified ? Optional.of(record) : Optional.empty();
    }

    /**
     * Builds the aggregated exception for a list of findings.
     *
     * @param findings a {@link List} of {@link Finding} the findings to report
     * @return {@link ApplyException} the exception describing all findings
     */
    static ApplyException failure(List<Finding> findings) {
        final List<String> messages = findings.stream().map(Finding::describe).toList();
        return new ApplyException("Shard validation failed - " + messages.size()
                + " problem(s), nothing applied:\n  - " + String.join("\n  - ", messages), findings);
    }

    /**
     * classify()'s verdict for one decision. Done carries the {@link MoveRecord} that proved it, as
     * a non-null component. A decision that isn't Done simply has no Done case to carry one, so
     * there is nothing for a caller to null-check.
     */
    sealed interface Status {

        /**
         * The decision this status describes.
         *
         * @return {@link Decision} the decision
         */
        Decision decision();

        /**
         * The decision has not been carried out yet, and its source is still on disk.
         *
         * @param decision {@link Decision} the decision still to carry out
         */
        record Pending(Decision decision) implements Status {
        }

        /**
         * The decision was already carried out, proven by a hash-verified move record.
         *
         * @param decision {@link Decision} the already-applied decision
         * @param record {@link MoveRecord} the verified move record proving it ran
         */
        record Done(Decision decision, MoveRecord record) implements Status {
        }

        /**
         * The user gave up on this decision rather than restoring its missing file. Terminal, like
         * Done: no move or write is ever carried out for it again.
         *
         * @param decision {@link Decision} the abandoned decision
         */
        record Skipped(Decision decision) implements Status {
        }

        /**
         * The source is gone and nothing proves where it went. Blocks the whole run.
         *
         * @param decision {@link Decision} the unresolvable decision
         */
        record Unresolved(Decision decision) implements Status {
        }
    }

    /**
     * classifyFile()'s verdict for one unreviewable file - {@link Status}'s sibling for a plain path
     * with no decision behind it. Done carries no record: an unreviewable file has no secondary
     * write to reconcile, so confirming the move alone is enough.
     */
    sealed interface FileStatus {

        /**
         * The unreviewable file this status describes.
         *
         * @return {@link Path} the file path
         */
        Path file();

        /**
         * The file has not been moved yet, and is still on disk.
         *
         * @param file {@link Path} the file still to move
         */
        record Pending(Path file) implements FileStatus {
        }

        /**
         * The file was already moved, proven by a hash-verified move record.
         *
         * @param file {@link Path} the already-moved file
         */
        record Done(Path file) implements FileStatus {
        }

        /**
         * The user gave up on this file rather than restoring it.
         *
         * @param file {@link Path} the abandoned file
         */
        record Skipped(Path file) implements FileStatus {
        }

        /**
         * The file is gone and nothing proves where it went. Blocks the whole run.
         *
         * @param file {@link Path} the unresolvable file
         */
        record Unresolved(Path file) implements FileStatus {
        }
    }
}
