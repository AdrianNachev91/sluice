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
    void aPhaseThatGaveUpPartWayKeepsTheCountItReachedRatherThanFillingUp() {
        this.port.phaseStarted("Sifting");
        this.port.tick("Sifting", 11, 28);

        this.port.phaseCutShort("Sifting");
        this.port.phaseFinished("Sifting");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::finished)
                .containsExactly(11d / 28, true);
    }

    @Test
    void aPhaseThatEndedHavingCountedNothingIsFullRatherThanEmpty() {
        this.port.phaseStarted("Applying decisions");

        this.port.phaseFinished("Applying decisions");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::measured)
                .containsExactly(1d, true);
    }

    @Test
    void aPhaseThatGaveUpBeforeCountingAnythingIsEmptyRatherThanFull() {
        this.port.phaseStarted("Applying decisions");

        this.port.phaseCutShort("Applying decisions");
        this.port.phaseFinished("Applying decisions");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::measured, PhaseBar::finished)
                .containsExactly(0d, true, true);
    }

    @Test
    void aPhaseEndingUncountedOnARunTheReaderStoppedIsEmptyRatherThanFull() {
        this.port.phaseStarted("Moving to library");

        this.port.phaseFinished("Moving to library");

        assertThat(this.stopping(RunMode.MOVE_TO_LIBRARY).phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::cutShort)
                .containsExactly(0d, false);
    }

    @Test
    void aPhaseThatWorkedThroughOnARunNobodyStoppedIsMarkedAsHavingDoneItsWork() {
        this.port.phaseStarted("Finding dates");
        this.port.tick("Finding dates", 1204, 1204);

        this.port.phaseFinished("Finding dates");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::finished, PhaseBar::wentThrough)
                .containsExactly(true, true);
    }

    @Test
    void aPhaseWhoseCountReachedItsTotalKeepsTheMarkOnARunTheReaderStopped() {
        this.port.phaseStarted("Finding dates");
        this.port.tick("Finding dates", 1204, 1204);
        this.port.phaseFinished("Finding dates");

        assertThat(this.stopping(RunMode.SORT).phases()).singleElement()
                .extracting(PhaseBar::wentThrough)
                .isEqualTo(true);
    }

    @Test
    void aPhaseStoppedShortOfItsTotalCarriesNoMarkOnARunTheReaderStopped() {
        this.port.phaseStarted("Sorting");
        this.port.tick("Sorting", 850, 1204);
        this.port.phaseFinished("Sorting");

        assertThat(this.stopping(RunMode.SORT).phases()).singleElement()
                .extracting(PhaseBar::wentThrough)
                .isEqualTo(false);
    }

    @Test
    void anUncountedPhaseCarriesNoMarkOnARunTheReaderStopped() {
        this.port.phaseStarted("Finding dates");
        this.port.phaseFinished("Finding dates");

        assertThat(this.stopping(RunMode.SORT).phases()).singleElement()
                .extracting(PhaseBar::finished, PhaseBar::cutShort, PhaseBar::wentThrough)
                .containsExactly(true, false, false);
    }

    @Test
    void aPhaseThatGaveUpPartWayHavingCountedKeepsTheFractionItReached() {
        this.port.phaseStarted("Sorting");
        this.port.tick("Sorting", 850, 1204);

        this.port.phaseCutShort("Sorting");
        this.port.phaseFinished("Sorting");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::cutShort, PhaseBar::wentThrough)
                .containsExactly(850d / 1204, true, false);
    }

    @Test
    void aCountedPhaseOnARunTheReaderStoppedKeepsTheFractionItReached() {
        this.port.phaseStarted("Sorting");
        this.port.tick("Sorting", 850, 1204);

        this.port.phaseFinished("Sorting");

        assertThat(this.stopping(RunMode.SORT).phases()).singleElement()
                .extracting(PhaseBar::fraction, PhaseBar::cutShort, PhaseBar::wentThrough)
                .containsExactly(850d / 1204, false, false);
    }

    @Test
    void aPhaseThatGaveUpPartWayIsCarriedToTheScreenAsHavingDoneSo() {
        this.port.phaseStarted("Sorting");

        this.port.phaseCutShort("Sorting");
        this.port.phaseFinished("Sorting");

        assertThat(this.working().phases()).singleElement()
                .extracting(PhaseBar::cutShort)
                .isEqualTo(true);
    }

    @Test
    void aRunWithNoPhaseYetSaysItIsStartingRatherThanShowingAnEmptyPage() {
        assertThat(this.working().phases()).isEmpty();
        assertThat(this.working().waiting()).isEqualTo("Starting...");
    }

    @Test
    void theAreaHoldsRoomForAsManyBarsAsTheModeCanReport() {
        assertThat(this.presenter.view(RunMode.SIFT, "2019", false, false, null, false).reservedBars())
                .isEqualTo(3);
        assertThat(this.presenter.view(RunMode.MOVE_TO_LIBRARY, "2019", false, false, null, false).reservedBars())
                .isEqualTo(1);
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
                .startsWith("What reached your Library stays there.");
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
        final RunProgressView giving = this.presenter.view(RunMode.SORT, "2019", true, true, null, false);

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
        assertThat(this.presenter.view(RunMode.IMPORT, "DCIM", true, false, null, false).cancelling())
                .isEqualTo(this.stoppingAnImport(ImportKind.COPY).cancelling());
    }

    private RunProgressView working() {
        return this.presenter.view(RunMode.SORT, "the oldest year in your Inbox", false, false, null, false);
    }

    private RunProgressView stopping(final RunMode ran) {
        return this.presenter.view(ran, "2019", true, false, null, false);
    }

    private RunProgressView stoppingAnImport(final ImportKind kind) {
        return this.presenter.view(RunMode.IMPORT, "DCIM", true, false, kind, false);
    }
}
