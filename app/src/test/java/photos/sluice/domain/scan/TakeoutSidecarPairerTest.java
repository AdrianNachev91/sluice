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
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.someextra.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void fallsBackToDupNumberReversedPrefixWhenPlainPrefixDoesNotMatch() {
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

    // Each media file gets its own pair() call, and the sidecar is read back by filename string.
    // Path folds case on Windows even for values that never touch disk. A single call keyed by
    // both case variants, or an equals() assertion on the sidecar, would pass whether or not the
    // pairing told them apart.
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

    @Test
    void aSingleSidecarWhoseCasingDiffersFromItsMediaStillPairs() {
        final Path media = Path.of("dir/IMG_1234.JPG");
        final Path json = Path.of("dir/img_1234.jpg.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    // Which of the two the pairer picks is arbitrary, and fine: both name the identical photo.
    @Test
    void twoDifferentlySuffixedSidecarsForOnePhotoStillPairSomeSidecar() {
        final Path media = Path.of("dir/IMG_1234.jpg");
        final Path firstVariant = Path.of("dir/IMG_1234.jpg.json");
        final Path secondVariant = Path.of("dir/IMG_1234.jpg.supplemental-metadata.json");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(firstVariant, secondVariant));

        assertThat(result.sidecarsByMedia()).containsKey(media);
        assertThat(result.sidecarsByMedia().get(media)).isIn(firstVariant, secondVariant);
    }

    // Two pair() calls and a filename read-back, since Path folds case on Windows.
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
        final Path media = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata(1).json");

        assertThat(TakeoutSidecarPairer.ownerKeyOf(json)).isEqualTo("IMG_1234(1).jpg");

        final PairingResult result = this.pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void aDupNumberedSidecarBesideItsUnnumberedOriginalIsClaimedByBoth() {
        // Both owning it is deliberate: a sidecar is spent only once every owner has left.
        final Path original = Path.of("dir/IMG_1234.jpg");
        final Path numberedCopy = Path.of("dir/IMG_1234(1).jpg");
        final Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata(1).json");

        final PairingResult result = this.pairer.pair(List.of(original, numberedCopy), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(numberedCopy, json);
        assertThat(result.sidecarsByMedia()).containsEntry(original, json);
    }

    @Test
    void eachSupportedSidecarNamingShapeIsRecognizedAsDescribingAMediaFile() {
        // The sidecar naming shapes from this class's design doc, plus a video extension.
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
        assertThat(TakeoutSidecarPairer.looksLikeMediaSidecar(
                Path.of("dir/VeryLongOriginalPhotoFilenameFromGoogleExpo.json"))).isFalse();
    }

    private static String fileNameOf(final Path path) {
        return path.getFileName().toString();
    }
}
