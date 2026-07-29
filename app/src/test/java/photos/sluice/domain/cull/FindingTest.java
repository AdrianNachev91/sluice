package photos.sluice.domain.cull;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.cull.Finding.CorruptSidecar;
import photos.sluice.domain.cull.Finding.DuplicateFileReference;
import photos.sluice.domain.cull.Finding.InvalidGroupSlug;
import photos.sluice.domain.cull.Finding.MissingChosenReason;
import photos.sluice.domain.cull.Finding.MissingGroup;
import photos.sluice.domain.cull.Finding.MissingMontageField;
import photos.sluice.domain.cull.Finding.TooFewRejects;
import photos.sluice.domain.cull.Finding.WrongChosenCount;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves describe() for every {@link Finding} case that {@link ShardValidatorTest}'s own
 * describe() sample deliberately leaves out. Between the two, every one of Finding's shapes has
 * its rendered prose checked at least once.
 */
class FindingTest {

    @Test
    void describeRendersEveryRemainingFindingShapesExactProse() {
        assertThat(new MissingMontageField("montage-001").describe())
                .isEqualTo("montage-001: missing 'montage'");
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
    }

    @Test
    void corruptSidecarDescribesTheMontageAndCarriesTheChoiceRemedy() {
        var finding = new CorruptSidecar("montage-001");

        assertThat(finding.describe()).isEqualTo("montage-001: sidecar unreadable or missing");
        assertThat(finding.remedy()).isEqualTo(Finding.Remedy.CHOICE);
    }
}
