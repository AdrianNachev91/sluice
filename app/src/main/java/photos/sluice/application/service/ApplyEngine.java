package photos.sluice.application.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.CullPrepPort;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.service.ApplyPlanner.FileStatus;
import photos.sluice.application.service.ApplyPlanner.Status;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.application.service.MoveLedger.MoveRecord;
import photos.sluice.domain.cull.ApplyReport;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;
import photos.sluice.domain.cull.Finding;
import photos.sluice.domain.cull.MontageNaming;
import photos.sluice.domain.cull.PrepDir;
import photos.sluice.domain.cull.ValidationReport;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Carries a prep directory's culling decisions out against the filesystem. This is the only class
 * that moves, copies, or deletes anything a cull decided on.
 *
 * <p>It decides nothing itself. {@link ApplyPlanner} says which decisions are still pending and
 * which an earlier run already finished. {@link CullDestinations} says where each one belongs.
 * {@link MoveLedger} records what happened. What is left here is the carrying out, plus the two
 * finalizers that close a completed run.
 *
 * <p>Every move is recorded before it runs, never after. That ordering is what makes a crashed run
 * resumable and a completed run safe to apply again - see {@link #recordThenMove}.
 *
 * <p>Flowchart + scenario table: {@code app/docs/design/application/service/apply-engine.md}.
 */
@Component
public class ApplyEngine {

    private static final String REASONS_FILE = "_reasons.txt";

    private final MediaStore mediaStore;
    private final CullPrepPort cullPrepPort;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final CullDestinations cullDestinations;
    private final MoveLedger moveLedger;
    private final ApplyPlanner applyPlanner;

    /**
     * Creates an engine wired to its ports and collaborators.
     *
     * @param mediaStore {@link MediaStore} filesystem effects (move, copy, read, write)
     * @param cullPrepPort {@link CullPrepPort} reads the prep-dir index and writes merged decisions
     * @param sha256Port {@link Sha256Port} hashes a source before its move is recorded
     * @param hashIndexPort {@link HashIndexPort} reads/appends the library hash index
     * @param cullDestinations {@link CullDestinations} resolves where each decision's file belongs
     * @param moveLedger {@link MoveLedger} records each move before it runs
     * @param applyPlanner {@link ApplyPlanner} validates the batch and classifies each decision
     */
    public ApplyEngine(final MediaStore mediaStore, final CullPrepPort cullPrepPort, final Sha256Port sha256Port,
                       final HashIndexPort hashIndexPort, final CullDestinations cullDestinations,
                       final MoveLedger moveLedger,
                       final ApplyPlanner applyPlanner) {
        this.mediaStore = mediaStore;
        this.cullPrepPort = cullPrepPort;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.cullDestinations = cullDestinations;
        this.moveLedger = moveLedger;
        this.applyPlanner = applyPlanner;
    }

    /**
     * Applies a prep directory's decisions.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @return {@link ApplyReport} the applied run's summary report
     * @throws ApplyException if validation finds unresolved problems
     */
    public ApplyReport apply(final Path prepDirPath, final ApplyOptions options) throws ApplyException {
        return this.apply(prepDirPath, options, ProgressCallback.NO_OP);
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
    public ApplyReport apply(final Path prepDirPath, final ApplyOptions options, final ProgressCallback progress) throws ApplyException {
        // NEVER never trips, so the cancellation-aware overload below always runs to completion
        // and returns non-null here - this just asserts that rather than silently trusting it.
        return Objects.requireNonNull(this.apply(prepDirPath, options, progress, CancellationSignal.NEVER));
    }

    /**
     * Applies a prep directory's decisions, cancellable mid-run.
     *
     * <p>The whole batch is validated in one pass before anything moves. Every decision is then
     * classified against the disposition ledger. A decision whose file is still on disk is pending.
     * One that's gone but hash-verifies at its recorded destination is already done, its secondary
     * write (if any) reconciled rather than redone. index.json's own unreviewable list goes through
     * the same classification - it has no shard-driven category, just a plain move once carried out.
     * Anything unresolved, decision or unreviewable file alike, aborts the whole run before a single
     * file moves. Once everything is handled, the merged decisions.json is written and the montage
     * and tile intermediates are deleted.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @param cancellation {@link CancellationSignal} signal checked between file operations
     * @return {@link ApplyReport} the applied run's summary report, or null if cancelled
     * @throws ApplyException if validation finds unresolved problems
     */
    public @Nullable ApplyReport apply(final Path prepDirPath, final ApplyOptions options,
                                       final ProgressCallback progress,
                                       final CancellationSignal cancellation) throws ApplyException {
        final PrepDir prepDir = this.cullPrepPort.readIndex(prepDirPath);
        // One snapshot for this whole run, taken before anything below could append to the ledger.
        final Ledger ledger = this.moveLedger.read(prepDirPath);
        final ValidationReport validation = this.applyPlanner.validate(prepDirPath, prepDir, options, ledger);
        if (!validation.valid()) {
            throw ApplyPlanner.failure(validation.findings());
        }

        final List<Path> unreviewableFiles = this.applyPlanner.resolvedUnreviewable(prepDir, ledger);
        final List<Status> statuses = validation.decisions().stream()
                .map(decision -> this.applyPlanner.classify(decision, ledger))
                .toList();
        final List<FileStatus> unreviewableStatuses = unreviewableFiles.stream()
                .map(file -> this.applyPlanner.classifyFile(file, ledger))
                .toList();
        final List<Finding> missingSource = new ArrayList<>();
        statuses.stream()
                .filter(Status.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.decision().file(), ledger.moveRecordLog()))
                .forEach(missingSource::add);
        unreviewableStatuses.stream()
                .filter(FileStatus.Unresolved.class::isInstance)
                .map(status -> new Finding.MissingSource(status.file(), ledger.moveRecordLog()))
                .forEach(missingSource::add);
        if (!missingSource.isEmpty()) {
            throw ApplyPlanner.failure(missingSource);
        }

        final Map<String, List<Decision>> nearDupGroups = groupNearDups(validation.decisions());
        final Map<String, Path> nearDupAnchors = CullDestinations.nearDupAnchors(validation.decisions());
        final var outcome = new ApplyOutcome();
        // Both loops below can move a file, so both count toward the total a caller is told about.
        // Otherwise progress would reach 100% while unreviewable files are still being moved.
        final int total = statuses.size() + unreviewableStatuses.size();
        int current = 0;
        // Checked per decision, in both loops below. On cancel, the finalizers past this point -
        // writeMergedDecisions() and cleanupIntermediates() - must not run, so this returns null
        // outright rather than falling through to them. No decisions.json means the prep dir still
        // reads as a waiting job (see dispatchAndApply()'s own null handling).
        for (final Status status : statuses) {
            if (cancellation.isCancelled()) {
                return null;
            }
            switch (status) {
                case final Status.Pending p -> this.apply(p.decision(), prepDirPath, nearDupGroups, nearDupAnchors, outcome);
                case final Status.Done d -> this.backfillSecondaryWrite(d.decision(), d.record());
                case Status.Skipped _ -> {} // user gave up on this decision - nothing to do
                case Status.Unresolved _ -> {} // already aborted the whole run above
            }
            progress.tick(++current, total);
        }
        for (final FileStatus status : unreviewableStatuses) {
            if (cancellation.isCancelled()) {
                return null;
            }
            if (status instanceof FileStatus.Pending(final Path file)) {
                this.recordThenMove(file, this.cullDestinations.unreviewableDir(file), prepDirPath);
            }
            // Done, Skipped: nothing further to do here.
            progress.tick(++current, total);
        }

        final var report = new ApplyReport(prepDir.photos(), outcome.byCategory, unreviewableFiles.size(),
                outcome.nearDupGroupsChosen.size(), outcome.nearDupRejects, validation.heals());
        final ApplyReport persistedSummary = summarize(validation.decisions(), prepDir, unreviewableFiles.size(),
                validation.heals());
        this.cullPrepPort.writeMergedDecisions(prepDirPath, prepDir.scope(), validation.decisions(), persistedSummary);
        this.cleanupIntermediates(prepDirPath);
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
     * @param unreviewableCount int prepDir's own unreviewable count, ledger-resolved overlaps already excluded
     * @param heals a {@link List} of {@link String} healed-shard messages to include
     * @return {@link ApplyReport} a freshly recomputed summary report
     */
    private static ApplyReport summarize(final List<Decision> decisions, final PrepDir prepDir,
                                         final int unreviewableCount,
                                         final List<String> heals) {
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Set<String> groups = new HashSet<>();
        int rejects = 0;
        for (final Decision decision : decisions) {
            switch (decision) {
                case final Classification c -> byCategory.merge(c.category(), 1, Integer::sum);
                case final NearDupChosen c -> groups.add(c.group());
                case NearDupReject _ -> rejects++;
            }
        }
        return new ApplyReport(prepDir.photos(), byCategory, unreviewableCount, groups.size(), rejects, heals);
    }

    /**
     * Runs only for a decision already hash-verified as done. It never re-decides the move itself.
     * It only backfills the one write that could have landed after it and is still missing. That's a
     * funny decision's library hash-index row, or a review category's _reasons.txt line.
     * NearDupReject has no write beyond the move, already fully confirmed by classification alone.
     *
     * @param decision {@link Decision} the already-verified-done decision
     * @param record {@link MoveRecord} the verified move record proving it ran
     */
    private void backfillSecondaryWrite(final Decision decision, final MoveRecord record) {
        if (decision instanceof final Classification c) {
            this.backfillClassificationWrite(c, record);
        }
    }

    /**
     * Backfills a classification's secondary write: a funny hash-index row, or a review reason line.
     *
     * @param c {@link Classification} the classification decision
     * @param record {@link MoveRecord} the verified move record
     */
    private void backfillClassificationWrite(final Classification c, final MoveRecord record) {
        if (c.category().equals(CullDestinations.FUNNY_CATEGORY)) {
            // HashIndexPort.contains(hash) alone isn't enough. The index legitimately allows several
            // paths under one hash (byte-identical files kept in more than one place). Another entry
            // sharing this hash would wrongly read as "this decision's own row is already there" -
            // the path has to match too.
            final boolean alreadyIndexed = this.hashIndexPort.load()
                    .getOrDefault(record.hash(), List.of())
                    .contains(record.dest());
            if (!alreadyIndexed) {
                this.hashIndexPort.append(List.of(new IndexEntry(record.hash(), record.dest())));
            }
        } else {
            final Path reasonsFile = this.cullDestinations.destinationDirFor(c).resolve(REASONS_FILE);
            final String line = c.file().getFileName() + " - " + c.reason();
            if (!this.mediaStore.readLines(reasonsFile).contains(line)) {
                this.mediaStore.appendLine(reasonsFile, line);
            }
        }
    }

    /**
     * Every near-dup decision (chosen or reject), keyed by group, regardless of whether it will be
     * skipped this run. A resumed run's chosen-note must still list every reject, including ones a
     * prior run already moved.
     *
     * @param decisions a {@link List} of {@link Decision} the full decisions list
     * @return a {@link Map} of {@link String} to a {@link List} of {@link Decision} near-dup decisions grouped by
     * group id
     */
    private static Map<String, List<Decision>> groupNearDups(final List<Decision> decisions) {
        final Map<String, List<Decision>> byGroup = new HashMap<>();
        for (final Decision decision : decisions) {
            switch (decision) {
                case final NearDupChosen c -> byGroup.computeIfAbsent(c.group(), _ -> new ArrayList<>()).add(decision);
                case final NearDupReject reject -> byGroup.computeIfAbsent(reject.group(), _ -> new ArrayList<>()).add(decision);
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
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @param nearDupGroups a {@link Map} of {@link String} to a {@link List} of {@link Decision} near-dup decisions
     * grouped by group id
     * @param nearDupAnchors a {@link Map} of {@link String} to {@link Path} each near-dup group's keeper file, by
     * group id
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void apply(final Decision decision, final Path prepDirPath, final Map<String, List<Decision>> nearDupGroups,
                       final Map<String, Path> nearDupAnchors, final ApplyOutcome outcome) {
        switch (decision) {
            case final Classification c -> this.applyClassification(c, prepDirPath, outcome);
            case final NearDupChosen c -> this.applyNearDupChosen(c, nearDupGroups.get(c.group()), outcome);
            case final NearDupReject reject -> this.applyNearDupReject(reject, nearDupAnchors.get(reject.group()), prepDirPath, outcome);
        }
    }

    /**
     * A funny classification is kept, so it is hashed into the library index and gets no reason
     * note. Every other category, junk included, gets a _reasons.txt note alongside its move.
     *
     * <p>The index append happens immediately, not batched after the loop. A decision an earlier,
     * crashed run already carried out is skipped on resume (backfillSecondaryWrite() handles it
     * instead), so it never reaches this method again. A batched append collected only from this
     * run's own outcome would then permanently lose that file's index row.
     *
     * @param c {@link Classification} the classification decision
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyClassification(final Classification c, final Path prepDirPath, final ApplyOutcome outcome) {
        outcome.byCategory.merge(c.category(), 1, Integer::sum);
        final Path destDir = this.cullDestinations.destinationDirFor(c);
        final MoveOutcome moved = this.recordThenMove(c.file(), destDir, prepDirPath);
        if (c.category().equals(CullDestinations.FUNNY_CATEGORY)) {
            this.hashIndexPort.append(List.of(new IndexEntry(moved.hash(), moved.dest())));
        } else {
            this.mediaStore.appendLine(destDir.resolve(REASONS_FILE), c.file().getFileName() + " - " + c.reason());
        }
    }

    /**
     * The keeper is copied, not moved. It stays a normal Sorted keeper, with a courtesy copy left
     * for context alongside the rejects it was chosen over.
     *
     * <p>Unlike every other decision type, its source file is never removed, so classification never
     * routes it through the move-record path. A resumed run would otherwise re-copy it, landing a
     * stray " (2)" duplicate in Duplicates/, and re-appending a now-duplicated note line.
     *
     * <p>Guarded explicitly here instead: the copy is skipped when the exact destination this
     * decision would produce already exists. The note is always (re)written wholesale, never
     * appended to. That makes re-running safe regardless of how far a prior attempt got.
     *
     * @param c {@link NearDupChosen} the chosen near-dup decision
     * @param group a {@link List} of {@link Decision} all decisions in this near-dup group
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyNearDupChosen(final NearDupChosen c, final List<Decision> group, final ApplyOutcome outcome) {
        // A copy rather than a move, so it takes the source check directly. Every other decision
        // type picks it up from recordThenMove.
        this.cullDestinations.requireUnderSorted(c.file());
        final Path dupDir = this.cullDestinations.duplicatesDir(c.file(), c.group());
        final Path dest = dupDir.resolve(c.file().getFileName().toString());
        if (!this.mediaStore.exists(dest)) {
            this.mediaStore.copy(c.file(), dupDir);
        }
        this.mediaStore.write(dupDir.resolve(c.file().getFileName() + ".txt"), chosenNote(c, group));
        outcome.nearDupGroupsChosen.add(c.group());
    }

    /**
     * Moves a rejected near-dup file into its duplicates group folder. The folder is resolved from
     * the group's chosen keeper, not from reject's own file - see
     * {@link CullDestinations#duplicatesDir}.
     *
     * @param reject {@link NearDupReject} the rejected near-dup decision
     * @param groupAnchor {@link Path} the group's chosen keeper file
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void applyNearDupReject(final NearDupReject reject, final Path groupAnchor, final Path prepDirPath,
                                    final ApplyOutcome outcome) {
        this.recordThenMove(reject.file(), this.cullDestinations.duplicatesDir(groupAnchor, reject.group()), prepDirPath);
        outcome.nearDupRejects++;
    }

    /**
     * Reserves the exact destination and durably records source-hash-plus-destination BEFORE
     * moving. That covers a crash any time after this point, whether it lands during the move
     * itself or during whatever write normally follows it. Classification can then always tell the
     * move already happened, hash-verified rather than a guess. The hash is computed once and
     * reused by the caller (e.g. for a funny decision's index row) instead of re-hashing the same
     * bytes twice.
     *
     * @param source {@link Path} the file to move
     * @param destDir {@link Path} the destination directory
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @return {@link MoveOutcome} the resolved destination and source hash
     */
    private MoveOutcome recordThenMove(final Path source, final Path destDir, final Path prepDirPath) {
        this.cullDestinations.requireUnderSorted(source);
        final Path dest = this.mediaStore.resolveDestination(source, destDir);
        final String hash = this.sha256Port.hash(source);
        this.moveLedger.recordMove(prepDirPath, source, dest, hash);
        this.mediaStore.moveTo(source, dest);
        return new MoveOutcome(dest, hash);
    }

    /**
     * Builds the note text recording which file was chosen and why, plus its rejects.
     *
     * @param chosen {@link NearDupChosen} the chosen near-dup decision
     * @param group a {@link List} of {@link Decision} all decisions in this near-dup group
     * @return {@link String} the note's text
     */
    private static String chosenNote(final NearDupChosen chosen, final List<Decision> group) {
        final String rejects = group.stream()
                .filter(NearDupReject.class::isInstance)
                .map(NearDupReject.class::cast)
                .map(reject -> reject.file().getFileName() + " - " + reject.reason())
                .collect(Collectors.joining("; "));
        return "Chose " + chosen.file().getFileName() + " - " + chosen.chosenReason() + ". Rejects: " + rejects;
    }

    /**
     * Drops the montage contact sheets and tile images once every decision has been carried out -
     * always, even when zero decisions exist. index.json, the per-montage sidecars and shards, the
     * move ledger, and the merged decisions.json are all left in place. A montage's own sidecar JSON
     * shares the "montage-" filename prefix with its contact-sheet image, so the two are told apart
     * by extension. Keeping the sidecar is what lets validation still find it readable on a later
     * call. A second apply on an already-complete run then validates cleanly and returns as a no-op
     * instead of throwing.
     *
     * @param prepDirPath {@link Path} the prep directory to clean up
     */
    private void cleanupIntermediates(final Path prepDirPath) {
        for (final Path file : this.mediaStore.listFiles(prepDirPath)) {
            if (MontageNaming.isMontageImage(file.getFileName().toString())) {
                this.mediaStore.delete(file);
            }
        }
    }

    /**
     * What one run actually did, accumulated as its decisions are carried out.
     */
    private static final class ApplyOutcome {
        final Map<String, Integer> byCategory = new TreeMap<>();
        final Set<String> nearDupGroupsChosen = new HashSet<>();
        int nearDupRejects;
    }

    /**
     * The destination and hash {@link #recordThenMove} just produced. Handed back so a caller (a
     * funny decision's index row) can reuse the same hash instead of re-hashing the file a second
     * time.
     *
     * @param dest {@link Path} the destination the source was moved to
     * @param hash {@link String} the source's hash, taken before the move
     */
    private record MoveOutcome(Path dest, String hash) {
    }
}
