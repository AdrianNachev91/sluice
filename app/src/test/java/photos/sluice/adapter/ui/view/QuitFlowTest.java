package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.QuitPresenter;
import photos.sluice.adapter.ui.QuitView;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.answerDialog;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;

// The order the two questions are asked in, which is the whole of what this class decides. Driven
// through the real dialogs, because the order only exists in how the two calls are sequenced.
class QuitFlowTest {

    private final QuitPresenter presenter = mock(QuitPresenter.class);

    private final AtomicReference<BooleanSupplier> leavingLosesWork = new AtomicReference<>(() -> false);

    private final QuitFlow flow = new QuitFlow(this.presenter, this.leavingLosesWork);

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    // Typed work is gone the moment the window is, and a run can still be kept. So a reader who
    // stays with what they typed is never asked about the run, and nothing winds down behind them.
    @Test
    void stayingWithTypedWorkIsNeverAskedAboutTheRun() throws Exception {
        this.leavingLosesWork.set(() -> true);
        final Stage stage = onFxThread(QuitFlowTest::shownStage);

        final Future<Boolean> closing = WaitForAsyncUtils.asyncFx(() -> this.flow.mayClose(stage));
        answerDialog("Stay");

        assertThat(closing.get(10, TimeUnit.SECONDS)).isFalse();
        verify(this.presenter, never()).quitDialog();
        verify(this.presenter, never()).stopAndWait();
        verify(this.presenter, never()).forceQuit();
    }

    @Test
    void leavingWithoutSavingThenMeetsTheQuestionAboutTheRun() throws Exception {
        this.leavingLosesWork.set(() -> true);
        when(this.presenter.quitDialog()).thenReturn(aRunningJob());
        final Stage stage = onFxThread(QuitFlowTest::shownStage);

        final Future<Boolean> closing = WaitForAsyncUtils.asyncFx(() -> this.flow.mayClose(stage));
        answerDialog("Leave anyway");
        answerDialog("Keep running");

        assertThat(closing.get(10, TimeUnit.SECONDS)).isFalse();
        verify(this.presenter).quitDialog();
        verify(this.presenter, never()).stopAndWait();
    }

    // Nothing typed, so the only question is the one about the run.
    @Test
    void aScreenHoldingNothingGoesStraightToTheQuestionAboutTheRun() throws Exception {
        when(this.presenter.quitDialog()).thenReturn(aRunningJob());
        final Stage stage = onFxThread(QuitFlowTest::shownStage);

        final Future<Boolean> closing = WaitForAsyncUtils.asyncFx(() -> this.flow.mayClose(stage));
        answerDialog("Keep running");

        assertThat(closing.get(10, TimeUnit.SECONDS)).isFalse();
        verify(this.presenter).quitDialog();
    }

    @Test
    void nothingTypedAndNothingRunningClosesWithoutAsking() throws Exception {
        when(this.presenter.quitDialog()).thenReturn(null);
        final Stage stage = onFxThread(QuitFlowTest::shownStage);

        assertThat(onFxThread(() -> this.flow.mayClose(stage))).isTrue();
    }

    private static QuitView aRunningJob() {
        return new QuitView("Quit while something is running?", "Sorting is still going.",
                "Stop and quit", "Keep running", false, "Quitting", "Finishing the current file.",
                "Force quit now");
    }

    private static Stage shownStage() {
        final var stage = new Stage();
        stage.setScene(new Scene(new VBox(), 400, 300));
        stage.show();
        return stage;
    }
}
