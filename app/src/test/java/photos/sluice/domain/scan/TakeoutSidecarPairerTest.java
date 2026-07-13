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
        List<Path> media = List.of(Path.of("dir/IMG_1234.jpg"));

        PairingResult result = pairer.pair(media, List.of());

        assertThat(result.takeoutMode()).isFalse();
        assertThat(result.sidecarsByMedia()).isEmpty();
    }

    @Test
    void anyJsonFileMeansTakeoutMode() {
        List<Path> media = List.of(Path.of("dir/IMG_1234.jpg"));
        List<Path> json = List.of(Path.of("dir/IMG_1234.jpg.json"));

        PairingResult result = pairer.pair(media, json);

        assertThat(result.takeoutMode()).isTrue();
    }

    @Test
    void pairsSimpleMediaJsonMatch() {
        Path media = Path.of("dir/IMG_1234.jpg");
        Path json = Path.of("dir/IMG_1234.jpg.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void pairsSupplementalMetadataSuffix() {
        Path media = Path.of("dir/IMG_1234.jpg");
        Path json = Path.of("dir/IMG_1234.jpg.supplemental-metadata.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void pairsReversedDupNumbering() {
        // Google numbers the sidecar outside the extension: media "IMG_1234(1).jpg" pairs with
        // sidecar "IMG_1234.jpg(1).json".
        Path media = Path.of("dir/IMG_1234(1).jpg");
        Path json = Path.of("dir/IMG_1234.jpg(1).json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void editedMediaSharesItsBaseSidecar() {
        Path media = Path.of("dir/IMG_1234-edited.jpg");
        Path json = Path.of("dir/IMG_1234.jpg.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void fallsBackToPrefixMatchWhenNoExactOwnerKeyMatches() {
        // When the sidecar's derived owner key doesn't exactly equal the media filename, a
        // sidecar whose base name starts with the media filename still pairs (a non-standard
        // sidecar suffix the owner-key derivation doesn't recognize).
        Path media = Path.of("dir/IMG_1234.jpg");
        Path json = Path.of("dir/IMG_1234.jpg.someextra.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void fallsBackToDupNumberReversedPrefixWhenPlainPrefixDoesNotMatch() {
        // The media's plain filename ("IMG_1234(1).jpg") isn't a prefix of the sidecar's base
        // name; only the dup-number-reversed form ("IMG_1234.jpg(1)") is, so this exercises the
        // fallback's dup-numbering branch specifically (not the exact owner-key match, which
        // fails here because the sidecar's own suffix keeps its derived owner key unchanged).
        Path media = Path.of("dir/IMG_1234(1).jpg");
        Path json = Path.of("dir/IMG_1234.jpg(1).extra.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).containsEntry(media, json);
    }

    @Test
    void prefixFallbackPrefersTheShortestMatchingSidecar() {
        Path media = Path.of("dir/IMG_1234.jpg");
        Path longerMatch = Path.of("dir/IMG_1234.jpg.aaaa.json");
        Path shorterMatch = Path.of("dir/IMG_1234.jpg.a.json");

        PairingResult result = pairer.pair(List.of(media), List.of(longerMatch, shorterMatch));

        assertThat(result.sidecarsByMedia()).containsEntry(media, shorterMatch);
    }

    @Test
    void unmatchedMediaFallsThroughUnpaired() {
        Path media = Path.of("dir/IMG_5678.jpg");
        Path json = Path.of("dir/IMG_1234.jpg.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.sidecarsByMedia()).doesNotContainKey(media);
    }

    @Test
    void sameOwnerNameInDifferentDirectoriesDoesNotCrossPair() {
        Path mediaInDirA = Path.of("dirA/IMG_1234.jpg");
        Path jsonInDirB = Path.of("dirB/IMG_1234.jpg.json");

        PairingResult result = pairer.pair(List.of(mediaInDirA), List.of(jsonInDirB));

        assertThat(result.sidecarsByMedia()).doesNotContainKey(mediaInDirA);
    }

    @Test
    void rootLevelPathsWithNoParentDoNotThrow() {
        Path media = Path.of("IMG_1234.jpg");
        Path json = Path.of("IMG_1234.jpg.json");

        PairingResult result = pairer.pair(List.of(media), List.of(json));

        assertThat(result.takeoutMode()).isTrue();
        assertThat(result.sidecarsByMedia()).doesNotContainKey(media);
    }
}
