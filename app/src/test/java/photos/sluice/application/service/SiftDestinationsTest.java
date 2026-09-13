package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.sift.Decision;
import photos.sluice.domain.sift.Decision.Classification;
import photos.sluice.domain.sift.Decision.NearDupChosen;
import photos.sluice.domain.sift.Decision.NearDupReject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.application.service.SiftPrepTestSupport.pathsConfig;

// Path arithmetic only, no filesystem I/O. The real move and copy behaviour these destinations
// feed into is covered where those moves happen.
class SiftDestinationsTest {

    @Test
    void aFunnyClassificationResolvesToTheLibrarysFunnyFolder(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final var decision = new Classification(root.resolve("Sorted/Photos/2019/06/meme.jpg"), "funny", "haha");

        assertThat(destinations.destinationDirFor(decision)).isEqualTo(root.resolve("Library/Funny"));
    }

    @Test
    void aNonFunnyClassificationResolvesToItsReviewCategoryFolder(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final var decision = new Classification(root.resolve("Sorted/Photos/2019/06/a.jpg"), "junk", "blurry");

        assertThat(destinations.destinationDirFor(decision)).isEqualTo(root.resolve("Review/junk"));
    }

    @Test
    void duplicatesDirDerivesYearMonthFromTheAnchorFileNotTheGroupsOtherMembers(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final Path anchor = root.resolve("Sorted/Photos/2019/06/a.jpg");

        assertThat(destinations.duplicatesDir(anchor, "lake-jun19"))
                .isEqualTo(root.resolve("Duplicates/2019-06_lake-jun19"));
    }

    @Test
    void duplicatesDirFallsBackToAnUndatedMarkerWhenTheAnchorHasNoYearMonthParents(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final Path anchor = root.resolve("a.jpg"); // no .../<yyyy>/<MM>/ parents at all

        assertThat(destinations.duplicatesDir(anchor, "some-group"))
                .isEqualTo(root.resolve("Duplicates/0000-00_some-group"));
    }

    @Test
    void unreviewableDirSplitsTheYearMonthIntoNestedFolders(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final Path file = root.resolve("Sorted/Photos/2019/06/corrupt.heic");

        assertThat(destinations.unreviewableDir(file)).isEqualTo(root.resolve("Unreviewable/2019/06"));
    }

    @Test
    void aCategoryResolvingOutsideTheReviewRootIsRefused(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final var escaping = new Classification(root.resolve("Sorted/Photos/2019/06/a.jpg"),
                "../Photos/2019/06", "blurry");

        assertThatThrownBy(() -> destinations.destinationDirFor(escaping))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void aCategoryResolvingToTheReviewRootItselfIsRefused(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final var blank = new Classification(root.resolve("Sorted/Photos/2019/06/a.jpg"), "", "blurry");

        assertThatThrownBy(() -> destinations.destinationDirFor(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("itself")
                .hasMessageNotContaining("outside");
    }

    // Three parent references, not one. The group id is joined onto the year-month, so the first is
    // glued into a literal "2019-06_.." name and the second only pops that back off.
    @Test
    void aNearDupGroupResolvingOutsideTheDuplicatesRootIsRefused(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));
        final Path anchor = root.resolve("Sorted/Photos/2019/06/a.jpg");

        assertThat(destinations.duplicatesDir(anchor, "../../lake")).isEqualTo(root.resolve("Duplicates/lake"));
        assertThatThrownBy(() -> destinations.duplicatesDir(anchor, "../../../lake"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    // The unasserted first call is the control: a method refusing everything would fail there.
    @Test
    void aSourceOutsideTheSortedRootIsRefused(@TempDir final Path root) {
        final var destinations = new SiftDestinations(pathsConfig(root, root.resolve("Library")));

        destinations.requireUnderSorted(root.resolve("Sorted/Photos/2019/06/a.jpg"));
        assertThatThrownBy(() -> destinations.requireUnderSorted(root.resolve("Documents/taxes.pdf")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    // The nearest sibling root to Sorted, so the one an over-wide rule would most plausibly admit.
    @Test
    void aSourceInTheLibraryIsRefused(@TempDir final Path root) {
        final Path libraryRoot = root.resolve("Library");
        final var destinations = new SiftDestinations(pathsConfig(root, libraryRoot));

        assertThatThrownBy(() -> destinations.requireUnderSorted(libraryRoot.resolve("Photos/2019/06/a.jpg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    // The accepted call first, so the refusal below cannot pass against a method that refuses
    // everything.
    @Test
    void aPathOutsideTheLibraryIsRefusedAsLibraryContent(@TempDir final Path root) {
        final Path libraryRoot = root.resolve("Library");
        final var destinations = new SiftDestinations(pathsConfig(root, libraryRoot));

        destinations.requireUnderLibrary(libraryRoot.resolve("Funny/meme.jpg"));
        assertThatThrownBy(() -> destinations.requireUnderLibrary(root.resolve("Sorted/Photos/2019/06/meme.jpg")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void theLibraryRootItselfIsRefusedAsLibraryContent(@TempDir final Path root) {
        final Path libraryRoot = root.resolve("Library");
        final var destinations = new SiftDestinations(pathsConfig(root, libraryRoot));

        assertThatThrownBy(() -> destinations.requireUnderLibrary(libraryRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void candidateNameIsThePlainNameAtSlotOneAndAParentheticalCountAfter() {
        assertThat(SiftDestinations.candidateName("a.jpg", 1)).isEqualTo("a.jpg");
        assertThat(SiftDestinations.candidateName("a.jpg", 2)).isEqualTo("a (2).jpg");
        assertThat(SiftDestinations.candidateName("a.jpg", 3)).isEqualTo("a (3).jpg");
    }

    @Test
    void candidateNameHandlesAnExtensionlessBaseName() {
        assertThat(SiftDestinations.candidateName("README", 2)).isEqualTo("README (2)");
    }

    @Test
    void nearDupAnchorsMapsEachGroupToItsChosenKeepersFileRegardlessOfMonth() {
        final Path juneChosen = Path.of("Sorted/Photos/2019/06/a.jpg");
        final Path juneReject = Path.of("Sorted/Photos/2019/06/b.jpg");
        final Path julyChosen = Path.of("Sorted/Photos/2019/07/c.jpg");
        final Path julyReject = Path.of("Sorted/Photos/2019/08/d.jpg"); // a different month than its own keeper
        final List<Decision> decisions = List.of(
                new NearDupChosen(juneChosen, "lake-jun19", "sharpest"),
                new NearDupReject(juneReject, "lake-jun19", "blurred"),
                new NearDupChosen(julyChosen, "beach-jul19", "sharpest"),
                new NearDupReject(julyReject, "beach-jul19", "blurred"));

        final Map<String, Path> anchors = SiftDestinations.nearDupAnchors(decisions);

        assertThat(anchors).containsExactlyInAnyOrderEntriesOf(
                Map.of("lake-jun19", juneChosen, "beach-jul19", julyChosen));
    }

    @Test
    void nearDupAnchorsIgnoresClassificationsAndRejectsThemselves() {
        final Path chosen = Path.of("Sorted/Photos/2019/06/a.jpg");
        final List<Decision> decisions = List.of(
                new Classification(Path.of("Sorted/Photos/2019/06/x.jpg"), "junk", "blurry"),
                new NearDupChosen(chosen, "lake-jun19", "sharpest"),
                new NearDupReject(Path.of("Sorted/Photos/2019/06/b.jpg"), "lake-jun19", "blurred"));

        assertThat(SiftDestinations.nearDupAnchors(decisions)).containsExactly(Map.entry("lake-jun19", chosen));
    }
}
