package photos.sluice.domain.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarSweepTest {

    private final SidecarSweep sweep = new SidecarSweep();

    @Test
    void sidecarWhoseMediaStillExistsInSameDirIsKept() {
        Path media = Path.of("Inbox", "2019-06", "photo1.jpg");
        Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).isEmpty();
    }

    @Test
    void sidecarWhoseMediaIsGoneIsOrphaned() {
        Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(sweep.findOrphaned(List.of(), List.of(json))).containsExactly(json);
    }

    @Test
    void sidecarInADifferentDirectoryFromASameNamedMediaFileIsStillOrphaned() {
        Path media = Path.of("Inbox", "2020-07", "photo1.jpg");
        Path json = Path.of("Inbox", "2019-06", "photo1.jpg.json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).containsExactly(json);
    }

    @Test
    void truncatedSidecarNameIsKeptWhenALongPrefixMatchingMediaFileRemains() {
        // Google truncates long sidecar names, so the owner key it derives can be a strict prefix
        // of the real (longer) media filename. Built from a shared base so the prefix
        // relationship holds by construction rather than by hand-counted characters. 48 chars
        // clears SidecarSweep's 46-char truncation-plausibility floor.
        String longBase = "VeryLongOriginalPhotoFilenameFromGoogleExportedAlbum";
        Path media = Path.of("Inbox", longBase + ".jpg");
        Path json = Path.of("Inbox", longBase.substring(0, 48) + ".json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).isEmpty();
    }

    @Test
    void prefixJustBelowTheTruncationFloorIsNotTrustedEvenThoughItIsAGenuinePrefix() {
        // Same shared base as above, but a 45-char prefix - one character short of the 46-char
        // floor. Still a real prefix of the media name, but not long enough to be trusted as
        // truncation evidence, so it's rejected. Pins the boundary itself, not just either side.
        String longBase = "VeryLongOriginalPhotoFilenameFromGoogleExportedAlbum";
        Path media = Path.of("Inbox", longBase + ".jpg");
        Path json = Path.of("Inbox", longBase.substring(0, 45) + ".json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).containsExactly(json);
    }

    @Test
    void shortAccidentalPrefixCollisionIsNotTreatedAsATruncationMatch() {
        // "img" happening to be a literal prefix of an unrelated "img_vacation.jpg" is coincidence,
        // not evidence of Google's truncation - only a long owner key earns the prefix-match trust.
        Path unrelatedMedia = Path.of("Inbox", "img_vacation.jpg");
        Path json = Path.of("Inbox", "img.json");

        assertThat(sweep.findOrphaned(List.of(unrelatedMedia), List.of(json))).containsExactly(json);
    }

    @Test
    void ownerMatchIsCaseInsensitive() {
        Path media = Path.of("Inbox", "PHOTO1.JPG");
        Path json = Path.of("Inbox", "photo1.jpg.json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).isEmpty();
    }

    @Test
    void supplementalMetadataSuffixIsStrippedToDeriveTheOwner() {
        Path media = Path.of("Inbox", "photo1.jpg");
        Path json = Path.of("Inbox", "photo1.jpg.supplemental-metadata.json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).isEmpty();
        assertThat(sweep.findOrphaned(List.of(), List.of(json))).containsExactly(json);
    }

    @Test
    void dupNumberedSidecarSuffixIsReversedToDeriveTheOwner() {
        // Sidecar base "photo1.jpg(1)" describes media "photo1(1).jpg".
        Path media = Path.of("Inbox", "photo1(1).jpg");
        Path json = Path.of("Inbox", "photo1.jpg(1).json");

        assertThat(sweep.findOrphaned(List.of(media), List.of(json))).isEmpty();
    }

    @Test
    void multipleSidecarsAreEvaluatedIndependently() {
        Path keptMedia = Path.of("Inbox", "keep.jpg");
        Path keptJson = Path.of("Inbox", "keep.jpg.json");
        Path orphanedJson = Path.of("Inbox", "gone.jpg.json");

        assertThat(sweep.findOrphaned(List.of(keptMedia), List.of(keptJson, orphanedJson)))
                .containsExactly(orphanedJson);
    }
}
