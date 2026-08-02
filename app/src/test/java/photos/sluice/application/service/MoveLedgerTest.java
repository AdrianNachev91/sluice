package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.application.service.MoveLedger.MoveRecord;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.OverlapResolution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.InstanceOfAssertFactories.PATH;
import static photos.sluice.application.service.CullPrepTestSupport.writeUndecodable;

// The ledger's own file layout, asserted from both sides: which file each kind of entry lands in,
// and what one read() makes of the pair. Everything goes through the real store against a @TempDir,
// since what actually lands on disk is the whole point of this class.
class MoveLedgerTest {

    private static final String MOVE_RECORDS = "move-records.log";
    private static final String CHOICES = "choices.log";

    @Test
    void aWitnessedMoveGoesToTheMoveRecordFile(@TempDir final Path prepDir) throws IOException {
        final Path source = prepDir.resolve("Sorted/a.jpg");
        final Path dest = prepDir.resolve("Review/junk/a.jpg");

        moveLedger().recordMove(prepDir, source, dest, "hash-a");

        assertThat(fieldsOf(prepDir, MOVE_RECORDS))
                .containsExactly(List.of(source.toString(), dest.toString(), "hash-a"));
        assertThat(Files.exists(prepDir.resolve(CHOICES))).isFalse();
    }

    @Test
    void aReconstructedMoveGoesToTheMoveRecordFileCarryingItsProvenanceMarker(@TempDir final Path prepDir)
            throws IOException {
        final Path source = prepDir.resolve("Sorted/a.jpg");
        final Path dest = prepDir.resolve("Review/junk/a.jpg");

        moveLedger().recordReconstructed(prepDir, source, dest, "hash-a");

        assertThat(fieldsOf(prepDir, MOVE_RECORDS))
                .containsExactly(List.of(source.toString(), dest.toString(), "hash-a", "RECONSTRUCTED"));
        assertThat(Files.exists(prepDir.resolve(CHOICES))).isFalse();
    }

    @Test
    void aSkipGoesToTheChoicesFile(@TempDir final Path prepDir) throws IOException {
        final Path source = prepDir.resolve("Sorted/gone.jpg");

        moveLedger().recordSkip(prepDir, source, "deleted it myself");

        assertThat(fieldsOf(prepDir, CHOICES).getFirst())
                .startsWith(source.toString(), "SKIPPED_BY_USER")
                .endsWith("deleted it myself");
        assertThat(Files.exists(prepDir.resolve(MOVE_RECORDS))).isFalse();
    }

    @Test
    void anOverlapResolutionGoesToTheChoicesFile(@TempDir final Path prepDir) throws IOException {
        final Path file = prepDir.resolve("Sorted/a.jpg");

        moveLedger().recordOverlap(prepDir, file, OverlapResolution.TRUST_DECISION, "the decision is correct");

        assertThat(fieldsOf(prepDir, CHOICES).getFirst())
                .startsWith(file.toString(), "OVERLAP_RESOLVED", "TRUST_DECISION")
                .endsWith("the decision is correct");
        assertThat(Files.exists(prepDir.resolve(MOVE_RECORDS))).isFalse();
    }

    // The one disposition keyed by montage id rather than a file path, on both the write and the
    // read side.
    @Test
    void aCorruptSidecarResolutionGoesToTheChoicesFileKeyedByMontage(@TempDir final Path prepDir) throws IOException {
        final MoveLedger ledger = moveLedger();
        ledger.recordCorruptSidecar(prepDir, "montage-002", CorruptSidecarResolution.SET_ASIDE, "redo it");

        assertThat(fieldsOf(prepDir, CHOICES).getFirst())
                .startsWith("montage-002", "CORRUPT_SIDECAR_RESOLVED", "SET_ASIDE")
                .endsWith("redo it");
        assertThat(ledger.read(prepDir).corruptSidecars())
                .containsExactly(entry("montage-002", CorruptSidecarResolution.SET_ASIDE));
        assertThat(Files.exists(prepDir.resolve(MOVE_RECORDS))).isFalse();
    }

