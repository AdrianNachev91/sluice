package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.StartupFailurePresenter;
import photos.sluice.application.startup.StartupFailure.ConfigSpot;
import photos.sluice.application.startup.StartupFailure.RejectedSetting;
import photos.sluice.application.startup.StartupFailure.Unclassified;
import photos.sluice.application.startup.StartupFailure.UnparsableConfigFile;
import photos.sluice.application.startup.StartupFailure.WorkingRootBusy;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

// What StartupFailurePresenterTest cannot reach: which button a built scene wires to which action,
// and the trace disclosure and its Copy button. What each card says stays that presenter's own.
//
// Runs on the FX thread throughout. Building the scene reads the desktop's colour preferences, and
// that call refuses any other thread.
class StartupFailureWindowTest {

    private static final String TRACE = "java.lang.IllegalStateException: boom";

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void retryButtonRunsTheRetryAction() throws Exception {
        final var retried = new AtomicBoolean();
        final Parent root = onFxThread(() ->
                built(busyRoot(), actions(() -> retried.set(true), noRemove(), noSetAside())));

        runOnFxThread(() -> ((Button) root.lookup("#retry-button")).fire());

        assertThat(retried).isTrue();
    }

    @Test
    void removeSettingButtonRunsTheRemoveSettingActionWithTheRejectedProperty() throws Exception {
        final var removed = new AtomicReference<@Nullable String>();
        final Parent root = onFxThread(() ->
                built(rejectedInFile(), actions(noRetry(), removed::set, noSetAside())));

        runOnFxThread(() -> ((Button) root.lookup("#remove-setting-button")).fire());

        assertThat(removed).hasValue("sluice.paths.library-root");
    }

    @Test
    void setAsideButtonRunsTheSetAsideAction() throws Exception {
        final var setAside = new AtomicBoolean();
        final Parent root = onFxThread(() ->
                built(unparsable(), actions(noRetry(), noRemove(), () -> setAside.set(true))));

        runOnFxThread(() -> ((Button) root.lookup("#set-aside-button")).fire());

        assertThat(setAside).isTrue();
    }

    @Test
    void theDisclosureStartsClosed() throws Exception {
        final Parent root = onFxThread(() -> built(generic(), noopActions()));

        assertThat(((TitledPane) root.lookup("#trace-disclosure")).isExpanded()).isFalse();
    }

    @Test
    void expandingTheDisclosureRevealsTheTrace() throws Exception {
        final Parent root = onFxThread(() -> built(generic(), noopActions()));
        final var disclosure = (TitledPane) root.lookup("#trace-disclosure");

        runOnFxThread(() -> disclosure.setExpanded(true));

        assertThat(((TextArea) disclosure.getContent()).getText()).isEqualTo(TRACE);
    }

    @Test
    void copyPutsTheTraceOnTheClipboardWithoutOpeningTheDisclosureFirst() throws Exception {
        final Parent root = onFxThread(() -> built(generic(), noopActions()));
        final var disclosure = (TitledPane) root.lookup("#trace-disclosure");

        runOnFxThread(() -> ((Button) root.lookup("#copy-trace-button")).fire());

        assertThat(disclosure.isExpanded()).isFalse();
        assertThat(onFxThread(() -> Clipboard.getSystemClipboard().getString())).isEqualTo(TRACE);
    }

    @Test
    void copyMarksItselfDoneOnTheButtonItself() throws Exception {
        final Parent root = onFxThread(() -> built(generic(), noopActions()));

        runOnFxThread(() -> ((Button) root.lookup("#copy-trace-button")).fire());

        assertThat(((Button) root.lookup("#copy-trace-button")).getText()).isEqualTo("Copied");
    }

    private static Parent built(final StartupFailurePresenter presenter, final StartupFailureActions actions) {
        final Scene scene = StartupFailureWindow.scene(presenter, actions);
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return scene.getRoot();
    }

    private static StartupFailurePresenter busyRoot() {
        return new StartupFailurePresenter(new WorkingRootBusy(Path.of("D:\\working"), TRACE));
    }

    private static StartupFailurePresenter rejectedInFile() {
        return new StartupFailurePresenter(new RejectedSetting("sluice.paths.library-root",
                new ConfigSpot(Path.of("D:\\config.yml"), null), TRACE));
    }

    private static StartupFailurePresenter unparsable() {
        return new StartupFailurePresenter(new UnparsableConfigFile(
                new ConfigSpot(Path.of("D:\\config.yml"), null), "a stray comma", TRACE));
    }

    private static StartupFailurePresenter generic() {
        return new StartupFailurePresenter(new Unclassified(TRACE));
    }

    private static StartupFailureActions actions(final Runnable retry, final Consumer<String> removeSetting,
            final Runnable setAside) {
        return new StartupFailureActions(retry, removeSetting, setAside);
    }

    private static StartupFailureActions noopActions() {
        return actions(noRetry(), noRemove(), noSetAside());
    }

    private static Runnable noRetry() {
        return () -> {
            throw new AssertionError("no test here retries");
        };
    }

    private static Consumer<String> noRemove() {
        return _ -> {
            throw new AssertionError("no test here removes a setting");
        };
    }

    private static Runnable noSetAside() {
        return () -> {
            throw new AssertionError("no test here sets the file aside");
        };
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
