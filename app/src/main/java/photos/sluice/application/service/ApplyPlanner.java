package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.MalformedPrepJsonException;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.PathsPort;
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
import photos.sluice.domain.paths.Containment;

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
 * snapshot rather than reading one itself. This class cannot even read the ledger files on its
 * own, let alone append to them.
 *
 * <p>It holds no cull settings either, on the same principle. A run is judged against the category
 * set its own {@link PrepDir} recorded at prep time. With no settings to reach for, judging it
 * against live config instead is a compile error rather than a convention.
 *
 * <p>The {@link PathsPort} it does hold is no exception to any of that. It resolves configured
 * folder roots and offers nothing that writes.
 *
 * <p>Flowchart: {@code app/docs/design/application/service/apply-planner.md}.
 */
@Component
public class ApplyPlanner {

    private final MediaReader mediaReader;
    private final CullPrepPort cullPrepPort;
    private final Sha256Port sha256Port;
    private final PathsPort pathsPort;
    private final ShardValidator shardValidator = new ShardValidator();

    /**
     * Creates a planner wired to its ports.
     *
     * @param mediaReader {@link MediaReader} checks file existence and lists prep-dir files
     * @param cullPrepPort {@link CullPrepPort} reads prep-dir sidecars and shards
     * @param sha256Port {@link Sha256Port} hashes a destination to verify a recorded move
     * @param pathsPort {@link PathsPort} resolves the Sorted root every source is held to
     */
    public ApplyPlanner(final MediaReader mediaReader, final CullPrepPort cullPrepPort,
                        final Sha256Port sha256Port, final PathsPort pathsPort) {
        this.mediaReader = mediaReader;
        this.cullPrepPort = cullPrepPort;
        this.sha256Port = sha256Port;
        this.pathsPort = pathsPort;
    }

    /**
     * Merges three problem sources into one report, reporting a problem rather than throwing on one.
     * A caller that must not proceed throws on an invalid result itself, while a read-only diagnosis
     * reads the identical report. A failed read still propagates: this is a claim about findings,
     * not a totality guarantee, and {@link PrepDirDoctor} is where that distinction is handled.
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
     * <p>This is the single validator on the resume path, so it has to catch everything a culler's
     * own batch check would have. That means a stray shard, a group id reused across two montages,
     * and a shard present but unparseable, alongside the whole per-decision contract.
     *
     * <p>It also answers which files this run may touch at all, over both lists its sources come
     * from. Every one of them must sit inside the configured Sorted root, which is the only place
     * prep looks for candidates. Anything else is a {@link Finding.SourceOutsideSorted}. See
     * {@link #checkSourceRoot} for why both lists need it.
     *
     * @param prepDirPath {@link Path} the prep directory being validated
     * @param prepDir {@link PrepDir} the prep directory's index
     * @param options {@link ApplyOptions} apply behavior flags
     * @param ledger {@link Ledger} the caller's own move-ledger snapshot
     * @return {@link ValidationReport} the merged validation report of decisions and findings
     */
    ValidationReport validate(final Path prepDirPath, final PrepDir prepDir, final ApplyOptions options,
                              final Ledger ledger) {
        final var extraFindings = new ArrayList<Finding>();

        final Set<String> missingMontages = prepDir.entries().stream()
                .filter(montage -> !this.cullPrepPort.hasShard(prepDirPath, montage))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!options.allowPartial()) {
            missingMontages.forEach(montage ->
                    extraFindings.add(new Finding.MissingShard(montage, MontageNaming.shardFileFor(montage))));
        }

        final Set<String> expectedShardNames = prepDir.entries().stream()
                .map(MontageNaming::shardFileFor)
                .collect(Collectors.toSet());
        this.mediaReader.listFiles(prepDirPath).stream()
                .map(file -> file.getFileName().toString())
                .filter(MontageNaming::isShardFile)
                .filter(name -> !expectedShardNames.contains(name))
                .sorted()
                .map(Finding.StrayShard::new)
                .forEach(extraFindings::add);

