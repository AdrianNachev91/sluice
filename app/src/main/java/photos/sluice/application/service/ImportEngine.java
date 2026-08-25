package photos.sluice.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.ImportSourceException;
import photos.sluice.application.port.out.MediaReader;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.application.port.out.PathsPort;
import photos.sluice.application.port.out.Sha256Port;
import photos.sluice.domain.imports.ImportKind;
import photos.sluice.domain.imports.ImportSummary;
import photos.sluice.domain.job.CancellationSignal;
import photos.sluice.domain.job.ProgressCallback;
import photos.sluice.domain.paths.Containment;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Brings photos into the Inbox from folders and files chosen outside it.
 *
 * <p>Each file is copied under a {@code .sluice-part} name and renamed into place. An interrupted
 * import therefore leaves whole files, never a truncated one wearing a real name. Abandoned part
 * files are never swept.
 *
 * <p>A file the Inbox already holds is passed over only where the bytes match, whichever kind of
 * import it is. A move then deletes the original, having just proved it survives.
 *
 * <p>There is no ledger: a file still in the source folder was never imported, so running the import
 * again is the resume.
 */
@Component
public class ImportEngine {

    private static final Logger log = LoggerFactory.getLogger(ImportEngine.class);

    // An extension no media filter matches, so a sort walking the Inbox passes over one of these.
    private static final String PART_SUFFIX = ".sluice-part";

    private final MediaStore mediaStore;
    private final PathsPort paths;
    private final Sha256Port hasher;

    /**
     * Creates the engine over what it writes through and where it writes to.
     *
     * @param mediaStore {@link MediaStore}
     * @param paths {@link PathsPort}
     * @param hasher {@link Sha256Port}
     */
    public ImportEngine(final MediaStore mediaStore, final PathsPort paths, final Sha256Port hasher) {
        this.mediaStore = mediaStore;
        this.paths = paths;
        this.hasher = hasher;
    }

    /**
     * Refuses a set of sources this could not be run over.
     *
     * <p>Apart from the import so a caller can refuse before a job exists.
     *
     * <p>A folder holding no files is not one of them. It runs, and brings nothing in.
     *
     * @param sources a {@link List} of {@link Path}
     * @throws ImportSourceException where the set is empty, or a source is gone or meets the Inbox
     */
    public void requireImportable(final List<Path> sources) {
        if (sources.isEmpty()) {
            throw new ImportSourceException("Sluice was asked to import nothing at all.");
        }
        final Path inbox = this.paths.inbox();
        for (final Path source : sources) {
            if (!this.mediaStore.exists(source)) {
                throw new ImportSourceException("Sluice could not find " + source
                        + " any more. Check it is still there, and try again.");
            }
            requireOutsideTheInbox(source, inbox);
        }
    }

    /**
     * Brings every file the chosen sources hold into the Inbox, keeping each folder's shape.
     *
     * @param sources a {@link List} of {@link Path}
     * @param kind {@link ImportKind} whether the originals stay where they are
     * @param progress {@link ProgressCallback} ticked once per file
     * @param cancelled {@link CancellationSignal} asked before each file
     * @return {@link ImportSummary}
     */
    public ImportSummary importFrom(final List<Path> sources, final ImportKind kind,
                                    final ProgressCallback progress,
                                    final CancellationSignal cancelled) {
        // Taken in full before anything is written, so the set being walked cannot grow as it is
        // walked.
        final Gathered gathered = this.arrivals(sources);
        final int total = gathered.arrivals().size();
        var counts = new Counts(0, 0, 0, 0);
        int seen = 0;
        for (final Arrival arrival : gathered.arrivals()) {
            if (cancelled.isCancelled()) {
                return counts.summary(total, gathered.unreadablePlaces(), true);
            }
            counts = this.bringInTolerating(arrival, kind, counts);
            progress.tick(++seen, total);
        }
        return counts.summary(total, gathered.unreadablePlaces(), false);
    }

    /**
     * Brings one file in, and carries on where the filesystem refuses.
     *
     * <p>With no ledger, an unreadable photo that ended the run would end every later one at the
     * same file, so the import could never finish. Counted and stepped over instead.
     *
     * @param arrival {@link Arrival}
     * @param kind {@link ImportKind} whether the original stays where it is
     * @param counts {@link Counts}
     * @return {@link Counts} the same, with this file counted
     */
    private Counts bringInTolerating(final Arrival arrival, final ImportKind kind,
                                     final Counts counts) {
        try {
            return this.bringIn(arrival, kind, counts);
        } catch (final UncheckedIOException e) {
            // Only the filesystem refusing. Anything else is a fault here, and still ends the run.
            log.warn("Could not bring in {}", arrival.file(), e);
            return counts.oneMoreCouldNotBeRead();
        }
    }

