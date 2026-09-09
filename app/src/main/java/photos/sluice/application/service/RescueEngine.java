package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.NoteIsNotTextException;
import photos.sluice.application.port.in.RescueRoot;
import photos.sluice.application.port.in.RescueUseCase;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.application.port.out.TransferAbandonedException;
import photos.sluice.application.port.out.TransferProgress;
import photos.sluice.domain.dating.RescueDateResolver;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.model.MediaFile;
import photos.sluice.domain.model.MediaType;
import photos.sluice.domain.paths.RelativePaths;
import photos.sluice.domain.paths.SortFolderNames;
import photos.sluice.domain.rescue.RescueSummary;
import photos.sluice.domain.review.ReasonNotes;
import photos.sluice.domain.scan.MediaTypeDetector;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Moves every media file still present under one waiting folder back into Sorted, dated via
 * {@link RescueDateResolver} off the folder's own notes. The folder is then dissolved, if nothing
 * was left behind.
 *
 * <p>It writes no library hash index row, and must not. A Sorted path in that index would make the
 * next sort read an Inbox original as a redundant re-import, and delete it. That is one of the
 * deletions the safety invariant allows, fired on a false premise.
 *
 * <p>A file nothing can date is moved too, into {@link SortFolderNames#UNDATED} directly under
 * Sorted.
 *
 * <p>A file whose own bytes are already at its destination is deleted rather than moved, which is
 * another of those allowed deletions.
 */
@Component
public class RescueEngine implements RescueUseCase {

    private final PathsPort pathsPort;
    private final MediaStore mediaStore;
    private final RescueDateResolver rescueDateResolver;
    private final Sha256Port sha256Port;
    private final MediaTypeDetector mediaTypeDetector = new MediaTypeDetector();

    /**
     * Creates a rescue engine backed by the given ports.
     *
     * @param pathsPort {@link PathsPort} resolves the roots a rescue reads and the Sorted root it writes
     * @param mediaStore {@link MediaStore} file operations on the files being moved
     * @param rescueDateResolver {@link RescueDateResolver} resolves a date per file
     * @param sha256Port {@link Sha256Port} settles whether a file is already at its destination
     */
    public RescueEngine(final PathsPort pathsPort, final MediaStore mediaStore,
                        final RescueDateResolver rescueDateResolver, final Sha256Port sha256Port) {
        this.pathsPort = pathsPort;
        this.mediaStore = mediaStore;
        this.rescueDateResolver = rescueDateResolver;
        this.sha256Port = sha256Port;
    }

    /**
     * Rescues a folder using no-op progress and cancellation.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder's path below that root
     * @return {@link RescueSummary} summary of what the run moved
     */
    @Override
    public RescueSummary rescue(final RescueRoot root, final String folder) {
        return this.rescue(root, folder, ProgressCallback.NO_OP, CancellationSignal.NEVER);
    }

    /**
     * Rescues a folder, reporting progress, never cancellable.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder's path below that root
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @return {@link RescueSummary} summary of what the run moved
     */
    public RescueSummary rescue(final RescueRoot root, final String folder, final ProgressCallback progress) {
        return this.rescue(root, folder, progress, CancellationSignal.NEVER);
    }

    /**
     * Moves every recognized media file still in the named folder into Sorted, then dissolves the
     * folder if the whole pass completed.
     *
     * @param root {@link RescueRoot} which root the folder sits under
     * @param folder {@link String} the folder's path below that root
     * @param progress {@link ProgressCallback} progress callback ticked per file
     * @param cancellation {@link CancellationSignal} checked between files to allow early stop
     * @return {@link RescueSummary} what moved, and whether the folder was removed
     * @throws IllegalArgumentException if folder resolves outside the named root
     */
    public RescueSummary rescue(final RescueRoot root, final String folder, final ProgressCallback progress,
                                final CancellationSignal cancellation) {
        final Path rootPath = this.pathOf(root);
        final Path target = resolveWithin(rootPath, folder);
        final Path sortedRoot = this.pathsPort.sorted();

        // Snapshotted once, before any move happens, and reused below to find the notes left
        // behind. The loop below only ever relocates recognized media files, never a note, so this
        // list's note entries are still accurate afterward.
        final List<Path> allFiles = this.mediaStore.listFiles(target).stream()
                .filter(file -> !MediaStore.isIncompleteTransfer(file))
                .toList();
        final int total = allFiles.size();
        int current = 0;
        final var outcome = new RescueOutcome();
        final Map<Path, Map<String, LocalDate>> noted = new HashMap<>();
        // Sorted first, so each folder's notes are read in the order the review screen's own fold
        // reads them. Two readings of one folder that disagreed about which line came last would
        // disagree about the photo it names.
        final Map<Path, List<Path>> notesByFolder = allFiles.stream()
                .filter(file -> file.getFileName().toString().endsWith(ReasonNotes.SUFFIX))
                .sorted()
                .collect(Collectors.groupingBy(Path::getParent));
        try {
            // Checked after each file, so an in-flight file is never interrupted; already-moved
            // files stay moved, matching the no-undo model.
            while (current < total && !cancellation.isCancelled()) {
                this.rescueOneFile(allFiles.get(current), rootPath, sortedRoot, outcome, noted,
                        notesByFolder, cancellation, TransferProgress.within(progress, current, total));
                progress.tick(++current, total);
            }
        } catch (final TransferAbandonedException e) {
            // The abandoned file is still in the folder. It also leaves current short of total, so
            // the all-or-nothing check below reads a pass that did not finish, and the folder is
            // not dissolved under it.
        }

        // All-or-nothing per folder: dissolving it, and the notes inside it, only happens once the
        // pass reached every file. A cancelled run would otherwise delete the notes while unvisited
        // media still sat in the folder.
        final boolean ranToCompletion = current == total;
        boolean folderRemoved = false;
        if (ranToCompletion) {
            allFiles.stream()
                    .filter(file -> ReasonNotes.isANote(file.getFileName().toString()))
                    .forEach(this.mediaStore::delete);
            this.mediaStore.removeIfEmptyOfFiles(target);
            folderRemoved = !this.mediaStore.exists(target);
        }

        return new RescueSummary(outcome.rescued, outcome.undated, outcome.alreadyThere,
                this.mediaAmong(allFiles.subList(current, total)), folderRemoved, !ranToCompletion);
    }

    /**
     * How many of these files a rescue would have moved.
     *
     * <p>An abandoned file is among them. Its bytes never landed, so it is still where it was, and
     * {@code current} was never advanced past it.
     *
     * @param files a {@link List} of {@link Path} the files the pass never reached
     * @return int how many of them are media
     */
    private int mediaAmong(final List<Path> files) {
        return (int) files.stream()
                .filter(file -> this.mediaTypeDetector.classify(file).isPresent())
                .count();
    }

    /**
     * Which tree on disk a root names.
     *
     * @param root {@link RescueRoot} the root asked for
     * @return {@link Path} where it is
     */
    private Path pathOf(final RescueRoot root) {
        return switch (root) {
            case REVIEW -> this.pathsPort.review();
            case UNREVIEWABLE -> this.pathsPort.unreviewable();
            case DUPLICATES -> this.pathsPort.duplicates();
        };
    }

    /**
     * Moves one file into Sorted, if it is media at all. A non-media file, a stray
     * {@code _reasons.txt} or anything else left in the folder, is ignored outright.
     *
     * @param file {@link Path} candidate file from the folder
     * @param rootPath {@link Path} the root being rescued from, which the file's date is read against
     * @param sortedRoot {@link Path} the Sorted root to move into
     * @param outcome {@link RescueOutcome} accumulator for what moved where
     * @param noted a {@link Map} of {@link Path} to a {@link Map} of {@link String} to
     *     {@link LocalDate}, each folder's notes read once and kept
     * @param notesByFolder a {@link Map} of {@link Path} to a {@link List} of {@link Path}, the
     *     notes each folder held before anything moved
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param watching {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void rescueOneFile(final Path file, final Path rootPath, final Path sortedRoot,
                               final RescueOutcome outcome, final Map<Path, Map<String, LocalDate>> noted,
                               final Map<Path, List<Path>> notesByFolder,
                               final CancellationSignal cancellation, final TransferProgress watching) {
        final Optional<MediaType> type = this.mediaTypeDetector.classify(file);
        if (type.isEmpty()) {
            return;
        }
        final String within = RelativePaths.slashed(rootPath.relativize(file));
        final LocalDate wasTaken = this.notesBeside(file, noted, notesByFolder)
                .get(file.getFileName().toString());
        final Optional<LocalDateTime> date = this.rescueDateResolver.resolve(new MediaFile(file), within,
                wasTaken == null ? null : wasTaken.atStartOfDay());
        if (date.isEmpty()) {
            this.moveOrDropAsAlreadyThere(file, sortedRoot.resolve(SortFolderNames.UNDATED), false,
                    outcome, cancellation, watching);
            return;
        }
        final Path destDir = sortedRoot.resolve(type.get() == MediaType.VIDEO ? "Videos" : "Photos")
                .resolve(SortFolderNames.yearFolder(date.get()))
                .resolve(SortFolderNames.monthFolder(date.get()));
        this.moveOrDropAsAlreadyThere(file, destDir, true, outcome, cancellation, watching);
    }

    /**
     * Moves a file into Sorted, unless the exact file is already sitting there.
     *
     * <p>A near-copy group holds a byte copy of the photo it kept, whose original never left
     * Sorted. Moving that copy back would land it beside its own original as a " (2)". That is a
     * duplicate this app made and handed to the reader to sort out, so it is deleted instead.
     *
     * <p>The one deletion here that the safety invariant allows, and it is allowed because the
     * bytes are found at the destination first.
     *
     * @param file {@link Path} the file to move
     * @param destDir {@link Path} where it belongs in Sorted
     * @param dated boolean true where something dated it, so it lands under a year and month
     * @param outcome {@link RescueOutcome} accumulator for what moved where
     * @param cancellation {@link CancellationSignal} asked while the file's bytes are moving
     * @param watching {@link TransferProgress} told how far this file's bytes have got
     * @throws TransferAbandonedException if cancellation escalated before the file landed
     */
    private void moveOrDropAsAlreadyThere(final Path file, final Path destDir, final boolean dated,
                                          final RescueOutcome outcome, final CancellationSignal cancellation,
                                          final TransferProgress watching) {
        if (this.isAlreadyAt(destDir.resolve(file.getFileName()), file)) {
            this.mediaStore.delete(file);
            outcome.alreadyThere++;
            return;
        }
        this.mediaStore.move(file, destDir, cancellation, watching);
        if (dated) {
            outcome.rescued++;
        } else {
            outcome.undated++;
        }
    }

    /**
     * Whether the destination already holds this exact file.
     *
     * @param dest {@link Path} where the file would land, under its own name
     * @param file {@link Path} the file about to move
     * @return boolean true where the bytes at dest are this file's
     */
    private boolean isAlreadyAt(final Path dest, final Path file) {
        return this.mediaStore.exists(dest)
                && this.mediaStore.size(dest) == this.mediaStore.size(file)
                && this.sha256Port.hash(dest).equals(this.sha256Port.hash(file));
    }

    /**
     * What the notes in a file's own folder say each photo there was taken, read once per folder.
     *
     * <p>Every {@code .txt} in the folder. A near-copy group's note is named for the photo it kept
     * rather than {@link ReasonNotes#FILE_NAME}, so taking only that one name would leave every
     * reject in the folder undated.
     *
     * <p>A note is text a reader is invited to edit. A line naming no photo, or carrying a date
     * that does not parse, costs that photo its date and nothing else.
     *
     * <p>A note that cannot be read at all stops the rescue, whatever the reason. That includes
     * bytes that are not text, which {@link MediaReader#readLines} does allow a caller to carry on
     * from.
     *
     * @param file {@link Path} a file about to be rescued
     * @param noted a {@link Map} of {@link Path} to a {@link Map} of {@link String} to
     *     {@link LocalDate}, every folder read so far
     * @param notesByFolder a {@link Map} of {@link Path} to a {@link List} of {@link Path}, the
     *     notes each folder held before anything moved
     * @return a {@link Map} of {@link String} to {@link LocalDate}, dates by the name each photo
     *     was filed under
     */
    private Map<String, LocalDate> notesBeside(final Path file, final Map<Path, Map<String, LocalDate>> noted,
                                               final Map<Path, List<Path>> notesByFolder) {
        return noted.computeIfAbsent(file.getParent(), folder -> ReasonNotes.datesIn(
                notesByFolder.getOrDefault(folder, List.of()).stream()
                        .flatMap(note -> this.linesOf(note).stream())
                        .toList()));
    }

    /**
     * One note's lines, naming it where its bytes turn out not to be text.
     *
     * <p>{@link MediaReader#readLines} throws an exception carrying no path, and a folder can hold
     * several notes. Without the name, whoever meets the refusal has nothing to open.
     *
     * @param note {@link Path} the note to read
     * @return a {@link List} of {@link String} its lines
     * @throws NoteIsNotTextException if the file holds something other than text
     */
    private List<String> linesOf(final Path note) {
        try {
            return this.mediaStore.readLines(note);
        } catch (final UncheckedIOException e) {
            if (MediaReader.mustStayLoud(e)) {
                throw e;
            }
            throw new NoteIsNotTextException(note, e);
        }
    }

    /**
     * A caller-supplied folder name must never resolve outside its root via a ".." segment.
     * Normalize first, then check containment, rather than string-matching for "..". A legitimately
     * dotted filename could trigger that as a false positive, and a smarter traversal could dodge
     * it.
     *
     * <p>The root itself is refused too. It passes the containment check, and a pass over it would
     * move the whole tree and then delete the configured root directory.
     *
     * @param rootPath {@link Path} the root the folder must stay under
     * @param folder {@link String} caller-supplied folder name to resolve
     * @return {@link Path} normalized path guaranteed to name something inside rootPath
     */
    private static Path resolveWithin(final Path rootPath, final String folder) {
        final Path target = rootPath.resolve(folder).normalize();
        if (!target.startsWith(rootPath) || target.equals(rootPath)) {
            throw new IllegalArgumentException("folder must name something inside its root: " + folder);
        }
        return target;
    }

    /**
     * Accumulates one rescue pass's outcome as it goes.
     */
    private static final class RescueOutcome {
        int rescued;
        int undated;
        int alreadyThere;
    }
}
