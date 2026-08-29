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
    void aRunWithNoPhaseYetSaysItIsStartingRatherThanShowingAnEmptyPage() {
        assertThat(this.working().phases()).isEmpty();
        assertThat(this.working().waiting()).isEqualTo("Starting...");
    }

    @Test
    void theAreaHoldsRoomForAsManyBarsAsTheModeCanReport() {
        assertThat(this.presenter.view(RunMode.SIFT, "2019", false, null).reservedBars())
                .isEqualTo(RunMode.SIFT.phases());
        assertThat(this.presenter.view(RunMode.MOVE_TO_LIBRARY, "2019", false, null).reservedBars())
                .isEqualTo(RunMode.MOVE_TO_LIBRARY.phases());
    }

    @Test
    void aCancelledSiftWarnsAboutTheSheetAlreadyInFrontOfTheModel() {
        assertThat(this.stopping(RunMode.SIFT).cancelling()).contains("up to about a minute");
    }

    @Test
    void aCancelledSortSaysWhatSurvivesRatherThanHowLongTheStopTakes() {
        assertThat(this.stopping(RunMode.SORT).cancelling())
                .isEqualTo("What was sorted stays where it is.");
    }

    @Test
    void aCancelledMoveSaysWhatReachedTheLibraryStaysThere() {
        assertThat(this.stopping(RunMode.MOVE_TO_LIBRARY).cancelling())
                .isEqualTo("What reached your library stays there.");
    }

    @Test
    void theCancelButtonReportsTheStopRatherThanGoingDeadStillOffering() {
        assertThat(this.working().cancelLabel()).isEqualTo("Stop");
        assertThat(this.working().cancelPressable()).isTrue();

        assertThat(this.stopping(RunMode.SORT).cancelLabel()).isEqualTo("Stopping...");
        assertThat(this.stopping(RunMode.SORT).cancelPressable()).isFalse();
    }

    @Test
    void aCancelledCopyClaimsNothingAboutTheOriginals() {
        assertThat(this.stoppingAnImport(ImportKind.COPY).cancelling())
                .isEqualTo("What arrived stays in your Inbox.");
    }

    @Test
    void aCancelledMoveSaysFilesAlreadyMovedIn() {
        assertThat(this.stoppingAnImport(ImportKind.MOVE).cancelling())
                .contains("already moved to your Inbox");
    }

    @Test
    void anImportWithNoKindClaimsNothingAboutTheFolderItCameFrom() {
        assertThat(this.presenter.view(RunMode.IMPORT, "DCIM", true, null).cancelling())
                .isEqualTo(this.stoppingAnImport(ImportKind.COPY).cancelling());
    }

    private RunProgressView working() {
        return this.presenter.view(RunMode.SORT, "the oldest year in your Inbox", false, null);
    }

    private RunProgressView stopping(final RunMode ran) {
        return this.presenter.view(ran, "2019", true, null);
    }

    private RunProgressView stoppingAnImport(final ImportKind kind) {
        return this.presenter.view(RunMode.IMPORT, "DCIM", true, kind);
    }
}
