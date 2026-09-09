package photos.sluice.domain.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarSweepTest {

    private final SidecarSweep sweep = new SidecarSweep();

    @Test
    void sidecarWhoseMediaStillExistsInSameDirIsKept() {
        final Path media = Path.of("Inbox", "2019-06", "photo1.jpg");
        final Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of(media, json))).isEmpty();
    }

    @Test
    void sidecarWhoseMediaIsGoneIsOrphaned() {
        final Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void sidecarInADifferentDirectoryFromASameNamedMediaFileIsStillOrphaned() {
        final Path media = Path.of("Inbox", "2020-07", "photo1.jpg");
        final Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void sidecarPairedToARemainingMediaFileIsKeptEvenThoughItsOwnerKeyMatchesNothing() {
        final Path media = Path.of("Inbox", "IMG_1234.jpg");
        final Path json = Path.of("Inbox", "IMG_1234.jpg.someextra.json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).isEqualTo("IMG_1234.jpg.someextra");
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(json)).isTrue();
        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of(media, json))).isEmpty();
    }

    @Test
    void sharedSidecarIsKeptWhileOneOfItsTwoOwnersRemains() {
        final Path editedCopy = Path.of("Inbox", "photo1-edited.jpg");
        final Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(editedCopy), List.of(json), Map.of(editedCopy, json))).isEmpty();
    }

    @Test
    void aJsonThatNeverNamedAMediaFileIsLeftAloneEvenWithNoMediaAroundIt() {
        final Path albumDescriptor = Path.of("Inbox", "Album", "metadata.json");
        final Path unrelated = Path.of("Inbox", "Album", "notes.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(albumDescriptor, unrelated), Map.of())).isEmpty();
    }

    @Test
    void aSidecarShapedJsonIsStillSweptWhenNothingOwnsIt() {
        final Path json = Path.of("Inbox", "Album", "gone.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void anOwnerKeyThatIsMerelyAPrefixOfAnUnrelatedMediaNameIsStillOrphaned() {
        final Path unrelatedMedia = Path.of("Inbox", "img.jpg.backup.jpg");
        final Path json = Path.of("Inbox", "img.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(unrelatedMedia), List.of(json), Map.of()))
                .containsExactly(json);
    }

    @Test
    void aSidecarWithANonStandardSuffixIsSweptOnceItsMediaHasGone() {
        final Path json = Path.of("Inbox", "IMG_1234.jpg.someextra.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void aTruncatedSidecarIsKeptWhileTheLongerNamedMediaItWasCutFromRemains() {
        final Path media = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000d.jpg");
        final Path json = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000.json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).hasSize(SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH);
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(json)).isFalse();
        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aTruncatedSidecarIsSweptOnceThatMediaHasGone() {
        final Path json = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void aNameOneShortOfTheTruncationLengthIsReadAsNeverHavingBeenASidecar() {
        final String shortOfTheLength = "Screenshot_2019-01-01-00-00-00-00_00000000000";
        final Path json = Path.of("Inbox", shortOfTheLength + ".json");

        assertThat(shortOfTheLength).hasSize(SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH - 1);
        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aDupNumberedSupplementalSidecarIsKeptWhileItsNumberedMediaFileRemains() {
        final Path media = Path.of("Inbox", "IMG_1234(1).jpg");
        final Path json = Path.of("Inbox", "IMG_1234.jpg.supplemental-metadata(1).json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).isEqualTo("IMG_1234(1).jpg");
        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void ownerMatchIsCaseInsensitive() {
        final Path media = Path.of("Inbox", "PHOTO1.JPG");
        final Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void supplementalMetadataSuffixIsStrippedToDeriveTheOwner() {
        final Path media = Path.of("Inbox", "photo1.jpg");
        final Path json = Path.of("Inbox", "photo1.jpg.supplemental-metadata.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void dupNumberedSidecarSuffixIsReversedToDeriveTheOwner() {
        final Path media = Path.of("Inbox", "photo1(1).jpg");
        final Path json = Path.of("Inbox", "photo1.jpg(1).json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aSecondSidecarDerivingAnAlreadyClaimedOwnerKeyIsKeptWhileThatMediaFileRemains() {
        final Path media = Path.of("Inbox", "photo1.jpg");
        final Path paired = Path.of("Inbox", "photo1.jpg.json");
        final Path unpairedTwin = Path.of("Inbox", "photo1.jpg.supplemental-metadata.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(paired, unpairedTwin), Map.of(media, paired)))
                .isEmpty();
    }

    @Test
    void aSidecarMatchingNeitherOfTwoCaseVariantSiblingsExactlyIsOrphaned() {
        final Path lowerMedia = Path.of("Inbox", "photo.jpg");
        final Path upperMedia = Path.of("Inbox", "PHOTO.jpg");
        final Path orphanJson = Path.of("Inbox", "Photo.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(lowerMedia, upperMedia), List.of(orphanJson), Map.of()))
                .containsExactly(orphanJson);
    }

    @Test
    void aSidecarMatchingOneOfTwoCaseVariantSiblingsExactlyIsKeptEvenWithoutALivePairing() {
        final Path lowerMedia = Path.of("Inbox", "photo.jpg");
        final Path upperMedia = Path.of("Inbox", "PHOTO.jpg");
        final Path json = Path.of("Inbox", "PHOTO.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(lowerMedia, upperMedia), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void multipleSidecarsAreEvaluatedIndependently() {
        final Path keptMedia = Path.of("Inbox", "keep.jpg");
        final Path keptJson = Path.of("Inbox", "keep.jpg.json");
        final Path orphanedJson = Path.of("Inbox", "gone.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(keptMedia), List.of(keptJson, orphanedJson),
                Map.of(keptMedia, keptJson))).containsExactly(orphanedJson);
    }

    @Test
    void aPairingEntryForMediaThatHasAlreadyLeftDoesNotKeepItsSidecar() {
        final Path departed = Path.of("Inbox", "photo1.jpg");
        final Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of(departed, json)))
                .containsExactly(json);
    }
}
