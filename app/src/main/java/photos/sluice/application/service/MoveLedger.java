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
 * The sole owner of {@code move-records.log}, the ledger recording how each of a prep dir's
 * decisions was finally resolved. It knows the file's name, its line format, every disposition
 * marker, how to parse the whole file back, and how to append each kind of entry.
 *
 * <p>It carries four dispositions. A move, witnessed or reconstructed. A source the user gave up
 * on. A decision/unreviewable overlap the user resolved. And a montage whose corrupt sidecar the
 * user resolved. Every CHOICE remedy lands here rather than editing a shard or index.json. Producer
 * output stays byte-pristine, which is what keeps the audit trail honest.
 *
 * <p>Naming and parsing live together on purpose. The markers are only unambiguous because no real
 * destination path can equal one, and that reasoning only holds while a single class owns both
 * sides of it.
 *
 * <p>A caller reads once per run via {@link #read} and threads the resulting {@link Ledger}
 * through every consumer that needs it. That snapshot is what {@link LedgerReader} exposes to a
 * read-only collaborator; only this class can also append to it.
 *
 * <p>Format reference: {@code app/docs/design/application/service/move-ledger.md}.
 */
@Component
public class MoveLedger implements LedgerReader {

    private static final String MOVE_RECORD_LOG = "move-records.log";

    // The move-record line format's one additive field. A legacy three-field line (no fourth field)
    // means WITNESSED - recorded from the source's own hash right before a real move. This exact
    // marker as the fourth field means RECONSTRUCTED - the record was inferred from disk state
    // alone, the original log having been lost. Both are trusted identically by a classifying
    // caller; the marker is provenance for a human reading the log, not a behavioral distinction.
    private static final String RECONSTRUCTED_MARKER = "RECONSTRUCTED";

    // The CHOICE-remedy dispositions, generalizing the log beyond plain moves. All three sit in the
    // same field position a move record's own dest path would occupy. That is safe, since none of
    // these markers is a value a real destination path could ever equal.
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
     * @param mediaStore {@link MediaStore} reads and appends the ledger file
     */
    public MoveLedger(MediaStore mediaStore) {
        this.mediaStore = mediaStore;
    }

    /**
     * The ledger file's own path inside a prep directory.
     *
     * @param prepDirPath {@link Path} the prep directory
     * @return {@link Path} the ledger file's path
     */
    private Path logFor(Path prepDirPath) {
        return prepDirPath.resolve(MOVE_RECORD_LOG);
    }

    /**
     * Parses the whole ledger into its four constituent pieces in one pass. A caller takes one
     * snapshot per run and threads it through every consumer that needs it. See {@link Ledger}'s
     * own Javadoc for the rules that snapshot must follow.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger to read
     * @return {@link Ledger} the parsed ledger, empty in every part if no log exists yet
     */
    @Override
    public Ledger read(Path prepDirPath) {
        var moves = new HashMap<Path, MoveRecord>();
        var skipped = new HashSet<Path>();
        var overlaps = new HashMap<Path, OverlapResolution>();
        var corruptSidecars = new HashMap<String, CorruptSidecarResolution>();
        mediaStore.readLines(logFor(prepDirPath))
                .forEach(line -> parseLine(line, moves, skipped, overlaps, corruptSidecars));
        return new Ledger(logFor(prepDirPath), Map.copyOf(moves), Set.copyOf(skipped),
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
    void recordMove(Path prepDirPath, Path source, Path dest, String hash) {
        mediaStore.appendLine(logFor(prepDirPath), source + RECORD_DELIMITER + dest + RECORD_DELIMITER + hash);
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
    void recordReconstructed(Path prepDirPath, Path source, Path dest, String hash) {
        mediaStore.appendLine(logFor(prepDirPath), source + RECORD_DELIMITER + dest + RECORD_DELIMITER
                + hash + RECORD_DELIMITER + RECONSTRUCTED_MARKER);
    }

    /**
     * Records that the user gave up on a missing source rather than restoring it.
     *
     * @param prepDirPath {@link Path} the prep directory whose ledger receives the entry
     * @param source {@link Path} the missing file's original source path
     * @param reason {@link String} a short user-supplied reason, recorded for the audit trail
     */
    void recordSkip(Path prepDirPath, Path source, String reason) {
        mediaStore.appendLine(logFor(prepDirPath), source + RECORD_DELIMITER + SKIPPED_MARKER
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
    void recordOverlap(Path prepDirPath, Path file, OverlapResolution resolution, String reason) {
        mediaStore.appendLine(logFor(prepDirPath), file + RECORD_DELIMITER + OVERLAP_MARKER + RECORD_DELIMITER
                + resolution + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + reason);
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
    void recordCorruptSidecar(Path prepDirPath, String montage, CorruptSidecarResolution resolution, String reason) {
        mediaStore.appendLine(logFor(prepDirPath), montage + RECORD_DELIMITER + CORRUPT_SIDECAR_MARKER
                + RECORD_DELIMITER + resolution + RECORD_DELIMITER + Instant.now() + RECORD_DELIMITER + reason);
    }

    /**
     * Parses one line into whichever of the four accumulators it belongs to. A move-record line's
     * own dest field can never equal one of the disposition markers. So checking those markers
     * first, before falling back to the witnessed and reconstructed move shapes, is unambiguous. An
     * unrecognized shape is silently ignored.
     *
     * @param line {@link String} one line of the ledger
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} accumulated move records
     * @param skipped a {@link Set} of {@link Path} accumulated sources the user gave up on
     * @param overlaps a {@link Map} of {@link Path} to {@link OverlapResolution} accumulated overlap resolutions
     * @param corruptSidecars a {@link Map} of {@link String} to {@link CorruptSidecarResolution} accumulated corrupt-sidecar resolutions, keyed by montage id
     */
    private static void parseLine(String line, Map<Path, MoveRecord> moves, Set<Path> skipped,
            Map<Path, OverlapResolution> overlaps, Map<String, CorruptSidecarResolution> corruptSidecars) {
        final String[] fields = line.split(RECORD_DELIMITER, -1);
        if (fields.length < 2) {
            return;
        }
        if (fields.length == 4 && SKIPPED_MARKER.equals(fields[1])) {
            skipped.add(Path.of(fields[0]));
        } else if (fields.length == 5 && OVERLAP_MARKER.equals(fields[1])) {
            overlaps.put(Path.of(fields[0]), OverlapResolution.valueOf(fields[2]));
        } else if (fields.length == 5 && CORRUPT_SIDECAR_MARKER.equals(fields[1])) {
            corruptSidecars.put(fields[0], CorruptSidecarResolution.valueOf(fields[2]));
        } else if (fields.length == 3) {
            moves.put(Path.of(fields[0]), new MoveRecord(Path.of(fields[1]), fields[2]));
        } else if (fields.length == 4 && RECONSTRUCTED_MARKER.equals(fields[3])) {
            moves.put(Path.of(fields[0]), new MoveRecord(Path.of(fields[1]), fields[2]));
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
     * A single snapshot of the whole ledger, parsed in one pass into the four dispositions it can
     * carry. Every field is an immutable copy, since one snapshot is meant to be shared read-only
     * across several consumers in the same run.
     *
     * <p>The disk state this reflects can change: a CHOICE remedy appends, and
     * {@code ReconcileEngine} files the whole log away before rebuilding it. So a snapshot is only
     * valid for the run that took it. Never cache one across a service's separate public calls. And
     * take it first, before any write that same run might make - never after a remedy or a
     * reconcile the run itself performs.
     *
     * @param log {@link Path} the ledger file this snapshot was read from
     * @param moves a {@link Map} of {@link Path} to {@link MoveRecord} every recorded move, keyed by source
     * @param skipped a {@link Set} of {@link Path} every source the user gave up on
     * @param overlaps a {@link Map} of {@link Path} to {@link OverlapResolution} every resolved overlap
     * @param corruptSidecars a {@link Map} of {@link String} to {@link CorruptSidecarResolution} every resolved corrupt sidecar, keyed by montage id
     */
    public record Ledger(Path log, Map<Path, MoveRecord> moves, Set<Path> skipped,
            Map<Path, OverlapResolution> overlaps, Map<String, CorruptSidecarResolution> corruptSidecars) {
    }
}
