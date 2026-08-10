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
        // The reverse-direction prefix case. The pairer matches this pair through its prefix
        // fallback, because the sidecar's base name starts with the media filename. The owner key
        // it derives is longer than the media name, so no name comparison here could recover the
        // relationship. Only the pairing itself knows.
        final Path media = Path.of("Inbox", "IMG_1234.jpg");
        final Path json = Path.of("Inbox", "IMG_1234.jpg.someextra.json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).isEqualTo("IMG_1234.jpg.someextra");
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(json)).isTrue();
        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of(media, json))).isEmpty();
    }

    @Test
    void sharedSidecarIsKeptWhileOneOfItsTwoOwnersRemains() {
        // An "-edited" copy has no sidecar of its own and pairs to the original's. If the original
        // leaves first, nothing left in the directory is named after the owner key. Only the
        // pairing keeps the JSON alive for the copy still waiting.
        final Path editedCopy = Path.of("Inbox", "photo1-edited.jpg");
        final Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(editedCopy), List.of(json), Map.of(editedCopy, json))).isEmpty();
    }

    @Test
    void aJsonThatNeverNamedAMediaFileIsLeftAloneEvenWithNoMediaAroundIt() {
        // A Takeout album descriptor. Its owner key is "metadata", which carries no media
        // extension, so it could never have been a per-photo sidecar. The same holds for any
        // unrelated .json a user's dump happens to carry.
        final Path albumDescriptor = Path.of("Inbox", "Album", "metadata.json");
        final Path unrelated = Path.of("Inbox", "Album", "notes.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(albumDescriptor, unrelated), Map.of())).isEmpty();
    }

    @Test
    void aSidecarShapedJsonIsStillSweptWhenNothingOwnsIt() {
        // The counterpart to the case above: "gone.jpg" is a media filename, so this one really is
        // a spent sidecar rather than an unrelated file.
        final Path json = Path.of("Inbox", "Album", "gone.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void anOwnerKeyThatIsMerelyAPrefixOfAnUnrelatedMediaNameIsStillOrphaned() {
        // "img.jpg" happening to be a literal prefix of an unrelated "img.jpg.backup.jpg" is
        // coincidence, not ownership. Only an exact owner-key match counts.
        final Path unrelatedMedia = Path.of("Inbox", "img.jpg.backup.jpg");
        final Path json = Path.of("Inbox", "img.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(unrelatedMedia), List.of(json), Map.of()))
                .containsExactly(json);
    }

    @Test
    void aSidecarWithANonStandardSuffixIsSweptOnceItsMediaHasGone() {
        // The prefix-fallback shape. Its media extension sits in the middle of the owner key
        // rather than at the end. It is still a real sidecar, so nothing should make it immortal.
        final Path json = Path.of("Inbox", "IMG_1234.jpg.someextra.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void aTruncatedSidecarIsKeptWhileTheLongerNamedMediaItWasCutFromRemains() {
        // The real shape, taken from an export. Google cut the name at the truncation length,
        // losing the media extension along with the rest. Only the length makes the prefix
        // trustworthy. The pairer cannot see this pair at all, since it matches a sidecar name
        // starting with a media name, and here it is the other way round.
        final Path media = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000d.jpg");
        final Path json = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000.json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).hasSize(SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH);
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(json)).isFalse();
        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aTruncatedSidecarIsSweptOnceThatMediaHasGone() {
        // The counterpart. Reaching the truncation length is what tells a cut sidecar name apart
        // from an album descriptor, so this one is spent rather than immortal.
        final Path json = Path.of("Inbox", "Screenshot_2019-01-01-00-00-00-00_000000000000.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).containsExactly(json);
    }

    @Test
    void aNameOneShortOfTheTruncationLengthIsReadAsNeverHavingBeenASidecar() {
        // Pins the boundary. At this length the name is too short to read as something Google cut.
        // It counts as an unrelated .json and is left alone, even with no media around it.
        final String shortOfTheLength = "Screenshot_2019-01-01-00-00-00-00_00000000000";
        final Path json = Path.of("Inbox", shortOfTheLength + ".json");

        assertThat(shortOfTheLength).hasSize(SidecarSweep.MIN_TRUNCATED_OWNER_KEY_LENGTH - 1);
        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aDupNumberedSupplementalSidecarIsKeptWhileItsNumberedMediaFileRemains() {
        // Google puts the duplicate counter at the very end of the sidecar's name, after the
        // supplemental suffix, while the media file carries it before its extension.
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
        // Sidecar base "photo1.jpg(1)" describes media "photo1(1).jpg".
        final Path media = Path.of("Inbox", "photo1(1).jpg");
        final Path json = Path.of("Inbox", "photo1.jpg(1).json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(json), Map.of())).isEmpty();
    }

    @Test
    void aSecondSidecarDerivingAnAlreadyClaimedOwnerKeyIsKeptWhileThatMediaFileRemains() {
        // Pairing awards a media file exactly one sidecar, so a second one deriving the same owner
        // key stays unpaired. It still describes a file sitting right there, so the name match has
        // to keep it.
        final Path media = Path.of("Inbox", "photo1.jpg");
        final Path paired = Path.of("Inbox", "photo1.jpg.json");
        final Path unpairedTwin = Path.of("Inbox", "photo1.jpg.supplemental-metadata.json");

        assertThat(this.sweep.findOrphaned(List.of(media), List.of(paired, unpairedTwin), Map.of(media, paired)))
                .isEmpty();
    }

    // Two genuinely distinct media files differ only in case, both still present. A third,
    // already-unpaired sidecar's owner key shares their lowercased form but matches neither
    // exactly - its own media departed separately. Only a lone remaining candidate gets the
    // lowercased tolerance; with two, only an exact match keeps a sidecar alive.
    @Test
    void aSidecarMatchingNeitherOfTwoCaseVariantSiblingsExactlyIsOrphaned() {
        final Path lowerMedia = Path.of("Inbox", "photo.jpg");
        final Path upperMedia = Path.of("Inbox", "PHOTO.jpg");
        final Path orphanJson = Path.of("Inbox", "Photo.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(lowerMedia, upperMedia), List.of(orphanJson), Map.of()))
                .containsExactly(orphanJson);
    }

    // The counterpart: among the same two siblings, a sidecar whose owner key matches one of them
    // exactly is still recognized. It doesn't need a live pairing to tell the sweep so directly.
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
        // The pairing spans the whole scan, including files this run moved out. Only the media
        // still on disk is allowed to vote.
        final Path departed = Path.of("Inbox", "photo1.jpg");
        final Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(this.sweep.findOrphaned(List.of(), List.of(json), Map.of(departed, json)))
                .containsExactly(json);
    }
}
