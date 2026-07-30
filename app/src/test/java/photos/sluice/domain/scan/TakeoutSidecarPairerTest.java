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
}
