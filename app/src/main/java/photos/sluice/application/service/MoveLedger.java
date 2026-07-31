package photos.sluice.application.service;

import org.springframework.stereotype.Component;
import photos.sluice.application.port.out.MediaStore;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.OverlapResolution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 * output stays byte-pristine, which is what keeps the audit trail honest.
 *
 * <p>Naming and parsing live together on purpose. Each file's line shapes are only unambiguous as a
 * closed set, and that reasoning only holds while a single class owns both sides of it.
 *
 * <p>A caller reads once per run via {@link #read} and threads the resulting {@link Ledger}
 * through every consumer that needs it. One snapshot covers both files. That snapshot is what
 * {@link LedgerReader} exposes to a read-only collaborator; only this class can also append to it.
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

    // A control character, not a printable one - guaranteed absent from any path on every
    // mainstream filesystem. A record's fields can be split back apart with zero escaping and no
    // ambiguity even when a path itself contains spaces, commas, or tabs.
    static final String RECORD_DELIMITER = "\u001F";

    private final MediaStore mediaStore;

    /**
     * Creates a ledger reading and appending through the given store.
     *
     * @param mediaStore {@link MediaStore} reads and appends both ledger files
     */
    public MoveLedger(final MediaStore mediaStore) {
        this.mediaStore = mediaStore;
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
        this.mediaStore.readLines(this.moveRecordLogFor(prepDirPath))
                .forEach(line -> parseMoveRecord(line, moves));
        this.mediaStore.readLines(this.choicesLogFor(prepDirPath))
                .forEach(line -> parseChoice(line, skipped, overlaps, corruptSidecars));
        return new Ledger(this.moveRecordLogFor(prepDirPath), Map.copyOf(moves), Set.copyOf(skipped),
                Map.copyOf(overlaps), Map.copyOf(corruptSidecars));
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
     * The provenance marker keeps a rebuilt log honest about which entries were witnessed.
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
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    void recordSkip(final Path prepDirPath, final Path source, final String reason) {
        this.mediaStore.appendLine(this.choicesLogFor(prepDirPath), source + RECORD_DELIMITER + SKIPPED_MARKER
                + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + reason);
    }

    /**
     * Records which side of a decision/unreviewable overlap the user chose.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param file {@link Path} the file the overlap concerns
     * @param resolution {@link OverlapResolution} which listing should win
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    void recordOverlap(final Path prepDirPath, final Path file, final OverlapResolution resolution,
                       final String reason) {
        this.mediaStore.appendLine(this.choicesLogFor(prepDirPath), file + RECORD_DELIMITER + OVERLAP_MARKER
                + RECORD_DELIMITER + resolution + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + reason);
    }

    /**
     * Records how the user resolved a montage whose sidecar could not be read. Keyed by montage id
     * rather than a file path - the one disposition this ledger tracks per-montage, not per-file.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param montage {@link String} the montage id this resolution concerns
     * @param resolution {@link CorruptSidecarResolution} which way the batch was resolved
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    void recordCorruptSidecar(final Path prepDirPath, final String montage, final CorruptSidecarResolution resolution
            , final String reason) {
        this.mediaStore.appendLine(this.choicesLogFor(prepDirPath), montage + RECORD_DELIMITER
                + CORRUPT_SIDECAR_MARKER + RECORD_DELIMITER + resolution + RECORD_DELIMITER + Instant.now()
                + RECORD_DELIMITER + reason);
    }

    /**
     * Parses one move-record line, keyed by the source it moved. A witnessed record carries three
     * fields and a reconstructed one carries four, the last being the provenance marker. Both parse
     * to the same shape, since a classifying caller trusts them identically. An unrecognized shape
     * is silently ignored.
     *
     * @param line {@link String} one line of the move-record file
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} accumulated move records
     */
    private static void parseMoveRecord(final String line, final Map<Path, MoveRecord> moves) {
        final String[] fields = line.split(RECORD_DELIMITER, -1);
        if (fields.length == 3 || (fields.length == 4 && RECONSTRUCTED_MARKER.equals(fields[3]))) {
            moves.put(Path.of(fields[0]), new MoveRecord(Path.of(fields[1]), fields[2]));
        }
    }

    /**
     * Parses one choices line into whichever of the three accumulators its marker names. Every shape
     * carries its subject first and its marker second, so one field decides which kind a line is. An
     * unrecognized shape is silently ignored.
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
        if (fields.length == 4 && SKIPPED_MARKER.equals(fields[1])) {
            skipped.add(Path.of(fields[0]));
        } else if (fields.length == 5 && OVERLAP_MARKER.equals(fields[1])) {
            overlaps.put(Path.of(fields[0]), OverlapResolution.valueOf(fields[2]));
        } else if (fields.length == 5 && CORRUPT_SIDECAR_MARKER.equals(fields[1])) {
            corruptSidecars.put(fields[0], CorruptSidecarResolution.valueOf(fields[2]));
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
     * {@code ReconcileEngine} files both files away before rebuilding the move records. So a
     * snapshot is only valid for the run that took it. Never cache one across a service's separate
     * public calls. And take it first, before any write that same run might make - never after a
     * remedy or a reconcile the run itself performs.
     *
     * @param moveRecordLog {@link Path} the move-record file this snapshot's moves were read from
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} every recorded move, keyed by source
     * @param skipped a {@link Set} of {@link Path} every source the user gave up on
     * @param overlaps a {@link Map} of {@link Path} to {@link OverlapResolution} every resolved overlap
     * @param corruptSidecars a {@link Map} of {@link String} to {@link CorruptSidecarResolution} every resolved
     * corrupt sidecar, keyed by montage id
     */
    public record Ledger(Path moveRecordLog, Map<Path, MoveRecord> moves, Set<Path> skipped,
                         Map<Path, OverlapResolution> overlaps, Map<String, CorruptSidecarResolution> corruptSidecars) {
    }
}