    /**
     * Every file the chosen sources hold, each paired with where in the Inbox it belongs.
     *
     * @param sources a {@link List} of {@link Path}
     * @return {@link Gathered}
     */
    private Gathered arrivals(final List<Path> sources) {
        final Path inbox = this.paths.inbox();
        final List<Arrival> arrivalsSoFar = new ArrayList<>();
        int unreadablePlaces = 0;
        // The sources are a list and may overlap. A folder and a file inside it name that file
        // twice, and a move would then delete an original twice.
        final Set<Path> alreadyGathered = new HashSet<>();
        for (final Path source : sources) {
            // Both calls below throw where the filesystem stops answering, which is what pulling
            // a card mid-import looks like. Everything above only proved it was there a moment ago.
            try {
                final Optional<Path> resolvedFolder = this.mediaStore.realDirectory(source);
                if (resolvedFolder.isPresent()) {
                    // Resolved, and used for both the walk and each file's place under it, so a
                    // name reaching the folder through a link cannot make the two disagree.
                    final Path walkedRoot = resolvedFolder.get();
                    // Tolerant, because a Windows-formatted card carries a directory nobody may
                    // read, and throwing on it would make importing a whole card impossible.
                    final MediaReader.Walk walk = this.mediaStore.listFilesTolerating(walkedRoot);
                    unreadablePlaces += walk.unreadablePlaces().size();
                    for (final Path file : walk.files()) {
                        addOnce(arrivalsSoFar, alreadyGathered, under(inbox, walkedRoot, file));
                    }
                } else {
                    // Resolved for the same reason the folder above is. A walked file arrives
                    // spelled the way its real root spells it. Resolved here too, the two
                    // spellings meet, and the de-duplication below can see they are one file.
                    addOnce(arrivalsSoFar, alreadyGathered,
                            new Arrival(this.mediaStore.realFile(source), inbox));
                }
            } catch (final UncheckedIOException e) {
                throw new ImportSourceException("Sluice could not read " + source + " any more. If "
                        + "it is a card or a drive, check it is still plugged in.", e);
            }
        }
        return new Gathered(arrivalsSoFar, unreadablePlaces);
    }

    /**
     * Adds one arrival unless an earlier source already named that file.
     *
     * @param arrivalsSoFar a {@link List} of {@link Arrival}
     * @param alreadyGathered a {@link Set} of {@link Path}
     * @param arrival {@link Arrival}
     */
    private static void addOnce(final List<Arrival> arrivalsSoFar, final Set<Path> alreadyGathered,
                                final Arrival arrival) {
        if (alreadyGathered.add(arrival.file().toAbsolutePath().normalize())) {
            arrivalsSoFar.add(arrival);
        }
    }

    /**
     * One file's place in the Inbox, matching where it sat under the folder it came from.
     *
     * @param inbox {@link Path}
     * @param source {@link Path} the chosen folder the file was found under
     * @param file {@link Path}
     * @return {@link Arrival}
     */
    private static Arrival under(final Path inbox, final Path source, final Path file) {
        final Path relative = source.relativize(file).getParent();
        return new Arrival(file, relative == null ? inbox : inbox.resolve(relative));
    }

    /**
     * Brings one file in, and answers what that came to.
     *
     * @param arrival {@link Arrival}
     * @param kind {@link ImportKind} whether the original stays where it is
     * @param counts {@link Counts}
     * @return {@link Counts} the same, with this file counted
     */
    private Counts bringIn(final Arrival arrival, final ImportKind kind, final Counts counts) {
        if (this.alreadySameFile(arrival)) {
            if (kind == ImportKind.MOVE) {
                // On the strength of the byte comparison alreadySameFile just made.
                this.mediaStore.delete(arrival.file());
            }
            return counts.oneMoreAlreadyThere();
        }
        return this.copyItIn(arrival, kind, counts);
    }

    /**
     * Copies one file into the Inbox under a temporary name, then renames it into place.
     *
     * <p>A move that cannot prove what arrived leaves both sides as they were, and counts the file
     * unverified.
     *
     * @param arrival {@link Arrival}
     * @param kind {@link ImportKind} whether the original stays where it is
     * @param counts {@link Counts}
     * @return {@link Counts} the same, with this file counted
     */
    private Counts copyItIn(final Arrival arrival, final ImportKind kind, final Counts counts) {
        final Path landing = this.mediaStore.resolveDestination(arrival.file(), arrival.destination());
        // A free name, not a composed one. An earlier crash between copy and rename leaves a whole
        // photo under that name, and its card may be gone. Writing over it can lose the only copy.
        final Path part = this.mediaStore.resolveDestination(Path.of(landing + PART_SUFFIX),
                arrival.destination());
        this.mediaStore.copyTo(arrival.file(), part);
        if (kind == ImportKind.MOVE && !this.sameBytes(arrival.file(), part)) {
            this.mediaStore.delete(part);
            return counts.oneMoreUnverified();
        }
        this.mediaStore.moveTo(part, landing);
        if (kind == ImportKind.MOVE) {
            this.mediaStore.delete(arrival.file());
        }
        return counts.oneMoreBroughtIn();
    }