    @Test
    void oneReadReturnsEveryDispositionFromBothFilesInASingleSnapshot(@TempDir final Path prepDir) {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final Path rebuilt = prepDir.resolve("Sorted/rebuilt.jpg");
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final Path overlapping = prepDir.resolve("Sorted/overlapping.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");
        ledger.recordReconstructed(prepDir, rebuilt, prepDir.resolve("Review/junk/rebuilt.jpg"), "hash-rebuilt");
        ledger.recordSkip(prepDir, gone, "deleted it myself");
        ledger.recordOverlap(prepDir, overlapping, OverlapResolution.TREAT_AS_UNREVIEWABLE, "leave it alone");
        ledger.recordCorruptSidecar(prepDir, "montage-002", CorruptSidecarResolution.APPLY_ANYWAY, "shard looks fine");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.moves()).containsOnlyKeys(moved, rebuilt);
        assertThat(snapshot.moves().get(moved))
                .isEqualTo(new MoveRecord(prepDir.resolve("Review/junk/moved.jpg"), "hash-moved"));
        assertThat(snapshot.moves().get(rebuilt))
                .isEqualTo(new MoveRecord(prepDir.resolve("Review/junk/rebuilt.jpg"), "hash-rebuilt"));
        assertThat(snapshot.skipped()).containsExactly(gone);
        assertThat(snapshot.overlaps()).containsExactly(entry(overlapping, OverlapResolution.TREAT_AS_UNREVIEWABLE));
        assertThat(snapshot.corruptSidecars())
                .containsExactly(entry("montage-002", CorruptSidecarResolution.APPLY_ANYWAY));
        assertThat(snapshot.moveRecordLog()).isEqualTo(prepDir.resolve(MOVE_RECORDS));
    }

    // Each file is read on its own, so one being absent must not cost the other's contents.
    @Test
    void aSnapshotCarriesTheChoicesFileEvenWhenNoMoveHasEverBeenRecorded(@TempDir final Path prepDir) {
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordSkip(prepDir, gone, "deleted it myself");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.skipped()).containsExactly(gone);
        assertThat(snapshot.moves()).isEmpty();
    }

    @Test
    void aSnapshotCarriesTheMoveRecordFileEvenWhenNoChoiceHasEverBeenMade(@TempDir final Path prepDir) {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.moves()).containsOnlyKeys(moved);
        assertThat(snapshot.skipped()).isEmpty();
        assertThat(snapshot.overlaps()).isEmpty();
        assertThat(snapshot.corruptSidecars()).isEmpty();
    }

    @Test
    void readingAPrepDirWithNeitherFileGivesAnEmptySnapshot(@TempDir final Path prepDir) {
        final Ledger snapshot = moveLedger().read(prepDir);

        assertThat(snapshot.moves()).isEmpty();
        assertThat(snapshot.skipped()).isEmpty();
        assertThat(snapshot.overlaps()).isEmpty();
        assertThat(snapshot.corruptSidecars()).isEmpty();
        assertThat(snapshot.moveRecordLog()).isEqualTo(prepDir.resolve(MOVE_RECORDS));
    }

    // A line shape neither file recognizes is dropped rather than throwing. A half-written line is
    // what a crash mid-append leaves behind. Parsing carries on past it, so one bad line costs its
    // own entry rather than every entry after it.
    @Test
    void anUnrecognizedLineIsIgnoredInEitherFileWithoutLosingTheGoodLinesAroundIt(@TempDir final Path prepDir)
            throws IOException {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final Path movedLater = prepDir.resolve("Sorted/moved-later.jpg");
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final Path goneLater = prepDir.resolve("Sorted/gone-later.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");
        ledger.recordSkip(prepDir, gone, "deleted it myself");
        appendRaw(prepDir.resolve(MOVE_RECORDS), "half-a-line");
        appendRaw(prepDir.resolve(CHOICES), "half-a-line");
        ledger.recordMove(prepDir, movedLater, prepDir.resolve("Review/junk/moved-later.jpg"), "hash-later");
        ledger.recordSkip(prepDir, goneLater, "this one too");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.moves()).containsOnlyKeys(moved, movedLater);
        assertThat(snapshot.skipped()).containsExactlyInAnyOrder(gone, goneLater);
    }

    // A line whose shape matches - right field count, right marker - but whose subject or
    // resolution cannot be parsed gets the same treatment as a shape neither parser recognizes at
    // all: dropped, not thrown. Path.of and Enum.valueOf would otherwise be unchecked escape routes
    // out of every caller's read-failure handling.
    @Test
    void aLineMatchingItsShapeButGarbledInContentIsDroppedWithoutLosingTheGoodLinesAroundIt(@TempDir final Path prepDir)
            throws IOException {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");
        final String d = MoveLedger.RECORD_DELIMITER;
        // A NUL byte is the one character Path.of rejects on both platforms this project's CI runs.
        // Built via a char literal rather than a string escape, so no encoding layer between here
        // and the file can silently turn it into something else.
        final String unusablePath = "bad" + (char) 0 + "path";
        appendRaw(prepDir.resolve(MOVE_RECORDS), unusablePath + d + "Review/junk/garbled.jpg" + d + "hash-garbled");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.moves()).containsOnlyKeys(moved);
    }

    // The choices file's own three shapes, each garbled the way its own fields can be: a subject
    // Path.of cannot parse, or a resolution Enum.valueOf cannot parse. Every good entry around them
    // still parses.
    @Test
    void everyChoicesShapeGarbledInContentIsDroppedWithoutLosingTheGoodEntriesAroundIt(@TempDir final Path prepDir)
            throws IOException {
        final Path skipped = prepDir.resolve("Sorted/skipped.jpg");
        final Path overlapping = prepDir.resolve("Sorted/overlapping.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordSkip(prepDir, skipped, "deleted it myself");
        ledger.recordOverlap(prepDir, overlapping, OverlapResolution.TRUST_DECISION, "the decision is correct");
        ledger.recordCorruptSidecar(prepDir, "montage-001", CorruptSidecarResolution.SET_ASIDE, "redo it");
        final String d = MoveLedger.RECORD_DELIMITER;
        // Same NUL-byte technique as the move-record test above, for the same reason.
        final String unusablePath = "bad" + (char) 0 + "path";
        appendRaw(prepDir.resolve(CHOICES), unusablePath + d + "SKIPPED_BY_USER" + d + "now" + d + "why");
        appendRaw(prepDir.resolve(CHOICES),
                "Sorted/other.jpg" + d + "OVERLAP_RESOLVED" + d + "NOT_A_RESOLUTION" + d + "now" + d + "why");
        appendRaw(prepDir.resolve(CHOICES),
                "montage-002" + d + "CORRUPT_SIDECAR_RESOLVED" + d + "NOT_A_RESOLUTION" + d + "now" + d + "why");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.skipped()).containsExactly(skipped);
        assertThat(snapshot.overlaps()).containsExactly(entry(overlapping, OverlapResolution.TRUST_DECISION));
        assertThat(snapshot.corruptSidecars())
                .containsExactly(entry("montage-001", CorruptSidecarResolution.SET_ASIDE));
    }

    // A move record and a skip that happen to share a field count sit in different files, so neither
    // parser ever sees the other's shape. A reconstructed move and a skip are both four fields.
    @Test
    void aSkipIsNeverMistakenForAReconstructedMove(@TempDir final Path prepDir) {
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordSkip(prepDir, gone, "RECONSTRUCTED");

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.skipped()).containsExactly(gone);
        assertThat(snapshot.moves()).isEmpty();
    }

    // A file present but undecodable is the one failure a snapshot reports, and it must not cost the
    // other file's contents. Bytes no UTF-8 decoder accepts stand in for a torn write or a damaged
    // sector. That is what a genuinely corrupt log looks like, as opposed to a malformed line.
    @Test
    void anUndecodableChoicesFileIsReportedOnTheSnapshotWithoutCostingTheMoveRecords(@TempDir final Path prepDir)
            throws IOException {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");
        ledger.recordSkip(prepDir, gone, "deleted it myself");
        writeUndecodable(prepDir.resolve(CHOICES));

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.choicesUndecodable()).isTrue();
        assertThat(snapshot.skipped()).isEmpty();
        assertThat(snapshot.moves()).containsOnlyKeys(moved);
    }

    // Losing the move records is recoverable - every affected file simply reports as unproven, and a
    // reconcile rebuilds them from disk. So an undecodable move-record file needs no flag of its own.
    @Test
    void anUndecodableMoveRecordFileReadsAsNoMovesRatherThanThrowing(@TempDir final Path prepDir) throws IOException {
        final Path moved = prepDir.resolve("Sorted/moved.jpg");
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final MoveLedger ledger = moveLedger();
        ledger.recordMove(prepDir, moved, prepDir.resolve("Review/junk/moved.jpg"), "hash-moved");
        ledger.recordSkip(prepDir, gone, "deleted it myself");
        writeUndecodable(prepDir.resolve(MOVE_RECORDS));

        final Ledger snapshot = ledger.read(prepDir);

        assertThat(snapshot.moves()).isEmpty();
        assertThat(snapshot.choicesUndecodable()).isFalse();
        assertThat(snapshot.skipped()).containsExactly(gone);
    }

    // Undecodable bytes are a permanent defect worth degrading around. Every other read failure is
    // transient - the file still holds every answer it ever did. Filing those away would destroy
    // intact testimony over a scanner's open handle, so that case has to stay loud.
    @Test
    void aReadFailureThatIsNotADecodeFailurePropagatesInsteadOfReportingLostAnswers(@TempDir final Path prepDir) {
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        moveLedger().recordSkip(prepDir, gone, "deleted it myself");
        final var locked = new LockedChoices();
        final var ledger = new MoveLedger(locked, new DisasterDrawer(locked));

        assertThatThrownBy(() -> ledger.read(prepDir))
                .isInstanceOf(UncheckedIOException.class)
                .cause().isInstanceOf(AccessDeniedException.class);
        // The answers were never damaged, so they are still exactly where the user left them.
        assertThat(Files.exists(prepDir.resolve(CHOICES))).isTrue();
    }

    // The same distinction on the write side. An answer must not file an intact file away just
    // because a scanner held it open for the length of one read.
    @Test
    void aFreshChoiceDoesNotFileAwayAChoicesFileThatMerelyFailedToOpen(@TempDir final Path prepDir) {
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        moveLedger().recordSkip(prepDir, gone, "deleted it myself");
        final var locked = new LockedChoices();
        final var ledger = new MoveLedger(locked, new DisasterDrawer(locked));

        assertThatThrownBy(() -> ledger.recordSkip(prepDir, prepDir.resolve("Sorted/other.jpg"), "this one too"))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Files.exists(prepDir.resolve("disasters"))).isFalse();
        assertThat(moveLedger().read(prepDir).skipped()).containsExactly(gone);
    }

    // Appending to a file nothing can parse would leave the fresh answer as lost as the ones
    // already in it. The finding it settles would then raise again forever.
    @Test
    void aFreshChoiceFilesAnUndecodableChoicesFileAwayAndLandsInAReadableOne(@TempDir final Path prepDir)
            throws IOException {
        final Path gone = prepDir.resolve("Sorted/gone.jpg");
        final MoveLedger ledger = moveLedger();
        writeUndecodable(prepDir.resolve(CHOICES));

        ledger.recordSkip(prepDir, gone, "deleted it myself");

        final Ledger snapshot = ledger.read(prepDir);
        assertThat(snapshot.choicesUndecodable()).isFalse();
        assertThat(snapshot.skipped()).containsExactly(gone);
        try (final var filed = Files.list(prepDir.resolve("disasters"))) {
            final List<Path> entries = filed.toList();
            assertThat(entries).singleElement(as(PATH))
                    .asString().contains("choices-log");
            // Filed for forensics, so the damaged bytes have to arrive intact rather than repaired.
            assertThat(Files.readAllBytes(entries.getFirst()))
                    .containsExactly((byte) 0xFF, (byte) 0xFE, (byte) 0xFF);
        }
    }

    private static MoveLedger moveLedger() {
        final var mediaStore = new NioMediaStore();
        return new MoveLedger(mediaStore, new DisasterDrawer(mediaStore));
    }

    private static List<List<String>> fieldsOf(final Path prepDir, final String fileName) throws IOException {
        return Files.readAllLines(prepDir.resolve(fileName)).stream()
                .map(line -> List.of(line.split(MoveLedger.RECORD_DELIMITER, -1)))
                .toList();
    }

    private static void appendRaw(final Path file, final String line) throws IOException {
        Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.APPEND);
    }

    // A choices file whose bytes are perfectly fine but whose read fails anyway. Windows makes this
    // easiest to picture, since its locking is mandatory and a backup or antivirus handle alone is
    // enough. A permission denial, a dropped network mount or an unhydrated cloud placeholder does
    // the same anywhere. Only readLines is intercepted, so a filing that did happen would still
    // land on disk for the test to catch.
    private static final class LockedChoices extends NioMediaStore {

        @Override
        public List<String> readLines(final Path file) {
            if (CHOICES.equals(file.getFileName().toString())) {
                throw new UncheckedIOException(new AccessDeniedException(file.toString()));
            }
            return super.readLines(file);
        }
    }
}
