package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.stage.Stage;
import photos.sluice.adapter.ui.LeavingUnsaved;
import photos.sluice.adapter.ui.QuitPresenter;
import photos.sluice.adapter.ui.QuitView;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Runs the two dialogs a close puts up while Sluice is still working.
 *
 * <p>The first blocks and asks. The second does not, because it describes a wait the app has to
 * keep making progress on. It goes up, the wind-down runs on a thread of its own, and whichever
 * finishes first takes it down.
 *
 * <p>Nothing here decides what to say, which run is going, or what a stop costs. All of that comes
 * from {@link QuitPresenter} as finished sentences.
 */
final class QuitFlow {

    private final QuitPresenter presenter;

    private final AtomicReference<BooleanSupplier> leavingLosesWork;

    /**
     * Creates the flow over the presenter that words it and carries it out.
     *
     * @param presenter {@link QuitPresenter} decides what to ask and what each answer does
     * @param leavingLosesWork an {@link AtomicReference} to whether the screen on show holds work
     *     nobody has saved, kept up to date by the shell
     */
    QuitFlow(final QuitPresenter presenter,
             final AtomicReference<BooleanSupplier> leavingLosesWork) {
        this.presenter = presenter;
        this.leavingLosesWork = leavingLosesWork;
    }

    /**
     * Whether the window may close now.
     *
     * <p>Two questions, asked in the order the reader loses things. Typed work goes the moment the
     * window does, and a run can still be kept. So the unsaved-work question comes first, and a
     * reader who stays there is never asked about the run at all.
     *
     * <p>False keeps the window open, which covers three answers. A reader who chose to stay with
     * their typed work. A reader who chose to keep the run going. And a reader who chose to stop
     * and quit, where this class takes the closing over and does it once the run has stopped.
     * Letting the toolkit close the window in that third case would take away the dialog describing
     * the wait.
     *
     * @param stage {@link Stage} the window being closed
     * @return boolean true where the toolkit should go ahead and close it
     */
    boolean mayClose(final Stage stage) {
        if (this.leavingLosesWork.get().getAsBoolean()
                && !Dialogs.agreed(stage.getScene().getRoot(), LeavingUnsaved.question())) {
            return false;
        }
        final QuitView asked = this.presenter.quitDialog();
        if (asked == null) {
            return true;
        }
        if (Dialogs.ask(stage.getScene().getRoot(), asked.heading(), asked.question(),
                new Dialogs.Choice(asked.stopAndQuit(), Dialogs.Role.GO_AHEAD,
                        Dialogs.Emphasis.of(asked.stopAndQuitLeads())),
                new Dialogs.Choice(asked.keepRunning(), Dialogs.Role.CANCEL,
                        Dialogs.Emphasis.of(!asked.stopAndQuitLeads())))
                .isEmpty()) {
            return false;
        }
        this.waitThenExit(stage, asked);
        return false;
    }

    /**
     * Shows the wait, stops the run behind it, and leaves once it has stopped.
     *
     * <p>The wind-down runs off the application thread because it blocks for as long as the run
     * takes to notice. On this thread the dialog it is being described in would not draw, and the
     * control offering a way out of it would not answer.
     *
     * <p>The exit is unconditional. A run outlasting even the attended wait is left to the process
     * ending. That is what the reader asked for, and the part-file convention is what makes it safe.
     *
     * @param stage {@link Stage} the window the wait covers
     * @param asked {@link QuitView} the wording of the wait
     */
    private void waitThenExit(final Stage stage, final QuitView asked) {
        final Dialogs.Waiting shown = Dialogs.waiting(stage, asked.waitingHeading(), asked.waiting(),
                asked.forceQuit(), () -> this.forceQuit(stage));
        Thread.ofVirtual().start(() -> {
            this.presenter.stopAndWait();
            Platform.runLater(() -> {
                shown.close().run();
                exit(stage);
            });
        });
    }

    /**
     * Takes the way out of the wait: gives up on the file in flight, then leaves.
     *
     * <p>The wind-down still runs, so watchers are retired and the runner is shut. What it does not
     * do is wait. The dialog is already down by the time this runs, taken there by the press.
     *
     * @param stage {@link Stage} the window to close
     */
    private void forceQuit(final Stage stage) {
        this.presenter.forceQuit();
        exit(stage);
    }

    /**
     * Closes the window and ends the app.
     *
     * <p>Both, because the close request that started all this was refused. Nothing else is going to
     * take the window down, and the toolkit only ends the app once the last window has gone.
     *
     * @param stage {@link Stage} the window to close
     */
    private static void exit(final Stage stage) {
        stage.close();
        Platform.exit();
    }
}