    /**
     * Whether this exact file is already sitting where it would land.
     *
     * <p>Name and size decide nothing here. They are a cheap negative, skipping a hash that could
     * not have matched. Two photos can share both, and passing over one on that alone would drop a
     * photo the user asked for.
     *
     * @param arrival {@link Arrival}
     * @return boolean
     */
    private boolean alreadySameFile(final Arrival arrival) {
        final Path there = whereItWouldSit(arrival);
        return this.mediaStore.exists(there)
                && this.mediaStore.size(there) == this.mediaStore.size(arrival.file())
                && this.sameBytes(arrival.file(), there);
    }

    /**
     * The path this file would take in the Inbox if nothing were already there.
     *
     * @param arrival {@link Arrival}
     * @return {@link Path}
     */
    private static Path whereItWouldSit(final Arrival arrival) {
        return arrival.destination().resolve(arrival.file().getFileName());
    }

    /**
     * Whether two files hold the same bytes.
     *
     * @param one {@link Path}
     * @param other {@link Path}
     * @return boolean
     */
    private boolean sameBytes(final Path one, final Path other) {
        return this.hasher.hash(one).equals(this.hasher.hash(other));
    }

    /**
     * Refuses a source the Inbox cannot be filled from.
     *
     * <p>A source inside the Inbox holds what is already there. A source holding the Inbox would be
     * read into a place inside itself.
     *
     * @param source {@link Path}
     * @param inbox {@link Path}
     * @throws ImportSourceException where the two meet
     */
    private static void requireOutsideTheInbox(final Path source, final Path inbox) {
        final Path from = source.toAbsolutePath().normalize();
        final Path to = inbox.toAbsolutePath().normalize();
        if (from.equals(to) || Containment.strictlyUnder(to, from)) {
            throw new ImportSourceException("Everything in " + from + " is already in your Inbox, "
                    + "so there is nothing to bring in.");
        }
        if (Containment.strictlyUnder(from, to)) {
            throw new ImportSourceException("Sluice cannot import from " + from
                    + ", because your Inbox is inside it.");
        }
    }

    /**
     * One file and the directory in the Inbox it belongs in.
     *
     * @param file {@link Path}
     * @param destination {@link Path} the directory under the Inbox it lands in
     */
    private record Arrival(Path file, Path destination) {
    }

    /**
     * What the walk over the chosen sources came to.
     *
     * @param arrivals a {@link List} of {@link Arrival} every file it reached
     * @param unreadablePlaces int how many folders it could not look inside at all
     */
    private record Gathered(List<Arrival> arrivals, int unreadablePlaces) {
    }

    /**
     * What an import has come to so far.
     *
     * @param broughtIn int how many landed in the Inbox on this run
     * @param alreadyThere int how many the Inbox already held
     * @param unverified int how many arrived with bytes that did not match the original
     * @param couldNotBeRead int how many the filesystem refused partway through
     */
    private record Counts(int broughtIn, int alreadyThere, int unverified, int couldNotBeRead) {

        /**
         * The same counts, with one more file brought in.
         *
         * @return {@link Counts}
         */
        private Counts oneMoreBroughtIn() {
            return new Counts(this.broughtIn + 1, this.alreadyThere, this.unverified, this.couldNotBeRead);
        }

        /**
         * The same counts, with one more file the Inbox already held.
         *
         * @return {@link Counts}
         */
        private Counts oneMoreAlreadyThere() {
            return new Counts(this.broughtIn, this.alreadyThere + 1, this.unverified, this.couldNotBeRead);
        }

        /**
         * The same counts, with one more file that could not be proved to have arrived intact.
         *
         * @return {@link Counts}
         */
        private Counts oneMoreUnverified() {
            return new Counts(this.broughtIn, this.alreadyThere, this.unverified + 1, this.couldNotBeRead);
        }

        /**
         * The same counts, with one more file the filesystem would not let it read.
         *
         * @return {@link Counts}
         */
        private Counts oneMoreCouldNotBeRead() {
            return new Counts(this.broughtIn, this.alreadyThere, this.unverified,
                    this.couldNotBeRead + 1);
        }

        /**
         * These counts as the summary an import answers with.
         *
         * @param found int how many files the import walked
         * @param unreadablePlaces int how many folders it could not look inside
         * @param cancelled boolean whether it stopped before reaching every one of them
         * @return {@link ImportSummary}
         */
        private ImportSummary summary(final int found, final int unreadablePlaces,
                                      final boolean cancelled) {
            return new ImportSummary(found, this.broughtIn, this.alreadyThere, this.unverified,
                    this.couldNotBeRead, unreadablePlaces, cancelled);
        }
    }
}
