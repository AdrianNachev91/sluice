package photos.sluice.adapter.cli;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Finding;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class FindingPayloadTest {

    @Test
    void everyProblemTheAppCanFindIsNamedHere() {
        assertThat(everyKind().stream().map(kind -> kind.finding().getClass().getSimpleName()).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Finding.class.getPermittedSubclasses())
                        .map(Class::getSimpleName).toList());
    }

    // The fields are a map, so both a key spelled wrong and two values swapped between keys compile
    // and reach a caller. A key list alone catches only the first. Naming every expected value is
    // what catches the second, and the fixtures give each field a distinct value so it can.
    @Test
    void everyProblemWritesTheFieldsItSaysItWrites() {
        assertThat(everyKind()).allSatisfy(kind -> {
            final FindingPayload payload = FindingPayload.of(kind.finding());
            assertThat(payload.type()).isEqualTo(kind.finding().getClass().getSimpleName());
            assertThat(payload.detail()).containsExactlyEntriesOf(kind.fields());
        });
    }

    @Test
    void aProblemCarriesHowFarTheAppCanResolveItUnprompted() {
        assertThat(FindingPayload.of(new Finding.StrayShard("decisions-009.json")).remedy())
                .isEqualTo(Finding.Remedy.AUTO);
        assertThat(FindingPayload.of(new Finding.CorruptSidecar("montage-002")).remedy())
                .isEqualTo(Finding.Remedy.CHOICE);
        assertThat(FindingPayload.of(new Finding.MissingShard("montage-002", "decisions-002.json")).remedy())
                .isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void noProblemPutsThePersonsOwnSentenceOnTheWire() {
        assertThat(everyKind()).allSatisfy(kind ->
                assertThat(FindingPayload.of(kind.finding()).detail().values())
                        .doesNotContain(kind.finding().describe()));
    }

    @Test
    void aFileNamedByBothADecisionAndTheUnreviewableListIsReportedByThatFile() {
        final Path overlapping = Path.of("D:", "Sorted", "a.jpg");
        final Decision decision = new Decision.Classification(overlapping, "junk", "blurry");

        final FindingPayload payload = FindingPayload.of(new Finding.DecisionUnreviewableOverlap(decision));

        assertThat(payload.detail()).containsExactly(entry("file", overlapping.toString()));
    }

    @Test
    void aGroupSpreadAcrossSheetsNamesEveryOneOfThem() {
        final FindingPayload payload = FindingPayload.of(
                new Finding.GroupSpansMultipleMontages("beach", List.of("montage-001", "montage-004")));

        assertThat(payload.detail()).containsEntry("montages", List.of("montage-001", "montage-004"));
    }

    @Test
    void aPayloadCannotBeChangedAfterItWasRead() {
        final FindingPayload payload = FindingPayload.of(new Finding.CorruptSidecar("montage-002"));

        assertThat(payload.detail()).isUnmodifiable();
    }

    private record Kind(Finding finding, SequencedMap<String, Object> fields) {
    }

    private static List<Kind> everyKind() {
        final Path file = Path.of("D:", "Sorted", "2019", "06", "a.jpg");
        final Path sorted = Path.of("D:", "Sorted");
        final Path index = sorted.resolve("index.json");
        final Path moves = sorted.resolve("moves.log");
        return List.of(
                new Kind(new Finding.MissingMontageField("montage-001"),
                        Fields.of("montage", "montage-001")),
                new Kind(new Finding.MontageFieldMismatch("montage-001", "montage-002"),
                        Fields.of("montage", "montage-001", "declared", "montage-002")),
                new Kind(new Finding.InvalidCategory("montage-001", 3, "sunsets", "one of junk, scenery"),
                        Fields.of("montage", "montage-001", "index", 3, "category", "sunsets",
                                "allowed", "one of junk, scenery")),
                new Kind(new Finding.MissingReason("montage-001", 3),
                        Fields.of("montage", "montage-001", "index", 3)),
                new Kind(new Finding.MissingGroup("montage-002", 4),
                        Fields.of("montage", "montage-002", "index", 4)),
                new Kind(new Finding.MissingChosenReason("montage-003", 5),
                        Fields.of("montage", "montage-003", "index", 5)),
                new Kind(new Finding.WrongChosenCount("montage-001", "beach", 2),
                        Fields.of("montage", "montage-001", "group", "beach", "chosen", 2)),
                new Kind(new Finding.TooFewRejects("montage-001", "beach", 0),
                        Fields.of("montage", "montage-001", "group", "beach", "rejects", 0)),
                new Kind(new Finding.InvalidGroupSlug("montage-001", "Beach Day", 40),
                        Fields.of("montage", "montage-001", "group", "Beach Day", "maxLength", 40)),
                new Kind(new Finding.DuplicateFileReference("a.jpg", 3),
                        Fields.of("file", "a.jpg", "count", 3L)),
                new Kind(new Finding.DecisionUnreviewableOverlap(
                        new Decision.Classification(file, "junk", "blurry")),
                        Fields.of("file", file.toString())),
                new Kind(new Finding.GroupSpansMultipleMontages("beach", List.of("montage-001", "montage-004")),
                        Fields.of("group", "beach", "montages", List.of("montage-001", "montage-004"))),
                new Kind(new Finding.MissingFile("montage-004", 6),
                        Fields.of("montage", "montage-004", "index", 6)),
                new Kind(new Finding.FileOutOfScope("montage-001", 3, file),
                        Fields.of("montage", "montage-001", "index", 3, "file", file.toString())),
                new Kind(new Finding.SourceOutsideSorted(file, sorted),
                        Fields.of("file", file.toString(), "sortedRoot", sorted.toString())),
                new Kind(new Finding.StrayShard("decisions-009.json"),
                        Fields.of("shardFile", "decisions-009.json")),
                new Kind(new Finding.MissingShard("montage-002", "decisions-002.json"),
                        Fields.of("montage", "montage-002", "expectedFile", "decisions-002.json")),
                new Kind(new Finding.CorruptShard("montage-005", "decisions-005.json"),
                        Fields.of("montage", "montage-005", "shardFile", "decisions-005.json")),
                new Kind(new Finding.CorruptIndex(index), Fields.of("indexPath", index.toString())),
                new Kind(new Finding.UnreadablePrepDir(sorted), Fields.of("prepDir", sorted.toString())),
                new Kind(new Finding.CorruptSidecar("montage-002"), Fields.of("montage", "montage-002")),
                new Kind(new Finding.MissingSource(file, moves),
                        Fields.of("file", file.toString(), "moveRecordLog", moves.toString())));
    }
}