        final var sidecarSrcs = new ArrayList<Path>();
        final var shardFiles = new ArrayList<ShardFile>();
        for (final String montage : prepDir.entries()) {
            this.collectMontage(prepDirPath, montage, !missingMontages.contains(montage), ledger,
                    sidecarSrcs, shardFiles, extraFindings);
        }
        this.checkSourceRoot(sidecarSrcs, prepDir.unreviewable(), extraFindings);
        final ValidationReport report = resolveOverlaps(ledger,
                this.shardValidator.validate(shardFiles, sidecarSrcs, prepDir.categoryNames(),
                        prepDir.unreviewable()));
        if (extraFindings.isEmpty()) {
            return report;
        }
        extraFindings.addAll(report.findings());
        return new ValidationReport(extraFindings, report.heals(), report.decisions());
    }

    /**
     * Holds every file this run could act on to the configured Sorted root, reporting one
     * {@link Finding.SourceOutsideSorted} per offender. A path naming any other place did not come
     * from a prep run, so the run stops before a single file moves.
     *
     * <p>Both lists are checked, because both are files on disk that something other than this app
     * could have written. index.json carries the unreviewable entries. The sidecars carry the
     * {@code src} set. A decision's own file is only ever trusted by way of that set, so checking
     * the set is what covers every decision too. A prep dir whose sidecars are intact but whose
     * index.json was edited, or the reverse, is caught either way round.
     *
     * <p>The two lists are merged before checking, so one escaping path named by both is reported
     * once. Twice would read as two separate things to put right.
     *
     * @param sidecarSrcs a {@link List} of {@link Path} every in-scope file the montages showed
     * @param unreviewable a {@link List} of {@link Path} index.json's own unreviewable entries
     * @param extraFindings a {@link List} of {@link Finding} accumulated findings beyond the shard contract
     */
    private void checkSourceRoot(final List<Path> sidecarSrcs, final List<Path> unreviewable,
                                 final List<Finding> extraFindings) {
        final Path sortedRoot = this.pathsPort.sorted();
        final Set<Path> sources = new LinkedHashSet<>(sidecarSrcs);
        sources.addAll(unreviewable);
        sources.stream()
                .filter(source -> !Containment.strictlyUnder(sortedRoot, source))
                .map(source -> new Finding.SourceOutsideSorted(source, sortedRoot))
                .forEach(extraFindings::add);
    }

    /**
     * One montage's worth of validate()'s sidecar/shard collection. A readable sidecar always
     * contributes its srcs to the in-scope pool, and its shard too once the montage actually has
     * one. Otherwise the disposition ledger decides. APPLY_ANYWAY trusts the shard's own decisions
     * as their own scope, no sidecar needed to corroborate them. SET_ASIDE drops the montage
     * entirely - no shard, no srcs, exactly like a ledger-skipped file. No resolution yet reports a
     * fresh {@link Finding.CorruptSidecar}.
     *
     * <p>That finding is raised whether or not the montage already has a shard. A montage with a
     * corrupt sidecar and no shard is not a montage still being culled, however much it looks like
     * one. A culler keys its verdicts against the sidecar, so it cannot produce a shard for a
     * montage whose sidecar it cannot read. Waiting for one means waiting forever. Reported
     * instead, SET_ASIDE becomes reachable, which drops the montage and leaves its photos in Sorted
     * for a later cull to see fresh.
     *
     * <p>A shard that is present but cannot be parsed is a {@link Finding.CorruptShard}, never an
     * exception escaping this method. It is a culling-agent content mistake, so it belongs in the
     * same aggregated report as every other one. That matters most on the apply-only resume path,
     * where no culler runs and this is the only gate the shard ever passes through.
     *
     * @param prepDirPath {@link Path} the prep directory being validated
     * @param montage {@link String} the montage id to collect
     * @param hasShard boolean whether this montage currently has a shard
     * @param ledger {@link Ledger} the parsed disposition ledger
     * @param sidecarSrcs a {@link List} of {@link Path} accumulated in-scope files
     * @param shardFiles a {@link List} of {@link ShardFile} accumulated shards to validate
     * @param extraFindings a {@link List} of {@link Finding} accumulated findings beyond the shard contract
     */
    private void collectMontage(final Path prepDirPath, final String montage, final boolean hasShard,
                                final Ledger ledger,
                                final List<Path> sidecarSrcs, final List<ShardFile> shardFiles,
                                final List<Finding> extraFindings) {
        final Optional<List<Path>> srcs = Sidecars.srcsOf(this.cullPrepPort, prepDirPath, montage);
        if (srcs.isPresent()) {
            sidecarSrcs.addAll(srcs.get());
            if (hasShard) {
                this.readShard(prepDirPath, montage, extraFindings)
                        .ifPresent(shard -> shardFiles.add(new ShardFile(montage, shard, srcs.get())));
            }
            return;
        }
        final CorruptSidecarResolution resolution = ledger.corruptSidecars().get(montage);
        if (resolution == null) {
            extraFindings.add(new Finding.CorruptSidecar(montage));
            return;
        }
        // Either answer is terminal, so neither raises the finding again. SET_ASIDE drops the
        // montage outright. APPLY_ANYWAY trusts the shard as its own scope. A montage answered that
        // way while still holding no shard contributes nothing, there being no shard yet to trust.
        if (resolution == CorruptSidecarResolution.APPLY_ANYWAY && hasShard) {
            this.readShard(prepDirPath, montage, extraFindings).ifPresent(shard -> {
                shard.verdicts().forEach(verdict -> sidecarSrcs.add(verdict.file()));
                // No sheet list, so no coverage rule. Nothing here knows what that sheet showed,
                // and this resolution is the reader having said to trust the shard's own account
                // of it.
                shardFiles.add(new ShardFile(montage, shard, List.of()));
            });
        }
    }

    /**
     * Reads one montage's shard, recording a {@link Finding.CorruptShard} instead of throwing when
     * the content cannot be turned into decisions. Only malformed content is caught. A read that
     * merely failed while the shard itself is intact propagates, so a lock or a permission denial
     * never gets diagnosed as a culler mistake.
     *
     * @param prepDirPath {@link Path} the prep directory holding the shard
     * @param montage {@link String} the montage whose shard to read
     * @param extraFindings a {@link List} of {@link Finding} accumulated findings beyond the shard contract
     * @return an {@link Optional} {@link DecisionShard} the parsed shard, or empty if it is unreadable
     */
    private Optional<DecisionShard> readShard(final Path prepDirPath, final String montage,
                                              final List<Finding> extraFindings) {
        try {
            return Optional.of(this.cullPrepPort.readShard(prepDirPath, montage));
        } catch (final MalformedPrepJsonException e) {
            extraFindings.add(new Finding.CorruptShard(montage, MontageNaming.shardFileFor(montage)));
            return Optional.empty();
        }
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
    List<Path> resolvedUnreviewable(final PrepDir prepDir, final Ledger ledger) {
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
    List<Finding> checkMissingSources(final PrepDir prepDir, final List<Decision> decisions, final Ledger ledger) {
        final var findings = new ArrayList<Finding>();
        decisions.stream()
                .map(decision -> this.classify(decision, ledger))
                .filter(Status.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.decision().file(), ledger.moveRecordLog()))
                .forEach(findings::add);
        this.resolvedUnreviewable(prepDir, ledger).stream()
                .map(file -> this.classifyFile(file, ledger))
                .filter(FileStatus.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.file(), ledger.moveRecordLog()))
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
    Status classify(final Decision decision, final Ledger ledger) {
        if (this.mediaReader.exists(decision.file())) {
            return new Status.Pending(decision);
        }
        if (ledger.skipped().contains(decision.file())) {
            return new Status.Skipped(decision);
        }
        if (decision instanceof NearDupChosen) {
            return new Status.Unresolved(decision);
        }
        final Optional<MoveRecord> record = this.verifiedMoveRecord(decision.file(), ledger.moves());
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
    FileStatus classifyFile(final Path file, final Ledger ledger) {
        if (this.mediaReader.exists(file)) {
            return new FileStatus.Pending(file);
        }
        if (ledger.skipped().contains(file)) {
            return new FileStatus.Skipped(file);
        }
        return this.verifiedMoveRecord(file, ledger.moves())
                .<FileStatus>map(record -> new FileStatus.Done(file, record))
                .orElseGet(() -> new FileStatus.Unresolved(file));
    }

    /**
     * Builds the aggregated exception for a list of findings.
     *
     * @param findings a {@link List} of {@link Finding} the findings to report
     * @return {@link ApplyException} the exception describing all findings
     */
    static ApplyException failure(final List<Finding> findings) {
        final List<String> messages = findings.stream().map(Finding::describe).toList();
        return new ApplyException("Shard validation failed - " + messages.size()
                + " problem(s), nothing applied:\n  - " + String.join("\n  - ", messages), findings);
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
    private static ValidationReport resolveOverlaps(final Ledger ledger, final ValidationReport report) {
        final Map<Path, OverlapResolution> overlaps = ledger.overlaps();
        if (overlaps.isEmpty()) {
            return report;
        }
        final var findings = new ArrayList<Finding>();
        final var decisions = new ArrayList<>(report.decisions());
        for (final Finding finding : report.findings()) {
            if (finding instanceof Finding.DecisionUnreviewableOverlap(final Decision decision)
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
     * The move-record log only proves a move happened when its recorded destination still exists
     * and still hashes to the recorded value. A record alone is never trusted on its own.
     *
     * @param file {@link Path} the source path a move record might exist for
     * @param moveRecords a {@link Map} of {@link Path} to {@link MoveRecord} move records keyed by source path
     * @return an {@link Optional} {@link MoveRecord} the verified move record, if one hash-verifies
     */
    private Optional<MoveRecord> verifiedMoveRecord(final Path file, final Map<Path, MoveRecord> moveRecords) {
        final MoveRecord record = moveRecords.get(file);
        final boolean verified = record != null && this.mediaReader.exists(record.dest())
                && this.sha256Port.hash(record.dest()).equals(record.hash());
        return verified ? Optional.of(record) : Optional.empty();
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
     * with no decision behind it.
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
         * @param record {@link MoveRecord} the record proving it, naming where it landed
         */
        record Done(Path file, MoveRecord record) implements FileStatus {
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
