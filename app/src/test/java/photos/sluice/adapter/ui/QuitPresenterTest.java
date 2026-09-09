package photos.sluice.adapter.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SortedTally.MonthRow;
import photos.sluice.application.port.in.SortedTally.YearRow;
import photos.sluice.application.port.in.SpendEstimate;
import photos.sluice.application.service.AutoResumedSifts;
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
import static org.mockito.Mockito.atLeastOnce;
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

    // Short enough that a test asking about a run which never ends is not paying the real
    // 2.5-second wait. Nearly every test in this file waits it out.
    private static final Duration A_SHORT_BEAT = Duration.ofMillis(20);

    // A sleep costs its own granularity on top of what it asks for. That overhead is a fixed number
    // of milliseconds rather than a fraction, so a longer beat absorbs it where a shorter one is
    // overrun by it.
    private static final Duration A_BEAT_LONG_ENOUGH_TO_ASK_THREE_TIMES = Duration.ofMillis(400);

    private final QuitPresenter presenter =
            new QuitPresenter(this.pipeline, this.startup, this.launcher, A_SHORT_BEAT);

    @BeforeEach
    void anInboxAndALibraryTheLauncherCanDraw() {
        when(this.pipeline.inboxTally()).thenReturn(new InboxTally(300, 1_000_000L));
        when(this.pipeline.sortedTally()).thenReturn(new SortedTally(List.of(
                new YearRow(2019, 100, 0, List.of(new MonthRow(6, 100, 0)))), 0));
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

    // On a beat of its own, since what this turns on is the wait asking a third time, after two
    // sleeps of a quarter of the beat. Windows rounds a sleep up to its timer's own granularity.
    // On the shared beat above, those two can outlast the deadline on a loaded machine, and the
    // third question is then never put.
    @Test
    void aRunThatEndsWhileTheCloseWaitsIsNeverAskedAbout() {
        final var waiting = new QuitPresenter(this.pipeline, this.startup, this.launcher,
                A_BEAT_LONG_ENOUGH_TO_ASK_THREE_TIMES);
        when(this.pipeline.isBusy()).thenReturn(true, true, false);

        assertThat(waiting.quitDialog()).isNull();
    }

    @Test
    void aRunStillGoingWhenTheBeatIsSpentIsAskedAbout() {
        when(this.pipeline.isBusy()).thenReturn(true);

        assertThat(this.presenter.quitDialog()).isNotNull();
    }

    @Test
    void closingOverARunningJobAsksBeforeAnythingIsStopped() {
        final QuitView asked = this.quitView(RunMode.SORT, "");

        assertThat(asked.question()).startsWith("Sorting is still going.");
        assertThat(asked.stopAndQuit()).isEqualTo("Stop and quit");
        assertThat(asked.keepRunning()).isEqualTo("Keep running");
        verify(this.startup, never()).windDownWithin(any());
    }

    // A discard, a sweep of the finished runs and a troubleshoot pass all reach here.
    @Test
    void aJobTheDashboardNeverStartedIsNamedWithoutAMode() {
        when(this.pipeline.isBusy()).thenReturn(true);

        final QuitView asked = this.presenter.quitDialog();

        assertThat(asked).isNotNull();
        assertThat(asked.question()).startsWith("Something is still running.");
    }

    // The other two are held apart from the sift's line itself rather than from a phrase inside it,
    // which a reword there would leave them passing over.
    @Test
    void aSiftAFileAndAJobWithNoModeEachGetTheirOwnWaitingLine() {
        when(this.pipeline.isBusy()).thenReturn(true);
        final QuitView noMode = this.presenter.quitDialog();
        assertThat(noMode).isNotNull();
        final String sift = this.quitView(RunMode.SIFT, "2019").waiting();

        assertThat(sift).contains("sheet").contains("provider account balance");
        assertThat(noMode.waiting())
                .contains("Finishing what was already started")
                .isNotEqualTo(sift);
        assertThat(this.quitView(RunMode.SORT, "").waiting())
                .contains("file")
                .contains("picked up again next time")
                .isNotEqualTo(sift);
    }

    @Test
    void quittingASiftNamesWhatASecondSiftOfThatSheetWouldSpend() {
        final QuitView asked = this.quitView(RunMode.SIFT, "2019");

        assertThat(asked.waiting()).contains("provider account balance");
        assertThat(asked.forceQuit()).isEqualTo("Force quit now");
    }

    // A reader who chose Copy would otherwise be told their file is moved.
    @Test
    void bothImportKindsGetTheSameWaitingLine() {
        assertThat(this.importWaitingLine(RunLauncherPresenter::startImportCopying))
                .isEqualTo(this.importWaitingLine(RunLauncherPresenter::startImportMoving));
    }

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

    // Asking after the wind-down would reach a runner already shut, with nothing left to escalate.
    @Test
    void forceQuittingGivesUpOnTheFileBeforeItWindsDown() {
        this.presenter.forceQuit();

        final var order = inOrder(this.pipeline, this.startup);
        order.verify(this.pipeline).abandonFileInFlight();
        order.verify(this.startup).windDownWithin(Duration.ZERO);
    }

    @Test
    void aSiftNobodyStartedIsNamedAsASiftRatherThanAsSomething() {
        final QuitView asked = this.quitViewOver(_ -> {
            final var listener = ArgumentCaptor.forClass(AutoResumedSifts.Listener.class);
            verify(this.pipeline, atLeastOnce()).onSiftAutoResumed(listener.capture());
            listener.getValue().resumed("2019", retyped(neverFinishing()));
        });

        assertThat(asked.question()).startsWith("Sifting is still going.");
        assertThat(asked.waiting()).contains("sheet").contains("provider account balance");
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
                new QuitPresenter(this.pipeline, this.startup, launcher, A_SHORT_BEAT).quitDialog();
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
