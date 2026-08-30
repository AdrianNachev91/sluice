package photos.sluice.adapter.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuitPresenterTest {

    private final Pipeline pipeline = mock(Pipeline.class);

    private final StartupSequence startup = mock(StartupSequence.class);

    private final RunLauncherPresenter launcher =
            new RunLauncherPresenter(this.pipeline, new FxProgressPort(Runnable::run));

    private final QuitPresenter presenter = new QuitPresenter(this.pipeline, this.startup, this.launcher);

    @BeforeEach
    void anInboxAndALibraryTheLauncherCanDraw() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 0, List.of(new MonthRow(6, 100, 0))))));
        when(this.pipeline.estimateFor(anyInt()))
                .thenReturn(new SpendEstimate(0, 0, true, false, false));
        when(this.pipeline.configuredProviderSpends()).thenReturn(true);
        this.launcher.setup().refreshCounts();
    }

    @Test
    void closingWithNothingRunningIsNotWorthAsking() {
        when(this.pipeline.isBusy()).thenReturn(false);

        assertThat(this.presenter.quitDialog()).isNull();
    }

    @Test
    void closingOverARunningJobAsksBeforeAnythingIsStopped() {
        final QuitView asked = this.quitView(RunMode.SORT, "");

        assertThat(asked.question()).startsWith("Sorting is still going.");
        assertThat(asked.stopAndQuit()).isEqualTo("Stop and quit");
        assertThat(asked.keepRunning()).isEqualTo("Keep running");
        verify(this.startup, never()).windDownWithin(any());
    }

    // A discard, a sweep of the finished runs and a troubleshoot pass all reach here. The dashboard
    // started none of them, so there is no mode to name.
    @Test
    void aJobTheDashboardNeverStartedIsNamedWithoutAMode() {
        when(this.pipeline.isBusy()).thenReturn(true);

        final QuitView asked = this.presenter.quitDialog();

        assertThat(asked).isNotNull();
        assertThat(asked.question()).startsWith("Something is still running.");
    }

    // Three branches, and what a test can hold is that each reaches its own line. The words
    // themselves are a wording call rather than a property of this class.
    @Test
    void aSiftAFileAndAJobWithNoModeEachGetTheirOwnWaitingLine() {
        when(this.pipeline.isBusy()).thenReturn(true);
        final QuitView noMode = this.presenter.quitDialog();
        assertThat(noMode).isNotNull();

        assertThat(List.of(noMode.waiting(), this.quitView(RunMode.SIFT, "2019").waiting(),
                this.quitView(RunMode.SORT, "").waiting())).doesNotHaveDuplicates();
    }

    @Test
    void quittingASiftNamesWhatASecondSiftOfThatSheetWouldSpend() {
        final QuitView asked = this.quitView(RunMode.SIFT, "2019");

        assertThat(asked.waiting()).contains("provider account balance");
        assertThat(asked.forceQuit()).isEqualTo("Force quit now");
    }

    // The progress area does branch on the import kind, so this line not branching is a property
    // rather than an accident. A reader who chose Copy would otherwise be told their file is moved.
    @Test
    void bothImportKindsGetTheSameWaitingLine() {
        assertThat(this.importWaitingLine(RunLauncherPresenter::startImportCopying))
                .isEqualTo(this.importWaitingLine(RunLauncherPresenter::startImportMoving));
    }

    // Quitting stops a run and on a sift throws away a sheet already paid for. Keeping costs
    // nothing, and the window closes again on a second press.
    @Test
    void keepRunningIsTheChoiceTheQuestionLeadsWith() {
        assertThat(this.quitView(RunMode.SORT, "").stopAndQuitLeads()).isFalse();
    }

    @Test
    void aWindDownThatFailsStillLetsTheReaderQuit() {
        doThrow(new UncheckedIOException(new IOException("claim file held")))
                .when(this.startup).windDownWithin(any());

        assertThatCode(this.presenter::stopAndWait).doesNotThrowAnyException();
        assertThatCode(this.presenter::forceQuit).doesNotThrowAnyException();
    }

    @Test
    void stoppingAndQuittingWaitsTheAttendedBudgetOut() {
        this.presenter.stopAndWait();

        verify(this.startup).windDownWithin(StartupSequence.ATTENDED_DRAIN_WAIT);
    }

    // The order carries it. Asking after the wind-down would reach a runner already shut, with
    // nothing left to escalate.
    @Test
    void forceQuittingGivesUpOnTheFileBeforeItWindsDown() {
        this.presenter.forceQuit();

        final var order = inOrder(this.pipeline, this.startup);
        order.verify(this.pipeline).abandonTheFileInFlight();
        order.verify(this.startup).windDownWithin(Duration.ZERO);
    }

    private QuitView quitView(final RunMode mode, final String scope) {
        return this.quitViewOver(launcher -> {
            launcher.setup().setMode(mode);
            launcher.setup().setScope(scope);
            launcher.start();
        });
    }

    private String importWaitingLine(final BiConsumer<RunLauncherPresenter, List<Path>> start) {
        return this.quitViewOver(
                launcher -> start.accept(launcher, List.of(Path.of("cards", "DCIM")))).waiting();
    }

    // A launcher of its own per call. One that has started a job refuses to start a second while
    // the first is in flight, and these jobs never finish. Sharing one would leave a second call
    // silently answering about the first job.
    //
    // Each handle is built before the call that returns it is stubbed. Building one inside the
    // argument to when() would stub a second mock while the first stubbing is still open, which
    // Mockito reads as an unfinished one.
    //
    // The facade answers busy only once the job is in flight, and the launcher refuses to start one
    // while it says so. Stubbing it true up front would refuse the very run this is arranging.
    private QuitView quitViewOver(final Consumer<RunLauncherPresenter> start) {
        final JobHandle<Object> sorting = neverFinishing();
        final JobHandle<Object> sifting = neverFinishing();
        final JobHandle<Object> importing = neverFinishing();
        final var launcher = new RunLauncherPresenter(this.pipeline, new FxProgressPort(Runnable::run));
        launcher.setup().refreshCounts();
        when(this.pipeline.isBusy()).thenReturn(false);
        when(this.pipeline.sort(any())).thenReturn(retyped(sorting));
        when(this.pipeline.cull(any())).thenReturn(retyped(sifting));
        when(this.pipeline.importFrom(any(), any())).thenReturn(retyped(importing));
        start.accept(launcher);
        when(this.pipeline.isBusy()).thenReturn(true);
        final QuitView asked =
                new QuitPresenter(this.pipeline, this.startup, launcher).quitDialog();
        assertThat(asked).isNotNull();
        return asked;
    }

    @SuppressWarnings("unchecked")
    private static JobHandle<Object> neverFinishing() {
        final JobHandle<Object> handle = mock(JobHandle.class);
        when(handle.onComplete()).thenReturn(new CompletableFuture<>());
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static <T> JobHandle<T> retyped(final JobHandle<?> handle) {
        return (JobHandle<T>) handle;
    }
}
