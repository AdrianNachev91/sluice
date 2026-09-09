package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.cull.Finding.CorruptIndex;
import photos.sluice.domain.cull.Finding.CorruptShard;
import photos.sluice.domain.cull.Finding.CorruptSidecar;
import photos.sluice.domain.cull.Finding.DuplicateFileReference;
import photos.sluice.domain.cull.Finding.FileOutOfScope;
import photos.sluice.domain.cull.Finding.InvalidGroupSlug;
import photos.sluice.domain.cull.Finding.MissingChosenReason;
import photos.sluice.domain.cull.Finding.MissingGroup;
import photos.sluice.domain.cull.Finding.MissingMontageField;
import photos.sluice.domain.cull.Finding.MissingReason;
import photos.sluice.domain.cull.Finding.MissingShard;
import photos.sluice.domain.cull.Finding.MissingSource;
import photos.sluice.domain.cull.Finding.PhotoFromAnotherSheet;
import photos.sluice.domain.cull.Finding.PhotosNotJudged;
import photos.sluice.domain.cull.Finding.SourceOutsideSorted;
import photos.sluice.domain.cull.Finding.StrayShard;
import photos.sluice.domain.cull.Finding.TooFewRejects;
import photos.sluice.domain.cull.Finding.UnreadablePrepDir;
import photos.sluice.domain.cull.Finding.WrongChosenCount;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Proves describe() renders the exact prose an aggregated ApplyException reports. The shapes here
// and the ones ShardValidatorTest renders are disjoint, so none is checked twice.
class FindingTest {

    // Paths are built into the expected strings rather than written out, since their rendering
    // differs between the two platforms CI runs on.
    @Test
    void describeRendersTheExactProseForEachRemainingFindingShape() {
        final var file = Path.of("Sorted", "Photos", "2019", "06", "a.jpg");
        final var sortedRoot = Path.of("Sorted");
        final var indexPath = Path.of("sift-prep", "2019-06", "index.json");
        final var moveLog = Path.of("sift-prep", "2019-06", "move-records.log");

        assertThat(new MissingMontageField("montage-001").describe())
                .isEqualTo("montage-001: missing 'montage'");
        assertThat(new MissingReason("montage-001", 4).describe())
                .isEqualTo("montage-001[#4]: missing 'reason'");
        assertThat(new FileOutOfScope("montage-001", 5, file).describe())
                .isEqualTo("montage-001[#5]: file out of scope: " + file);
        assertThat(new StrayShard("decisions-009.json").describe())
                .isEqualTo("decisions-009.json: no matching montage");
        assertThat(new MissingShard("montage-002", "decisions-002.json").describe())
                .isEqualTo("montage-002: no shard decisions-002.json");
        assertThat(new CorruptIndex(indexPath).describe())
                .isEqualTo(indexPath + ": corrupt or unreadable index.json");
        assertThat(new MissingSource(file, moveLog).describe())
                .isEqualTo("file not found, and its move could not be verified: " + file
                        + " - if an earlier, crashed sift already applied it, the automatic check that would confirm"
                        + " that (a move record matching this file, whose recorded destination still hash-verifies)"
                        + " found none. This needs manual investigation before re-running; see " + moveLog + ".");
        assertThat(new MissingGroup("montage-001", 1).describe())
                .isEqualTo("montage-001[#1]: missing 'group'");
        assertThat(new MissingChosenReason("montage-001", 2).describe())
                .isEqualTo("montage-001[#2]: missing 'chosen_reason'");
        assertThat(new WrongChosenCount("montage-001", "g1", 2).describe())
                .isEqualTo("montage-001: near-dup group 'g1' has 2 chosen (need exactly 1)");
        assertThat(new TooFewRejects("montage-001", "g1", 0).describe())
                .isEqualTo("montage-001: near-dup group 'g1' has 0 reject(s) (need >=1)");
        assertThat(new InvalidGroupSlug("montage-001", "Bad_Slug", 32).describe())
                .isEqualTo("montage-001: near-dup group 'Bad_Slug' is not a valid slug "
                        + "(lowercase a-z0-9, hyphenated, max 32 chars)");
        assertThat(new DuplicateFileReference("a.jpg", 3).describe())
                .isEqualTo("file listed 3 times across shards/unreviewable: a.jpg");
        assertThat(new SourceOutsideSorted(file, sortedRoot).describe())
                .isEqualTo("file outside " + sortedRoot + ", the only place photos may be taken from: " + file);
    }

    @Test
    void sourceOutsideSortedNamesTheRootPlainlyWhenTheRootItselfIsTheOffender() {
        final var sortedRoot = Path.of("Sorted");

        assertThat(new SourceOutsideSorted(sortedRoot, sortedRoot).describe())
                .isEqualTo("the Sorted root itself is named as a file to act on: " + sortedRoot);
    }

    @Test
    void sourceOutsideSortedOffersNoRemedyBecauseNoEngineCanTellWhichFileWasMeant() {
        assertThat(new SourceOutsideSorted(Path.of("a.jpg"), Path.of("Sorted")).remedy())
                .isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void corruptSidecarDescribesTheMontageAndCarriesTheChoiceRemedy() {
        final var finding = new CorruptSidecar("montage-001");

        assertThat(finding.describe()).isEqualTo("montage-001: sidecar unreadable or missing");
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.CHOICE);
    }

    @Test
    void corruptShardNamesTheShardFileAndOffersNoRemedy() {
        final var finding = new CorruptShard("montage-001", "decisions-001.json");

        assertThat(finding.describe()).isEqualTo("montage-001: shard decisions-001.json is unreadable");
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void unreadablePrepDirNamesTheDirAndOffersNoRemedy() {
        final var prepDir = Path.of("sift-prep", "2019");
        final var finding = new UnreadablePrepDir(prepDir);

        assertThat(finding.describe())
                .isEqualTo(prepDir + ": could not be read far enough to diagnose");
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void photosNotJudgedCountsThemAndNamesEveryOne() {
        final var finding = new PhotosNotJudged("montage-007", List.of("IMG_1.jpg", "IMG_2.jpg"));

        assertThat(finding.describe())
                .isEqualTo("montage-007: no verdict for 2 of its photos (IMG_1.jpg, IMG_2.jpg)");
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.NONE);
    }

    @Test
    void photoFromAnotherSheetNamesTheTileItSatIn() {
        final var file = Path.of("Sorted", "Photos", "2019", "06", "IMG_9.jpg");
        final var finding = new PhotoFromAnotherSheet("montage-008", 4, file);

        assertThat(finding.describe())
                .isEqualTo("montage-008[#4]: names a photo another sheet showed: " + file);
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.NONE);
    }
}
