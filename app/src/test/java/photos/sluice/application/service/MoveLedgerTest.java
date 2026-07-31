package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.adapter.fs.NioMediaStore;
import photos.sluice.application.service.MoveLedger.Ledger;
import photos.sluice.application.service.MoveLedger.MoveRecord;
import photos.sluice.domain.cull.CorruptSidecarResolution;
import photos.sluice.domain.cull.OverlapResolution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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

    private static MoveLedger moveLedger() {
        return new MoveLedger(new NioMediaStore());
    }

    private static List<List<String>> fieldsOf(final Path prepDir, final String fileName) throws IOException {
        return Files.readAllLines(prepDir.resolve(fileName)).stream()
                .map(line -> List.of(line.split(MoveLedger.RECORD_DELIMITER, -1)))
                .toList();
    }

    private static void appendRaw(final Path file, final String line) throws IOException {
        Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.APPEND);
    }
}
