package photos.sluice.domain.scan;

import org.junit.jupiter.api.Test;
import photos.sluice.domain.scan.TakeoutSidecarPairer.PairingResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TakeoutSidecarPairerTest {

    private final TakeoutSidecarPairer pairer = new TakeoutSidecarPairer();

    @Test
    void noJsonFilesMeansNotTakeoutMode() {
        final List<Path> media = List.of(Path.of("dir/IMG_1234.jpg"));

        final PairingResult result = this.pairer.pair(media, List.of());

        assertThat(result.takeoutMode()).isFalse();
        assertThat(result.sidecarsByMedia()).isEmpty();
    }

    @Test
    void anyJsonFileMeansTakeoutMode() {
        final List<Path> media = List.of(Path.of("dir/IMG_1234.jpg"));
        final List<Path> json = List.of(Path.of("dir/IMG_1234.jpg.json"));

        final PairingResult result = this.pairer.pair(media, json);

        assertThat(result.takeoutMode()).isTrue();
    }

    @Test
    void pairsSimpleMediaJsonMatch() {
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void pairsSupplementalMetadataSuffix() {
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void pairsReversedDupNumbering() {
        // Google numbers the sidecar outside the extension: media "IMG_1234(1).jpg" pairs with
        // sidecar "IMG_1234.jpg(1).json".
        final Path media = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg(1).json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void editedMediaSharesItsBaseSidecar() {
        final Path media = Path.of("dir/IMG_1234-edited.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void fallsBackToPrefixMatchWhenNoExactOwnerKeyMatches() {
        // When the sidecar's derived owner key doesn't exactly equal the media filename, a
        // sidecar whose base name starts with the media filename still pairs (a non-standard
        // sidecar suffix the owner-key derivation doesn't recognize).
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.someextra.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void fallsBackToDupNumberReversedPrefixWhenPlainPrefixDoesNotMatch() {
        // The media's plain filename ("IMG_1234(1).jpg") isn't a prefix of the sidecar's base
        // name; only the dup-number-reversed form ("IMG_1234.jpg(1)") is, so this exercises the
        // fallback's dup-numbering branch specifically (not the exact owner-key match, which
        // fails here because the sidecar's own suffix keeps its derived owner key unchanged).
        final Path media = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg(1).extra.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void prefixFallbackPrefersTheShortestMatchingSidecar() {
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path longerMatch = Path.of("dir/IMG_1234.jpg.aaaa.json");
        final Path shorterMatch = Path.of("dir/IMG_1234.jpg.a.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(longerMatch, shorterMatch));

        assertThat(result.sidecarsByMedia()).containsEntry(media, shorterMatch);
    }

    // Two genuinely distinct media files in one directory, differing only in case - possible only
    // on a case-sensitive filesystem. Each has its own correctly-cased sidecar. Without the
    // exact-case preference in bestOwnerMatch, both would silently pair to whichever sidecar
    // happened to claim the shared lowercased key first.
    //
    // Each media file is paired in its own pair() call against the same two sidecars, rather than
    // both together. The result is read back by filename string, not by Path equality. Path folds
    // case on Windows even for values that never touch disk. A single call with both case-variant
    // media as keys, or an equals()-based assertion on the sidecar value, would silently pass
    // regardless of whether the fix works. Pairing scoping only depends on the sidecar list,
    // which is identical across both calls, so this still reproduces the same ambiguity
    // bestOwnerMatch has to resolve.
    @Test
    void twoMediaFilesDifferingOnlyInCaseEachPairToTheirOwnSidecar() {
        final Path lower = Path.of("dir/photo.jpg");
        final Path upper = Path.of("dir/PHOTO.jpg");
        final Path lowerJson = Path.of("dir/photo.jpg.json");
        final Path upperJson = Path.of("dir/PHOTO.jpg.json");
        final List<Path> bothSidecars = List.of(lowerJson, upperJson);

        final PairingResult lowerResult = this.pairer.pair(List.of(lower), bothSidecars);
        final PairingResult upperResult = this.pairer.pair(List.of(upper), bothSidecars);

        assertThat(fileNameOf(lowerResult.sidecarsByMedia().get(lower))).isEqualTo("photo.jpg.json");
        assertThat(fileNameOf(upperResult.sidecarsByMedia().get(upper))).isEqualTo("PHOTO.jpg.json");
    }

    // A single sidecar whose own casing genuinely differs from its media's - Google's own export
    // casing is not always consistent. This is the tolerance bestOwnerMatch's lone-candidate
    // shortcut exists to preserve, distinct from the ambiguous multi-candidate case above.
    @Test
    void aSingleSidecarWhoseCasingDiffersFromItsMediaStillPairs() {
        final Path media = Path.of("dir/IMG_1234.JPG");
        final Path json = Path.of("dir/img_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    // Two sidecars naming the exact same media file, differing only in their own suffix. Both
    // candidates' raw owner keys equal the media filename exactly, so bestOwnerMatch's
    // exact-match loop finds a hit either way. Which one is arbitrary, and that is fine, since
    // both name the identical photo.
    @Test
    void twoDifferentlySuffixedSidecarsForOnePhotoStillPairSomeSidecar() {
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path firstVariant = Path.of("dir/IMG_1234.jpg.json");
        final Path secondVariant = Path.of("dir/IMG_1234.jpg.supplemental-metadata.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(firstVariant, secondVariant));

        assertThat(result.sidecarsByMedia()).containsKey(media);
        assertThat(result.sidecarsByMedia().get(media)).isIn(firstVariant, secondVariant);
    }

    // Same case-variant scenario as above, but routed through the prefix fallback rather than an
    // exact owner-key match. A non-standard suffix on both sidecars means ownerKeyOf doesn't
    // recognize either exactly, so this exercises shortestStartingWith's own exact-case
    // preference instead of bestOwnerMatch's. Two separate pair() calls and a filename-string
    // read-back, for the same Windows Path-equality reason as the test above.
    @Test
    void prefixFallbackPrefersTheExactCaseMatchOverACaseFoldedOne() {
        final Path lower = Path.of("dir/photo.jpg");
        final Path upper = Path.of("dir/PHOTO.jpg");
        final Path lowerJson = Path.of("dir/photo.jpg.someextra.json");
        final Path upperJson = Path.of("dir/PHOTO.jpg.someextra.json");
        final List<Path> bothSidecars = List.of(lowerJson, upperJson);

        final PairingResult lowerResult = this.pairer.pair(List.of(lower), bothSidecars);
        final PairingResult upperResult = this.pairer.pair(List.of(upper), bothSidecars);

        assertThat(fileNameOf(lowerResult.sidecarsByMedia().get(lower))).isEqualTo("photo.jpg.someextra.json");
        assertThat(fileNameOf(upperResult.sidecarsByMedia().get(upper))).isEqualTo("PHOTO.jpg.someextra.json");
    }

    @Test
    void unmatchedMediaFallsThroughUnpaired() {
        final Path media = Path.of("dir/IMG_5678.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).doesNotContainKey(media);
    }

    @Test
    void sameOwnerNameInDifferentDirectoriesDoesNotCrossPair() {
        final Path mediaInDirA = Path.of("dirA/IMG_1234.jpg");
        final Path jsonInDirB = Path.of("dirB/IMG_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(mediaInDirA), List.of(jsonInDirB));

        assertThat(result.sidecarsByMedia()).doesNotContainKey(mediaInDirA);
    }

    @Test
    void rootLevelPathsWithNoParentDoNotThrow() {
        final Path media = Path.of("IMG_1234.jpg");
        final Path json = Path.of("IMG_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.takeoutMode()).isTrue();
        assertThat(result.sidecarsByMedia()).doesNotContainKey(media);
    }

    @Test
    void aDupNumberedSupplementalSidecarNamesTheNumberedMediaFile() {
        // Google puts the duplicate counter at the very end of the sidecar's name, after the
        // supplemental suffix. The media file it describes carries it before its extension.
        final Path media = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata(1).json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).isEqualTo("IMG_1234(1).jpg");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void aDupNumberedSidecarBesideItsUnnumberedOriginalIsClaimedByBoth() {
        // The numbered copy matches on the owner key. The original still reaches the same sidecar
        // through the prefix fallback, since the sidecar's base name starts with its filename.
        // Both owning it is the safe outcome: the sweep keeps a sidecar until every owner has left.
        final Path original = Path.of("dir/IMG_1234.jpg");
        final Path numberedCopy = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata(1).json");

        final PairingResult result = this.pairer.pair(List.of(original, numberedCopy), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(numberedCopy, json);
        assertThat(result.sidecarsByMedia()).containsEntry(original, json);
    }

    @Test
    void eachSupportedSidecarNamingShapeIsRecognizedAsDescribingAMediaFile() {
        // One per row of the naming table in this class's design doc, plus a video extension.
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/IMG_1234.jpg.json"))).isTrue();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(
                Path.of("dir/IMG_1234.jpg.supplemental-metadata.json"))).isTrue();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/IMG_1234.jpg(1).json"))).isTrue();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/IMG_1234.jpg.someextra.json"))).isTrue();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/IMG_1234.jpg(1).extra.json"))).isTrue();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/clip.MP4.json"))).isTrue();
    }

    @Test
    void aJsonWhoseOwnerKeyCarriesNoMediaExtensionDescribesNoMediaFile() {
        // Real Google Takeout exports ship all three of these, none of them a per-photo sidecar.
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/metadata.json"))).isFalse();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/print-subscriptions.json"))).isFalse();
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/user-generated-memory-titles.json")))
                .isFalse();
        // A name-shaped .json whose trailing component is not a media extension.
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(Path.of("dir/export.v2.json"))).isFalse();
    }

    @Test
    void aTruncatedSidecarNameThatLostItsMediaExtensionDescribesNoMediaFile() {
        // Google truncates a long sidecar name. Once the cut eats past the media extension, the
        // owner key stops naming a media file and the sweep has to leave the JSON alone.
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(
                Path.of("dir/VeryLongOriginalPhotoFilenameFromGoogleExpo.json"))).isFalse();
    }

    private static String fileNameOf(final Path path) {
        return path.getFileName().toString();
    }
}
