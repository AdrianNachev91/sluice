package photos.sluice.adapter.ui.view;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// The one dialog in the app that does not block its caller, because what it describes is a wait the
// caller has to keep making progress on. Everything else about it follows from that.
class DialogsWaitingTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void itIsUpAsSoonAsItIsAskedFor() throws Exception {
        final Shown shown = onFxThread(DialogsWaitingTest::aWait);

        assertThat(isShowing(shown)).isTrue();
        assertThat(shown.pressed.get()).isZero();
    }

    @Test
    void theWorkEndingTakesItDown() throws Exception {
        final Shown shown = onFxThread(DialogsWaitingTest::aWait);

        onFxThread(() -> shown.waiting.close().run());

        assertThat(isShowing(shown)).isFalse();
    }

    @Test
    void itsOneControlReportsThePressAndTakesTheDialogDown() throws Exception {
        final Shown shown = onFxThread(DialogsWaitingTest::aWait);

        onFxThread(() -> giveUpButton(shown).fire());

        assertThat(shown.pressed.get()).isOne();
        assertThat(isShowing(shown)).isFalse();
    }

    @Test
    void theWindowsOwnCloseButtonIsRefused() throws Exception {
        final Shown shown = onFxThread(DialogsWaitingTest::aWait);

        onFxThread(() -> Event.fireEvent(shown.dialogWindow(),
                new WindowEvent(shown.dialogWindow(), WindowEvent.WINDOW_CLOSE_REQUEST)));

        assertThat(isShowing(shown)).isTrue();
    }

    @Test
    void escapeIsRefused() throws Exception {
        final Shown shown = onFxThread(DialogsWaitingTest::aWait);

        onFxThread(() -> Event.fireEvent(shown.dialogWindow().getScene().getRoot(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                        false, false, false, false)));

        assertThat(isShowing(shown)).isTrue();
    }

    private static Shown aWait() {
        final var owner = new Stage();
        owner.setScene(new Scene(new VBox(), 400, 300));
        owner.show();
        final var pressed = new AtomicInteger();
        final Dialogs.Waiting waiting = Dialogs.waiting(owner, "Quitting",
                "Sluice is finishing the file it is on.", "Force quit now", pressed::incrementAndGet);
        return new Shown(owner, waiting, pressed);
    }

    private static Button giveUpButton(final Shown shown) {
        return (Button) shown.dialogWindow().getScene().getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof final Button button
                        && "Force quit now".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the waiting dialog drew no way out of the wait"));
    }

    // Read on the FX thread. Window.getWindows() is a live list the FX thread rebuilds as a dialog
    // opens or closes, so iterating it anywhere else reads a size that changes underneath.
    private static boolean isShowing(final Shown shown) throws Exception {
        return onFxThread(() -> Window.getWindows().stream()
                .anyMatch(window -> window != shown.owner && window.isShowing()));
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        final T result = WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
        return result;
    }

    private static void onFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private record Shown(Stage owner, Dialogs.Waiting waiting, AtomicInteger pressed) {

        private Window dialogWindow() {
            return Window.getWindows().stream()
                    .filter(window -> window != this.owner)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the waiting dialog never opened"));
        }
    }
}
