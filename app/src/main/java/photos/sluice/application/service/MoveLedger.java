package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.domain.sift.AnswerSource;
import photos.sluice.domain.sift.CorruptSidecarResolution;
import photos.sluice.domain.sift.OverlapResolution;

import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The sole owner of a prep dir's two ledger files, together recording how each of its decisions was
 * finally resolved. It knows both file names, the line format, every disposition marker, how to
 * parse each file back, and how to append each kind of entry.
 *
 * <p>The two files hold evidence of different kinds. {@code move-records.log} holds moves,
 * witnessed or reconstructed. Each one states something disk state itself can confirm or deny. A
 * lost move record is therefore re-derivable by looking at disk again. {@code choices.log} holds
 * user testimony instead. A source the user gave up on, a decision/unreviewable overlap the user
 * resolved, and a montage whose corrupt sidecar the user resolved. No amount of looking at disk can
 * re-derive an answer somebody gave.
 *
 * <p>Every CHOICE remedy lands in the ledger rather than editing a shard or index.json. Producer
 * output stays byte-pristine, so the trail still records what the producer actually said.
 *
 * <p>Naming and parsing live together on purpose. Each file's line shapes are only unambiguous as a
 * closed set, and that reasoning only holds while a single class owns both sides of it.
 *
 * <p>A caller reads once per run via {@link #read} and threads the resulting {@link Ledger}
 * through every consumer that needs it. One snapshot covers both files. That snapshot is what
 * {@link LedgerReader} exposes to a read-only collaborator; only this class can also append to it.
 *
 * <p>Neither file's own damage is fatal to reading the other. A file whose bytes do not decode
 * reads as no entries rather than throwing. A damaged ledger is the exact state the recovery flow
 * exists to diagnose, so it has to be reportable. Only the choices side carries that failure on
 * the snapshot, because only its loss is permanent.
 *
 * <p>Format reference: {@code app/docs/design/application/service/move-ledger.md}.
 */
@Component
public class MoveLedger implements LedgerReader {

    private static final String MOVE_RECORD_LOG = "move-records.log";
    private static final String CHOICES_LOG = "choices.log";

    // The move-record line format's one additive field. A three-field line (no fourth field) means
    // WITNESSED - recorded from the source's own hash right before a real move. This exact marker as
    // the fourth field means RECONSTRUCTED - the record was inferred from disk state alone, the
    // original log having been lost. Both are trusted identically by a classifying caller; the
    // marker is provenance for a human reading the log, not a behavioral distinction.
    private static final String RECONSTRUCTED_MARKER = "RECONSTRUCTED";

    // Which of the three CHOICE remedies a choices.log line records. The marker sits in the second
    // field, so the subject a line is about always comes first regardless of kind.
    private static final String SKIPPED_MARKER = "SKIPPED_BY_USER";
    private static final String OVERLAP_MARKER = "OVERLAP_RESOLVED";
    private static final String CORRUPT_SIDECAR_MARKER = "CORRUPT_SIDECAR_RESOLVED";

    // A control character, not a printable one. A filesystem may technically permit one in a name,
    // so its absence from a path is a practical convention rather than an OS-enforced guarantee. It
    // is vanishingly unlikely to appear in a real path. A record's fields then split back apart
    // with zero escaping and no ambiguity, even when a path itself contains spaces, commas, or
    // tabs. A path that did carry one would change its line's field count, and a line whose shape
    // is not recognized is dropped.
    static final String RECORD_DELIMITER = "\u001F";

    // What a filed-away copy of each file is called in a disaster drawer. Named here rather than at
    // the filing site, so the class owning the file names owns its drawer name too.
    static final String MOVE_RECORD_DRAWER_LABEL = "move-records-log";
    static final String CHOICES_DRAWER_LABEL = "choices-log";

    private final MediaStore mediaStore;
    private final DisasterDrawer disasterDrawer;

    /**
     * Creates a ledger reading and appending through the given store.
     *
     * @param mediaStore {@link MediaStore} reads and appends both ledger files
     * @param disasterDrawer {@link DisasterDrawer} files an undecodable choices file away before a fresh answer
     */
    public MoveLedger(final MediaStore mediaStore, final DisasterDrawer disasterDrawer) {
        this.mediaStore = mediaStore;
        this.disasterDrawer = disasterDrawer;
    }

    /**
     * The choices file's own path inside a prep directory. Exposed so a collaborator handling the
     * file itself, rather than its parsed contents, can name it without restating the file name.
     *
     * @param prepDirPath {@link Path} the prep directory
     * @return {@link Path} the choices file's path
     */
    Path choicesLogFor(final Path prepDirPath) {
        return prepDirPath.resolve(CHOICES_LOG);
    }

    /**
     * Parses both ledger files into their four constituent pieces in one pass. A caller takes one
     * snapshot per run and threads it through every consumer that needs it. See {@link Ledger}'s
     * own Javadoc for the rules that snapshot must follow.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger to read
     * @return {@link Ledger} the parsed ledger, empty in every part if neither file exists yet
     */
    @Override
    public Ledger read(final Path prepDirPath) {
        final var moves = new HashMap<Path, MoveRecord>();
        final var skipped = new HashSet<Path>();
        final var overlaps = new HashMap<Path, OverlapResolution>();
        final var corruptSidecars = new HashMap<String, CorruptSidecarResolution>();
        this.linesOf(this.moveRecordLogFor(prepDirPath)).orElse(List.of())
                .forEach(line -> parseMoveRecord(line, moves));
        final Optional<List<String>> choiceLines = this.linesOf(this.choicesLogFor(prepDirPath));
        choiceLines.orElse(List.of())
                .forEach(line -> parseChoice(line, skipped, overlaps, corruptSidecars));
        return new Ledger(this.moveRecordLogFor(prepDirPath), Map.copyOf(moves), Set.copyOf(skipped),
                Map.copyOf(overlaps), Map.copyOf(corruptSidecars), choiceLines.isEmpty());
    }

    /**
     * Records a move this app is about to carry out itself, hashed from the source beforehand.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param source {@link Path} the file being moved
     * @param dest {@link Path} the exact, already-collision-resolved destination
     * @param hash {@link String} the source's hash, taken before the move
     */
    void recordMove(final Path prepDirPath, final Path source, final Path dest, final String hash) {
        this.mediaStore.appendLine(this.moveRecordLogFor(prepDirPath),
                source + RECORD_DELIMITER + dest + RECORD_DELIMITER + hash);
    }

    /**
     * Records a move inferred after the fact from disk state, the original log having been lost.
     * The provenance marker is what separates such a line from a witnessed one.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param source {@link Path} the file whose move was inferred
     * @param dest {@link Path} the destination the file was located at
     * @param hash {@link String} the hash of whatever was found at dest
     */
    void recordReconstructed(final Path prepDirPath, final Path source, final Path dest, final String hash) {
        this.mediaStore.appendLine(this.moveRecordLogFor(prepDirPath), source + RECORD_DELIMITER + dest
                + RECORD_DELIMITER + hash + RECORD_DELIMITER + RECONSTRUCTED_MARKER);
    }

    /**
     * Records that the user gave up on a missing source rather than restoring it.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param source {@link Path} the missing file's original source path
     * @param answeredOn {@link AnswerSource} which surface the answer was given through
     */
    void recordSkip(final Path prepDirPath, final Path source, final AnswerSource answeredOn) {
        this.appendChoice(prepDirPath, source + RECORD_DELIMITER + SKIPPED_MARKER
                + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + answeredOn);
    }

    /**
     * The move-record file's own path inside a prep directory.
     *
     * @param prepDirPath {@link Path} the prep directory
     * @return {@link Path} the move-record file's path
     */
    private Path moveRecordLogFor(final Path prepDirPath) {
        return prepDirPath.resolve(MOVE_RECORD_LOG);
    }

    /**
     * One ledger file's lines, or nothing at all when its bytes are not decodable text. A missing
     * file is not a failure - either file may legitimately never have been written - so it reads
     * back as no lines.
     *
     * <p>Only a decode failure degrades. A damaged ledger is the exact state the recovery flow
     * exists to diagnose, so undecodable bytes have to reach a diagnosis rather than abort it.
     * Every other I/O failure propagates untouched. A file held open by a backup or antivirus
     * scanner still holds every entry it ever did. Treating that as lost testimony would file away
     * answers nothing ever damaged, so the transient case fails the run loudly instead.
     *
     * <p>This has no side effects, which is what keeps a diagnosis side-effect-free.
     *
     * @param file {@link Path} the ledger file to read
     * @return an {@link Optional} {@link List} of {@link String}, the file's lines, empty if its bytes do not decode
     */
    private Optional<List<String>> linesOf(final Path file) {
        try {
            return Optional.of(this.mediaStore.readLines(file));
        } catch (final UncheckedIOException e) {
            if (e.getCause() instanceof CharacterCodingException) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Appends one line to the choices file, filing an undecodable one into the disaster drawer
     * first. Without that, a fresh answer would land in a file nothing can parse. The finding the
     * user just answered would then raise again on the very next read. The damaged original is
     * kept for forensics rather than deleted. Its own answers are gone either way.
     *
     * <p>A file that has never been written reads back as no lines, not as a failure, so nothing is
     * filed for it either.
     *
     * @param prepDirPath {@link Path} the prep directory whose choices file receives the line
     * @param line {@link String} the already-formatted entry to append
     */
    private void appendChoice(final Path prepDirPath, final String line) {
        final Path choicesLog = this.choicesLogFor(prepDirPath);
        if (this.linesOf(choicesLog).isEmpty()) {
            this.disasterDrawer.file(prepDirPath, choicesLog, CHOICES_DRAWER_LABEL);
        }
        this.mediaStore.appendLine(choicesLog, line);
    }

    /**
     * Records which side of a decision/unreviewable overlap the user chose.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param file {@link Path} the file the overlap concerns
     * @param resolution {@link OverlapResolution} which listing should win
     * @param answeredOn {@link AnswerSource} which surface the answer was given through
     */
    void recordOverlap(final Path prepDirPath, final Path file, final OverlapResolution resolution,
                       final AnswerSource answeredOn) {
        this.appendChoice(prepDirPath, file + RECORD_DELIMITER + OVERLAP_MARKER
                + RECORD_DELIMITER + resolution + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + answeredOn);
    }

    /**
     * Records how the user resolved a montage whose sidecar could not be read. Keyed by montage id
     * rather than a file path - the one disposition this ledger tracks per-montage, not per-file.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param montage {@link String} the montage id this resolution concerns
     * @param resolution {@link CorruptSidecarResolution} which way the batch was resolved
     * @param answeredOn {@link AnswerSource} which surface the answer was given through
     */
    void recordCorruptSidecar(final Path prepDirPath, final String montage,
                              final CorruptSidecarResolution resolution, final AnswerSource answeredOn) {
        this.appendChoice(prepDirPath, montage + RECORD_DELIMITER
                + CORRUPT_SIDECAR_MARKER + RECORD_DELIMITER + resolution + RECORD_DELIMITER + Instant.now()
                + RECORD_DELIMITER + answeredOn);
    }

    /**
     * Parses one move-record line, keyed by the source it moved. A witnessed record carries three
     * fields and a reconstructed one carries four, the last being the provenance marker. Both parse
     * to the same shape, since a classifying caller trusts them identically. A line whose shape is
     * not recognized, or whose shape matches but whose subject cannot be turned into a path, is
     * silently ignored the same way. {@link Path#of} would otherwise be an unchecked escape route
     * out of every caller's read-failure handling. One garbled line has no business taking the whole
     * file's worth of otherwise-good records down with it.
     *
     * @param line {@link String} one line of the move-record file
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} accumulated move records
     */
    private static void parseMoveRecord(final String line, final Map<Path, MoveRecord> moves) {
        final String[] fields = line.split(RECORD_DELIMITER, -1);
        if (fields.length != 3 && !(fields.length == 4 && RECONSTRUCTED_MARKER.equals(fields[3]))) {
            return;
        }
        try {
            moves.put(Path.of(fields[0]), new MoveRecord(Path.of(fields[1]), fields[2]));
        } catch (final InvalidPathException e) {
            // Same treatment as a line whose shape is not recognized at all.
        }
    }

    /**
     * Parses one choices line into whichever of the three accumulators its marker names. Every shape
     * carries its subject first and its marker second, so one field decides which kind a line is. A
     * line whose shape is not recognized, or whose shape matches but whose subject or resolution
     * field is garbled, is silently ignored the same way. One bad line then costs one lost answer
     * rather than the whole file, and the disposition it named reads as never given.
     *
     * @param line {@link String} one line of the choices file
     * @param skipped a {@link Set} of {@link Path} accumulated sources the user gave up on
     * @param overlaps a {@link Map} of {@link Path} to {@link OverlapResolution} accumulated overlap resolutions
     * @param corruptSidecars a {@link Map} of {@link String} to {@link CorruptSidecarResolution} accumulated
     * corrupt-sidecar resolutions, keyed by montage id
     */
    private static void parseChoice(final String line, final Set<Path> skipped,
                                    final Map<Path, OverlapResolution> overlaps,
                                    final Map<String, CorruptSidecarResolution> corruptSidecars) {
        final String[] fields = line.split(RECORD_DELIMITER, -1);
        try {
            if (fields.length == 4 && SKIPPED_MARKER.equals(fields[1])) {
                skipped.add(Path.of(fields[0]));
            } else if (fields.length == 5 && OVERLAP_MARKER.equals(fields[1])) {
                overlaps.put(Path.of(fields[0]), OverlapResolution.valueOf(fields[2]));
            } else if (fields.length == 5 && CORRUPT_SIDECAR_MARKER.equals(fields[1])) {
                corruptSidecars.put(fields[0], CorruptSidecarResolution.valueOf(fields[2]));
            }
        } catch (final IllegalArgumentException e) {
            // Covers both InvalidPathException (Path.of) and a plain IllegalArgumentException
            // (Enum.valueOf) - the same treatment as a line whose shape is not recognized at all.
        }
    }

    /**
     * One recorded move: the exact, already-collision-resolved destination a move-based decision's
     * source was headed for, plus the hash proving which bytes went there. A witnessed and a
     * reconstructed record are trusted identically, so provenance is not a field here.
     *
     * @param dest {@link Path} the destination the source was moved to
     * @param hash {@link String} the hash recorded for the moved bytes
     */
    public record MoveRecord(Path dest, String hash) {
    }

    /**
     * A single snapshot of both ledger files, parsed in one pass into the four dispositions they can
     * carry. Every field is an immutable copy, since one snapshot is meant to be shared read-only
     * across several consumers in the same run.
     *
     * <p>Only the move-record file's path is carried here. A {@code MissingSource} finding names it
     * as the place a move's proof should have been.
     *
     * <p>The disk state this reflects can change: a CHOICE remedy appends, and
     * {@code ReconcileEngine} files the move-record file away before rebuilding it. So a snapshot
     * is only valid for the run that took it. Never cache one across a service's separate public
     * calls. And take it first, before any write that same run might make - never after a remedy
     * or a reconcile the run itself performs.
     *
     * <p>choicesUndecodable is the one failure this snapshot carries. An undecodable move-record
     * file needs no flag: every move it held reports as an unproven one, which is already how a
     * missing record reads, and a reconcile rebuilds them all from disk. Damaged testimony has
     * no such second source, so a caller has to be able to say so.
     *
     * @param moveRecordLog {@link Path} the move-record file this snapshot's moves were read from
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} every recorded move, keyed by source
     * @param skipped a {@link Set} of {@link Path} every source the user gave up on
     * @param overlaps a {@link Map} of {@link Path} to {@link OverlapResolution} every resolved overlap
     * @param corruptSidecars a {@link Map} of {@link String} to {@link CorruptSidecarResolution} every resolved
     * corrupt sidecar, keyed by montage id
     * @param choicesUndecodable boolean whether the choices file exists but its bytes do not decode
     */
    public record Ledger(Path moveRecordLog, Map<Path, MoveRecord> moves, Set<Path> skipped,
                         Map<Path, OverlapResolution> overlaps, Map<String, CorruptSidecarResolution> corruptSidecars,
                         boolean choicesUndecodable) {
    }
}
