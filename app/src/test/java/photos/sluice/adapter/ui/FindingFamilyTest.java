package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.Finding;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingFamilyTest {

    @Test
    void aShardWhoseContentIsUnusableIsAboutTheDecisions() {
        assertThat(FindingFamily.of(new Finding.MissingReason("montage-001", 0)))
                .isEqualTo(FindingFamily.DECISIONS);
        assertThat(FindingFamily.of(new Finding.WrongChosenCount("montage-001", "g", 2)))
                .isEqualTo(FindingFamily.DECISIONS);
    }

    @Test
    void aFileTheDecisionsPointAtIsAboutThePhotos() {
        assertThat(FindingFamily.of(new Finding.MissingFile("montage-001", 0)))
                .isEqualTo(FindingFamily.PHOTOS);
        assertThat(FindingFamily.of(new Finding.SourceOutsideSorted(Path.of("a"), Path.of("b"))))
                .isEqualTo(FindingFamily.PHOTOS);
    }

    @Test
    void theRunsOwnStoredFilesAreAboutTheRecords() {
        assertThat(FindingFamily.of(new Finding.CorruptIndex(Path.of("index.json"))))
                .isEqualTo(FindingFamily.RECORDS);
        assertThat(FindingFamily.of(new Finding.MissingShard("montage-001", "montage-001.json")))
                .isEqualTo(FindingFamily.RECORDS);
    }

    @Test
    void aListSpanningTwoFamiliesIsMixed() {
        assertThat(FindingFamily.of(List.of(new Finding.CorruptIndex(Path.of("a")),
                new Finding.MissingFile("montage-001", 0)))).isEqualTo(FindingFamily.MIXED);
    }

    @Test
    void anEmptyListIsMixedRatherThanAnyOneFamily() {
        assertThat(FindingFamily.of(List.of())).isEqualTo(FindingFamily.MIXED);
    }

    @Test
    void theClauseSaysWhatKindOfFaultAndNotHowMany() {
        assertThat(FindingFamily.wentWrong(List.of(new Finding.MissingReason("montage-001", 0))))
                .isEqualTo("There were structural problems with the decisions");
        assertThat(FindingFamily.wentWrong(List.of(new Finding.MissingReason("montage-001", 0),
                new Finding.MissingGroup("montage-002", 1))))
                .isEqualTo("There were structural problems with the decisions");
    }

    @Test
    void aMixedListNamesNoKindRatherThanPickingOneOfThem() {
        assertThat(FindingFamily.wentWrong(List.of(new Finding.CorruptIndex(Path.of("a")),
                new Finding.MissingFile("montage-001", 0))))
                .isEqualTo("There were problems with the sift");
    }

    @Test
    void everyOverlapFindingIsAboutTheDecisionsWhateverDecisionItCarries() {
        assertThat(FindingFamily.of(new Finding.VerdictUnreviewableOverlap(
                new Decision.Classification(Path.of("a.jpg"), "junk", "blurred"))))
                .isEqualTo(FindingFamily.DECISIONS);
    }
}
