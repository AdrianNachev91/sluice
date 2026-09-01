package photos.sluice.adapter.ui;

import org.junit.jupiter.api.Test;
import photos.sluice.adapter.ui.RunProgressView.PhaseBar;
import photos.sluice.domain.imports.ImportKind;

import static org.assertj.core.api.Assertions.assertThat;

class RunProgressPresenterTest {

    private final FxProgressPort port = new FxProgressPort(Runnable::run);

    private final RunProgressPresenter presenter = new RunProgressPresenter(this.port);

    @Test
    void aPhaseThatHasReportedNoTotalDrawsWithoutAFraction() {
        this.port.phaseStarted("Sorting");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::label, PhaseBar::measured, PhaseBar::counts)
                .containsExactly("Sorting", false, null);
    }

    @Test
    void aPhaseWithCountsCarriesThemAndHowFarThroughItIs() {
        this.port.phaseStarted("Sorting");

        this.port.tick("Sorting", 850, 1204);

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::counts, PhaseBar::measured, PhaseBar::fraction)
                .containsExactly("850 of 1,204", true, 850d / 1204);
    }

    @Test
    void theFillMovesThroughOneFileWhileTheCountsStayWhole() {
        this.port.phaseStarted("Moving to library");
        this.port.tick("Moving to library", 3, 8);

        this.port.tickWithin("Moving to library", 3, 8, 0.5);

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::counts, PhaseBar::fraction)
                .containsExactly("3 of 8, current file 50%", 3.5d / 8);
    }

    // The fill carries the same fraction as 1/total of its width, which disappears at any real
    // number of files. This is the reader's only sight of it there.
    @Test
    void aFilePartWayAcrossIsSaidInTheCountsAtAnyTotal() {
        this.port.phaseStarted("Sorting");
        this.port.tick("Sorting", 850, 1204);

        this.port.tickWithin("Sorting", 850, 1204, 0.6);

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::counts)
                .isEqualTo("850 of 1,204, current file 60%");
    }

    @Test
    void countsSayNothingAboutACurrentFileWhileNothingIsPartWayAcross() {
        this.port.phaseStarted("Finding dates");

        this.port.tick("Finding dates", 300, 1204);

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::counts)
                .isEqualTo("300 of 1,204");
    }

    @Test
    void aRunWithNoPhaseYetSaysItIsStartingRatherThanShowingAnEmptyPage() {
        assertThat(this.working().phases()).isEmpty();
        assertThat(this.working().waiting()).isEqualTo("Starting...");
    }

    @Test
    void theAreaHoldsRoomForAsManyBarsAsTheModeCanReport() {
        assertThat(this.presenter.view(RunMode.SIFT, "2019", false, false, null).reservedBars())
                .isEqualTo(RunMode.SIFT.phases());
        assertThat(this.presenter.view(RunMode.MOVE_TO_LIBRARY, "2019", false, false, null).reservedBars())
                .isEqualTo(RunMode.MOVE_TO_LIBRARY.phases());
    }

    @Test
    void aCancelledSiftWarnsAboutTheSheetAlreadyInFrontOfTheModel() {
        assertThat(this.stopping(RunMode.SIFT).cancelling()).contains("up to about a minute");
    }

    @Test
    void aCancelledSortSaysWhatSurvivesRatherThanHowLongTheStopTakes() {
        assertThat(this.stopping(RunMode.SORT).cancelling())
                .startsWith("What was sorted stays where it is.");
    }

    @Test
    void aCancelledMoveSaysWhatReachedTheLibraryStaysThere() {
        assertThat(this.stopping(RunMode.MOVE_TO_LIBRARY).cancelling())
                .startsWith("What reached your library stays there.");
    }

    @Test
    void aCancelledRescueNamesSortedRatherThanTheLibrary() {
        assertThat(this.stopping(RunMode.RESCUE).cancelling())
                .startsWith("What reached Sorted stays there.");
    }

    @Test
    void theStopButtonReportsTheStopRatherThanGoingDeadStillOffering() {
        assertThat(this.working().cancelLabel()).isEqualTo("Stop");
        assertThat(this.working().cancelPressable()).isTrue();

        assertThat(this.stopping(RunMode.SIFT).cancelLabel()).isEqualTo("Stopping...");
        assertThat(this.stopping(RunMode.SIFT).cancelPressable()).isFalse();
    }

    @Test
    void aStoppedRunMovingFilesOffersToGiveUpOnTheFileItIsOn() {
        this.port.phaseStarted("Sorting");
        this.port.tickWithin("Sorting", 850, 1204, 0.4);

        assertThat(this.stopping(RunMode.SORT).cancelLabel()).isEqualTo("Stop now");
        assertThat(this.stopping(RunMode.SORT).cancelPressable()).isTrue();
        assertThat(this.stopping(RunMode.SORT).cancelling()).contains("gives up on it instead");
    }

    // A sort finds dates and checks for duplicates before it moves anything, and both report counts
    // without a fraction. Naming a current file there would assert one that does not exist.
    @Test
    void aStopWhileNothingIsBeingWrittenNamesNoCurrentFile() {
        this.port.phaseStarted("Finding dates");
        this.port.tick("Finding dates", 300, 1204);

        assertThat(this.stopping(RunMode.SORT).cancelling())
                .doesNotContain("current file")
                .isEqualTo("What was sorted stays where it is.");
    }

    @Test
    void aStoppedSiftOffersNoSecondPressAndPromisesNoneInItsLine() {
        assertThat(this.stopping(RunMode.SIFT).cancelling()).doesNotContain("Stop now");
    }

    @Test
    void aRunAlreadyGivingUpOnItsFileSaysSoAndOffersNothingFurther() {
        final RunProgressView giving = this.presenter.view(RunMode.SORT, "2019", true, true, null);

        assertThat(giving.cancelLabel()).isEqualTo("Stopping now...");
        assertThat(giving.cancelPressable()).isFalse();
        assertThat(giving.cancelling()).contains("Nothing half-written is left behind");
    }

    @Test
    void aCancelledCopyClaimsNothingAboutTheOriginals() {
        assertThat(this.stoppingAnImport(ImportKind.COPY).cancelling())
                .startsWith("What arrived stays in your Inbox.");
    }

    @Test
    void aCancelledMoveSaysFilesAlreadyMovedIn() {
        assertThat(this.stoppingAnImport(ImportKind.MOVE).cancelling())
                .contains("already moved to your Inbox");
    }

    @Test
    void anImportWithNoKindClaimsNothingAboutTheFolderItCameFrom() {
        assertThat(this.presenter.view(RunMode.IMPORT, "DCIM", true, false, null).cancelling())
                .isEqualTo(this.stoppingAnImport(ImportKind.COPY).cancelling());
    }

    private RunProgressView working() {
        return this.presenter.view(RunMode.SORT, "the oldest year in your Inbox", false, false, null);
    }

    private RunProgressView stopping(final RunMode ran) {
        return this.presenter.view(ran, "2019", true, false, null);
    }

    private RunProgressView stoppingAnImport(final ImportKind kind) {
        return this.presenter.view(RunMode.IMPORT, "DCIM", true, false, kind);
    }
}
