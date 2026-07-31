package photos.sluice.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import photos.sluice.domain.cull.Decision;
import photos.sluice.domain.cull.Decision.Classification;
import photos.sluice.domain.cull.Decision.NearDupChosen;
import photos.sluice.domain.cull.Decision.NearDupReject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.application.service.CullPrepTestSupport.pathsConfig;

// Where a culled file ends up. Deciding what folder a decision or an unreviewable file belongs in
// is pure path arithmetic over the configured roots - no filesystem I/O. ApplyEngineTest and
// ReconcileEngineTest cover the real move/copy behavior these destinations feed into.
class CullDestinationsTest {

    @Test
    void aFunnyClassificationResolvesToTheLibrarysFunnyFolder(@TempDir final Path root) {
        final var destinations = new CullDestinations(pathsConfig(root, root.resolve("Library")));
        final var decision = new Classification(root.resolve("Sorted/Photos/2019/06/meme.jpg"), "funny", "haha");

        assertThat(destinations.destinationDirFor(decision)).isEqualTo(root.resolve("Library/Funny"));
    }

    @Test
    void aNonFunnyClassificationResolvesToItsReviewCategoryFolder(@TempDir final Path root) {
        final var destinations = new CullDestinations(pathsConfig(root, root.resolve("Library")));
        final var decision = new Classification(root.resolve("Sorted/Photos/2019/06/a.jpg"), "junk", "blurry");

        assertThat(destinations.destinationDirFor(decision)).isEqualTo(root.resolve("Review/junk"));
    }

    @Test
    void duplicatesDirDerivesYearMonthFromTheAnchorFileNotTheGroupsOtherMembers(@TempDir final Path root) {
        final var destinations = new CullDestinations(pathsConfig(root, root.resolve("Library")));
        final Path anchor = root.resolve("Sorted/Photos/2019/06/a.jpg");

        assertThat(destinations.duplicatesDir(anchor, "lake-jun19"))
                .isEqualTo(root.resolve("Duplicates/2019-06_lake-jun19"));
    }

    @Test
    void duplicatesDirFallsBackToAnUndatedMarkerWhenTheAnchorHasNoYearMonthParents(@TempDir final Path root) {
        final var destinations = new CullDestinations(pathsConfig(root, root.resolve("Library")));
        final Path anchor = root.resolve("a.jpg"); // no .../<yyyy>/<MM>/ parents at all

        assertThat(destinations.duplicatesDir(anchor, "some-group"))
                .isEqualTo(root.resolve("Duplicates/0000-00_some-group"));
    }

    @Test
    void unreviewableDirSplitsTheYearMonthIntoNestedFolders(@TempDir final Path root) {
        final var destinations = new CullDestinations(pathsConfig(root, root.resolve("Library")));
        final Path file = root.resolve("Sorted/Photos/2019/06/corrupt.heic");

        assertThat(destinations.unreviewableDir(file)).isEqualTo(root.resolve("Unreviewable/2019/06"));
    }

    @Test
    void candidateNameIsThePlainNameAtSlotOneAndAParentheticalCountAfter() {
        assertThat(CullDestinations.candidateName("a.jpg", 1)).isEqualTo("a.jpg");
        assertThat(CullDestinations.candidateName("a.jpg", 2)).isEqualTo("a (2).jpg");
        assertThat(CullDestinations.candidateName("a.jpg", 3)).isEqualTo("a (3).jpg");
    }

    @Test
    void candidateNameHandlesAnExtensionlessBaseName() {
        assertThat(CullDestinations.candidateName("README", 2)).isEqualTo("README (2)");
    }

    // Every group's rejects must resolve through the SAME anchor its own keeper does. Never through
    // their own file. nearDupAnchors() is where that anchor comes from - see
    // CullDestinations.duplicatesDir's own Javadoc for why.
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

        final Map<String, Path> anchors = CullDestinations.nearDupAnchors(decisions);

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

        assertThat(CullDestinations.nearDupAnchors(decisions)).containsExactly(Map.entry("lake-jun19", chosen));
    }
}
