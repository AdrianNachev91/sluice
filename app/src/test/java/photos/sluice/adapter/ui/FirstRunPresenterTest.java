package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirstRunPresenterTest {

    @Test
    void everyRootUnsetLeavesFirstRunUnfinished() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.WORKING_ROOT),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.unfinished()).isTrue();
    }

    @Test
    void oneRootLeftUnsetAmongOthersChosenLeavesFirstRunUnfinished() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.unfinished()).isTrue();
    }

    @Test
    void usableRootsFinishFirstRun() {
        final var presenter = new FirstRunPresenter(violating());

        assertThat(presenter.unfinished()).isFalse();
    }

    @Test
    void aRootSetToSomethingUnusableDoesNotReopenFirstRun() {
        final var presenter = new FirstRunPresenter(violating(new NotAPath(PathRole.WORKING_ROOT, "not a path")));

        assertThat(presenter.unfinished()).isFalse();
    }

    @Test
    void twoRootsInsideEachOtherDoNotReopenFirstRun() {
        final var presenter = new FirstRunPresenter(violating(new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX)));

        assertThat(presenter.unfinished()).isFalse();
    }

    @Test
    void aSaveLeavingOneRootUnsetSaysWhichOne() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete(null).message())
                .isEqualTo("Saved. Sluice still needs your Inbox before it can start any work on your photos.");
    }

    @Test
    void aSaveLeavingTwoRootsUnsetNamesBoth() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.LIBRARY_ROOT), new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete(null).message()).isEqualTo(
                "Saved. Sluice still needs your Library root and Inbox before it can start any work "
                        + "on your photos.");
    }

    @Test
    void theRootsStillNeededAreNamedInTheOrderTheCardDrawsThem() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.INBOX),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.WORKING_ROOT)));

        assertThat(presenter.savedWhileStillIncomplete(null).message()).isEqualTo(
                "Sluice still needs your Working root, Library root and Inbox before it can start any "
                        + "work on your photos.");
    }

    // The broken root is in the list on purpose. It is not an unset one, so it neither holds the
    // card open nor earns a mention.
    @Test
    void aSaveThatSetsTheLastRootHasNothingLeftToAskFor() {
        final var presenter = new FirstRunPresenter(violating(new NotAPath(PathRole.INBOX, "?")));

        assertThat(presenter.savedWhileStillIncomplete(null)).isNull();
    }

    @Test
    void aCardOpenedPartWayThroughSaysWhatIsStillNeeded() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.opening())
                .isEqualTo("Sluice still needs your Inbox before it can start any work on your photos.");
    }

    @Test
    void aCardOpenedOnAFreshInstallSaysWhatTheCardIsFor() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.WORKING_ROOT),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.opening()).startsWith("Pick three folders");
    }

    @Test
    void aSaveThatStoredNoFolderAtAllDoesNotClaimToHaveSavedOne() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.WORKING_ROOT),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete(null).message()).doesNotContain("Saved.");
    }

    @Test
    void aSaveThatStoredNothingIsNotReportedAsGoodNews() {
        final var presenter = new FirstRunPresenter(violating(
                new NotConfigured(PathRole.WORKING_ROOT),
                new NotConfigured(PathRole.LIBRARY_ROOT),
                new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete(null).anythingWasStored()).isFalse();
    }

    @Test
    void aSaveThatStoredOneFolderIsReportedAsGoodNews() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete(null).anythingWasStored()).isTrue();
    }

    @Test
    void aSaveThatMovedTheLibraryIsGoodNewsEvenWithFoldersLeft() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete("Copied 12 file(s).").anythingWasStored()).isTrue();
    }

    @Test
    void aSaveThatReportedSomethingKeepsThatReportAndAddsWhatIsLeft() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.savedWhileStillIncomplete("Copied 12 file(s) into the new library.").message())
                .isEqualTo("Copied 12 file(s) into the new library. Sluice still needs your Inbox before it "
                        + "can start any work on your photos.");
    }

    @Test
    void theLibraryRefusalCoversChangingItRatherThanOnlyMovingIt() {
        final var presenter = new FirstRunPresenter(violating(new NotConfigured(PathRole.INBOX)));

        assertThat(presenter.libraryRootCannotMoveYet())
                .contains("cannot be changed")
                .doesNotContain("cannot move");
    }

    private static PathValidationUseCase violating(final PathViolation... violations) {
        return new FixedViolations(List.of(violations));
    }

    private record FixedViolations(List<PathViolation> violations) implements PathValidationUseCase {
        @Override
        public List<PathViolation> violations(final PathSettings paths) {
            return this.violations;
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return this.violations;
        }
    }
}
