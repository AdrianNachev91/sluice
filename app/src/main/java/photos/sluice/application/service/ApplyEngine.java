package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.ApplyException;
import photos.sluice.application.port.out.ApplyOptions;
import photos.sluice.application.port.out.SiftPrepPort;
import photos.sluice.application.port.out.HashIndexPort;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.application.service.ApplyPlanner.FileStatus;
import photos.sluice.application.service.ApplyPlanner.Status;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.application.service.MoveLedger.MoveRecord;
import photos.sluice.domain.sift.ApplyReport;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.Decision.Classification;
import photos.sluice.domain.sift.Decision.NearDupChosen;
import photos.sluice.domain.sift.Decision.NearDupReject;
import photos.sluice.domain.sift.Finding;
import photos.sluice.domain.sift.MontageNaming;
import photos.sluice.domain.sift.PrepDir;
import photos.sluice.domain.sift.ValidationReport;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.IndexEntry;
import photos.sluice.domain.review.ReasonNotes;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Carries a prep directory's sifting decisions out against the filesystem. This is the only class
 * that moves, copies, or deletes anything a sift decided on.
 *
 * <p>It decides nothing itself. {@link ApplyPlanner} says which decisions are still pending and
 * which an earlier run already finished. {@link SiftDestinations} says where each one belongs.
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

    private static final Logger log = LoggerFactory.getLogger(ApplyEngine.class);

    // Read by whoever opens the folder, and the same for every file in it. Nothing per-file is in
    // hand: an unreviewable file arrives as a path, without the reason a classification carries.
    private static final String REASON_UNREVIEWABLE = "could not be seen clearly enough to judge";

    private final MediaStore mediaStore;
    private final SiftPrepPort siftPrepPort;
    private final Sha256Port sha256Port;
    private final HashIndexPort hashIndexPort;
    private final SiftDestinations siftDestinations;
    private final MoveLedger moveLedger;
    private final ApplyPlanner applyPlanner;

    /**
     * Creates an engine wired to its ports and collaborators.
     *
     * @param mediaStore {@link MediaStore} filesystem effects (move, copy, read, write)
     * @param siftPrepPort {@link SiftPrepPort} reads the prep-dir index and writes merged decisions
     * @param sha256Port {@link Sha256Port} hashes a source before its move is recorded
     * @param hashIndexPort {@link HashIndexPort} reads/appends the library hash index
     * @param siftDestinations {@link SiftDestinations} resolves where each decision's file belongs
     * @param moveLedger {@link MoveLedger} records each move before it runs
     * @param applyPlanner {@link ApplyPlanner} validates the batch and classifies each decision
     */
    public ApplyEngine(final MediaStore mediaStore, final SiftPrepPort siftPrepPort, final Sha256Port sha256Port,
                       final HashIndexPort hashIndexPort, final SiftDestinations siftDestinations,
                       final MoveLedger moveLedger,
                       final ApplyPlanner applyPlanner) {
        this.mediaStore = mediaStore;
        this.siftPrepPort = siftPrepPort;
        this.sha256Port = sha256Port;
        this.hashIndexPort = hashIndexPort;
        this.siftDestinations = siftDestinations;
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
        return switch (this.apply(prepDirPath, options, progress, CancellationSignal.NEVER)) {
            case ApplyEnding.Finished(final ApplyReport report) -> report;
            case ApplyEnding.StoppedMidRun _ ->
                    throw new IllegalStateException("an uncancellable apply stopped part way");
        };
    }

    /**
     * Applies a prep directory's decisions, cancellable mid-run.
     *
     * <p>The whole batch is validated in one pass before anything moves, and anything unresolved,
     * decision or unreviewable file alike, aborts the run before a single file moves.
     *
     * <p>Every decision is then classified against the disposition ledger. One whose file is still
     * on disk is pending. One that is gone but hash-verifies at its recorded destination is already
     * done, and its secondary write is reconciled rather than redone.
     *
     * <p>index.json's own unreviewable list goes through the same classification. Its one secondary
     * write is the note line its destination folder carries.
     *
     * @param prepDirPath {@link Path} the prep directory to apply
     * @param options {@link ApplyOptions} apply behavior flags
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @param cancellation {@link CancellationSignal} signal checked between file operations
     * @return {@link ApplyEnding} how it ended, carrying what it moved either way
     * @throws ApplyException if validation finds unresolved problems
     */
    ApplyEnding apply(final Path prepDirPath, final ApplyOptions options,
                      final ProgressCallback progress,
                      final CancellationSignal cancellation) throws ApplyException {
        final PrepDir prepDir = this.siftPrepPort.readIndex(prepDirPath);
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
        final Map<String, Path> nearDupAnchors = SiftDestinations.nearDupAnchors(validation.decisions());
        final var outcome = new ApplyOutcome();
        // Both loops below can move a file, so both count toward the total a caller is told about.
        // Otherwise progress would reach 100% while unreviewable files are still being moved.
        final int total = statuses.size() + unreviewableStatuses.size();
        int current = 0;
        // Counted as they move rather than taken from the planned list, so a run that stops part
        // way reports files rather than intentions.
        int unreviewableMoved = 0;
        // Checked per decision, in both loops below. On cancel, the finalizers past this point -
        // writeMergedDecisions() and cleanupIntermediates() - must not run, so this leaves outright
        // rather than falling through to them. No decisions.json means the prep dir still reads as
        // a waiting job.
        try {
            for (final Status status : statuses) {
                if (cancellation.isCancelled()) {
                    return stoppedPartWay(prepDir, outcome, unreviewableMoved, validation);
                }
                switch (status) {
                    case final Status.Pending p ->
                            this.apply(p.decision(), prepDirPath, nearDupAnchors, outcome,
                                    cancellation, TransferProgress.within(progress, current, total));
                    case final Status.Done d -> this.backfillSecondaryWrite(d.decision(), d.record(), outcome);
                    case Status.Skipped _ -> {} // user gave up on this decision - nothing to do
                    case Status.Unresolved _ -> {} // already aborted the whole run above
                }
                progress.tick(++current, total);
            }
            for (final FileStatus status : unreviewableStatuses) {
                if (cancellation.isCancelled()) {
                    return stoppedPartWay(prepDir, outcome, unreviewableMoved, validation);
                }
                switch (status) {
                    case final FileStatus.Pending pending -> {
                        final Path file = pending.file();
                        final Path destDir = this.siftDestinations.unreviewableDir(file);
                        final MoveOutcome moved = this.recordThenMove(file, destDir, prepDirPath,
                                cancellation, TransferProgress.within(progress, current, total));
                        this.note(file, destDir, moved.dest(), REASON_UNREVIEWABLE);
                        unreviewableMoved++;
                    }
                    case final FileStatus.Done done -> this.backfillUnreviewableNote(done);
                    case FileStatus.Skipped _ -> {} // user gave up on this file - nothing to do
                    case FileStatus.Unresolved _ -> {} // already aborted the whole run above
                }
                progress.tick(++current, total);
            }
            this.writeNearDupNotes(nearDupGroups, outcome);
        } catch (final TransferAbandonedException e) {
            // Stopped for the same reason a cancel between decisions stops: the finalizers must not
            // run. The abandoned decision left a move record with nothing at its destination, which
            // is the crash-between-record-and-move case classification already resolves by hash on
            // the next pass.
            return stoppedPartWay(prepDir, outcome, unreviewableMoved, validation);
        }

        final var report = new ApplyReport(prepDir.photos(), outcome.byCategory, unreviewableFiles.size(),
                outcome.nearDupGroupsChosen.size(), outcome.nearDupRejects, validation.heals());
        final ApplyReport persistedSummary = summarize(validation.decisions(), prepDir, unreviewableFiles.size(),
                validation.heals());
        this.siftPrepPort.writeMergedDecisions(prepDirPath, prepDir.scope(), validation.decisions(), persistedSummary);
        this.cleanupIntermediates(prepDirPath);
        return new ApplyEnding.Finished(report);
    }

    /**
     * What an apply that gave up between two files moved before it did.
     *
     * @param prepDir {@link PrepDir} the run's own prep directory
     * @param outcome {@link ApplyOutcome} what has moved so far
     * @param unreviewableMoved int how many unjudgeable files were moved before it stopped
     * @param validation {@link ValidationReport} the gate this run passed, for its heals
     * @return {@link ApplyEnding} the stopped ending, carrying that report
     */
    private static ApplyEnding stoppedPartWay(final PrepDir prepDir, final ApplyOutcome outcome,
                                              final int unreviewableMoved,
                                              final ValidationReport validation) {
        return new ApplyEnding.StoppedMidRun(new ApplyReport(prepDir.photos(), outcome.byCategory,
                unreviewableMoved, outcome.nearDupGroupsChosen.size(), outcome.nearDupRejects,
                validation.heals()));
    }

    /**
     * The persisted decisions.json embeds a fresh recount over the whole decisions array it sits
     * next to. That covers this run's decisions and every prior run's alike, rather than only the
     * this-run report returned to the caller. Every write overwrites decisions.json wholesale and
     * never appends, so recomputing from the full list each time carries no double-counting risk.
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
     * funny decision's library hash-index row, or a review category's note line.
     * A NearDupReject has no write of its own beyond the move. Its landed path is taken here all
     * the same, because the note its group keeps is written once from every member's landed name.
     * A prior run's rejects are named in that note too.
     *
     * @param decision {@link Decision} the already-verified-done decision
     * @param record {@link MoveRecord} the verified move record proving it ran
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     */
    private void backfillSecondaryWrite(final Decision decision, final MoveRecord record,
                                        final ApplyOutcome outcome) {
        if (decision instanceof final Classification c) {
            this.backfillClassificationWrite(c, record);
        }
        if (decision instanceof final NearDupReject reject) {
            outcome.landedRejects.put(reject.file(), record.dest());
        }
    }

    /**
     * Backfills a classification's secondary write: a funny hash-index row, or a review reason line.
     *
     * @param c {@link Classification} the classification decision
     * @param record {@link MoveRecord} the verified move record
     */
    private void backfillClassificationWrite(final Classification c, final MoveRecord record) {
        if (c.category().equals(SiftDestinations.FUNNY_CATEGORY)) {
            this.siftDestinations.requireUnderLibrary(record.dest());
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
            this.noteUnlessListed(c.file(), this.siftDestinations.destinationDirFor(c), record.dest(), c.reason());
        }
    }

    /**
     * Backfills the note line for an unreviewable file an earlier run already moved.
     *
     * <p>The move is the only write it makes beyond this one, and that is what the verified record
     * proves. So a note line is all a resumed run can still owe such a file.
     *
     * @param done {@link FileStatus.Done} the already-moved file and the record proving it
     */
    private void backfillUnreviewableNote(final FileStatus.Done done) {
        this.noteUnlessListed(done.file(), this.siftDestinations.unreviewableDir(done.file()),
                done.record().dest(), REASON_UNREVIEWABLE);
    }

    /**
     * Appends a photo's note line unless the folder's note already names it.
     *
     * @param from {@link Path} where the file was, under Sorted
     * @param destDir {@link Path} the folder it landed in
     * @param landed {@link Path} the path it landed at
     * @param reason {@link String} why it is here, as the note says it to a reader
     */
    private void noteUnlessListed(final Path from, final Path destDir, final Path landed, final String reason) {
        final List<String> lines;
        try {
            lines = this.mediaStore.readLines(destDir.resolve(ReasonNotes.FILE_NAME));
        } catch (final UncheckedIOException e) {
            if (MediaReader.mustBeRethrown(e)) {
                throw e;
            }
            log.warn("The note in {} is not text, so {} is left out of it", destDir, landed.getFileName(), e);
            return;
        }
        if (!ReasonNotes.lists(lines, landed.getFileName().toString())) {
            this.note(from, destDir, landed, reason);
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
     * @param nearDupAnchors a {@link Map} of {@link String} to {@link Path} each near-dup group's keeper file, by
     * group id
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     * @param cancellation {@link CancellationSignal} asked while a file's bytes are moving
     * @param transferProgress {@link TransferProgress} told how far this decision's file has got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void apply(final Decision decision, final Path prepDirPath,
                       final Map<String, Path> nearDupAnchors, final ApplyOutcome outcome,
                       final CancellationSignal cancellation, final TransferProgress transferProgress) {
        switch (decision) {
            case final Classification c -> this.applyClassification(c, prepDirPath, outcome, cancellation, transferProgress);
            case final NearDupChosen c -> this.applyNearDupChosen(c, outcome, cancellation, transferProgress);
            case final NearDupReject reject -> this.applyNearDupReject(reject, nearDupAnchors.get(reject.group()),
                    prepDirPath, outcome, cancellation, transferProgress);
        }
    }

    /**
     * A funny classification is kept, so it is hashed into the library index and gets no reason
     * note. Every other category, junk included, gets a note line alongside its move.
     *
     * <p>The index append happens immediately, not batched after the loop. A decision an earlier,
     * crashed run already carried out is skipped on resume (backfillSecondaryWrite() handles it
     * instead), so it never reaches this method again. A batched append collected only from this
     * run's own outcome would then permanently lose that file's index row.
     *
     * @param c {@link Classification} the classification decision
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param transferProgress {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void applyClassification(final Classification c, final Path prepDirPath, final ApplyOutcome outcome,
                                     final CancellationSignal cancellation, final TransferProgress transferProgress) {
        final Path destDir = this.siftDestinations.destinationDirFor(c);
        final MoveOutcome moved = this.recordThenMove(c.file(), destDir, prepDirPath, cancellation, transferProgress);
        outcome.byCategory.merge(c.category(), 1, Integer::sum);
        if (c.category().equals(SiftDestinations.FUNNY_CATEGORY)) {
            this.hashIndexPort.append(List.of(new IndexEntry(moved.hash(), moved.dest())));
        } else {
            this.note(c.file(), destDir, moved.dest(), c.reason());
        }
    }

    /**
     * Appends one photo's line to the note in the folder it just landed in.
     *
     * <p>The line names the file by where it landed rather than where it came from. A name already
     * taken in the destination lands the file as a " (2)".
     *
     * @param from {@link Path} where the file was, under Sorted
     * @param destDir {@link Path} the folder it landed in
     * @param landed {@link Path} the path it landed at
     * @param reason {@link String} why it is here, as the note says it to a reader
     */
    private void note(final Path from, final Path destDir, final Path landed, final String reason) {
        this.mediaStore.appendLine(destDir.resolve(ReasonNotes.FILE_NAME),
                this.noteLine(from, landed.getFileName().toString(), reason));
    }

    /**
     * One line of a note, dated by the month the photo sat under in Sorted where that is known.
     *
     * @param filedUnder {@link Path} the photo's path under Sorted, which carries the month
     * @param name {@link String} the file name the line calls it by
     * @param reason {@link String} why it is here, as the note says it to a reader
     * @return {@link String} the line
     */
    private String noteLine(final Path filedUnder, final String name, final String reason) {
        return this.siftDestinations.monthFiledUnder(filedUnder)
                .map(month -> ReasonNotes.line(name, month, reason))
                .orElseGet(() -> ReasonNotes.line(name, reason));
    }

    /**
     * Writes each near-copy group's note, once every member of it has reached its destination.
     *
     * <p>After the decision loop rather than beside the copy. A reject's line has to carry the name
     * it landed under, and that name is not known until it moves. Written wholesale rather than
     * appended, so a resumed run converges on the same file however far a prior attempt got.
     *
     * <p>Only a group whose keeper this run copied. A group nobody reached has no folder to write
     * into, and a run the caller stopped never arrives here at all.
     *
     * @param groups a {@link Map} of {@link String} to a {@link List} of {@link Decision}, every
     *     near-dup decision by group id
     * @param outcome {@link ApplyOutcome} the run's outcome, holding where each reject landed
     */
    private void writeNearDupNotes(final Map<String, List<Decision>> groups, final ApplyOutcome outcome) {
        for (final String group : outcome.nearDupGroupsChosen) {
            final List<Decision> members = groups.getOrDefault(group, List.of());
            members.stream()
                    .filter(NearDupChosen.class::isInstance)
                    .map(NearDupChosen.class::cast)
                    .forEach(chosen -> this.mediaStore.write(
                            this.siftDestinations.duplicatesDir(chosen.file(), group)
                                    .resolve(chosen.file().getFileName() + ReasonNotes.SUFFIX),
                            this.chosenNote(chosen, members, outcome)));
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
     * decision would produce already exists. That makes re-running safe regardless of how far a
     * prior attempt got.
     *
     * @param c {@link NearDupChosen} the chosen near-dup decision
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are copying
     * @param transferProgress {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the copy finished
     */
    private void applyNearDupChosen(final NearDupChosen c, final ApplyOutcome outcome,
                                    final CancellationSignal cancellation, final TransferProgress transferProgress) {
        // A copy rather than a move, so it takes the source check directly. Every other decision
        // type picks it up from recordThenMove.
        this.siftDestinations.requireUnderSorted(c.file());
        final Path dupDir = this.siftDestinations.duplicatesDir(c.file(), c.group());
        final Path dest = dupDir.resolve(c.file().getFileName().toString());
        if (!this.mediaStore.exists(dest)) {
            this.mediaStore.copy(c.file(), dupDir, cancellation, transferProgress);
        }
        outcome.nearDupGroupsChosen.add(c.group());
    }

    /**
     * Moves a rejected near-dup file into its duplicates group folder. The folder is resolved from
     * the group's chosen keeper, not from reject's own file - see
     * {@link SiftDestinations#duplicatesDir}.
     *
     * @param reject {@link NearDupReject} the rejected near-dup decision
     * @param groupAnchor {@link Path} the group's chosen keeper file
     * @param prepDirPath {@link Path} the prep directory whose ledger records the move
     * @param outcome {@link ApplyOutcome} the run's accumulating outcome
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param transferProgress {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void applyNearDupReject(final NearDupReject reject, final Path groupAnchor, final Path prepDirPath,
                                    final ApplyOutcome outcome, final CancellationSignal cancellation,
                                    final TransferProgress transferProgress) {
        final MoveOutcome moved = this.recordThenMove(reject.file(),
                this.siftDestinations.duplicatesDir(groupAnchor, reject.group()), prepDirPath,
                cancellation, transferProgress);
        outcome.landedRejects.put(reject.file(), moved.dest());
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
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param transferProgress {@link TransferProgress} told how far this file's bytes have got
     * @return {@link MoveOutcome} the resolved destination and source hash
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private MoveOutcome recordThenMove(final Path source, final Path destDir, final Path prepDirPath,
                                       final CancellationSignal cancellation,
                                       final TransferProgress transferProgress) {
        this.siftDestinations.requireUnderSorted(source);
        final Path dest = this.mediaStore.resolveDestination(source, destDir);
        final String hash = this.sha256Port.hash(source);
        this.moveLedger.recordMove(prepDirPath, source, dest, hash);
        this.mediaStore.moveTo(source, dest, cancellation, transferProgress);
        return new MoveOutcome(dest, hash);
    }

    /**
     * Builds the note text recording which file was kept and why, plus its rejects.
     *
     * <p>One photo per line, the kept one first, every line the same shape: the name, the month it
     * sat under in Sorted, then why it is here.
     *
     * <p>The month matters on the kept photo's own line as much as on a reject's. Its copy here is
     * deleted rather than moved only where a rescue resolves the destination its original is at.
     * For a photo nothing else can date, this line is the only thing that resolves it.
     *
     * <p>The folder's own name cannot stand in for any of them. It is built from the keeper's month,
     * and a group's members can sit in different ones.
     *
     * @param chosen {@link NearDupChosen} the chosen near-dup decision
     * @param group a {@link List} of {@link Decision} all decisions in this near-dup group
     * @param outcome {@link ApplyOutcome} the run's outcome, holding where each reject landed
     * @return {@link String} the note's text
     */
    private String chosenNote(final NearDupChosen chosen, final List<Decision> group,
                              final ApplyOutcome outcome) {
        return Stream.concat(
                Stream.of(this.keptLine(chosen)),
                group.stream()
                        .filter(NearDupReject.class::isInstance)
                        .map(NearDupReject.class::cast)
                        .map(reject -> this.rejectLine(reject, outcome)))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * The kept photo's line in the note its group keeps.
     *
     * <p>Named by where it came from, unlike a reject's. It is copied rather than moved, so the copy
     * lands under its own name or not at all.
     *
     * @param chosen {@link NearDupChosen} the chosen near-dup decision
     * @return {@link String} the line
     */
    private String keptLine(final NearDupChosen chosen) {
        final String name = chosen.file().getFileName().toString();
        final String reason = "kept, " + chosen.chosenReason();
        return this.noteLine(chosen.file(), name, reason);
    }

    /**
     * One rejected near-copy's line in the note its group keeps.
     *
     * <p>Named by where it landed. A reject the caller gave up on never moved, so it keeps the name
     * it has in Sorted, which is the only name it answers to.
     *
     * @param reject {@link NearDupReject} the rejected near-dup decision
     * @param outcome {@link ApplyOutcome} the run's outcome, holding where each reject landed
     * @return {@link String} the line
     */
    private String rejectLine(final NearDupReject reject, final ApplyOutcome outcome) {
        final String name = outcome.landedRejects.getOrDefault(reject.file(), reject.file())
                .getFileName().toString();
        return this.noteLine(reject.file(), name, reject.reason());
    }

    /**
     * Drops the montage contact sheets and tile images once every decision has been carried out.
     * Always, even when zero decisions exist. index.json, the per-montage sidecars and shards, the
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
        // Where each near-copy reject ended up, by the file it came from. A name already taken in
        // the group's folder lands the file as a " (2)".
        final Map<Path, Path> landedRejects = new HashMap<>();
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
