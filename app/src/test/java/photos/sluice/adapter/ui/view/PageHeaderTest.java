package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import photos.sluice.adapter.ui.SettingsView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class PageHeaderTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void enterSavesWhileTheCaretIsNotInAField() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.elsewhere::requestFocus);

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    // The whole reason Save is not a default button. JavaFX hands a default button every Enter in
    // the scene, and a text field does not consume the key. A half-typed folder path would then be
    // saved by the keystroke meant to end the line.
    @Test
    void enterDoesNothingWhileTheCaretIsInAField() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.field::requestFocus);

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isZero();
    }

    // Text a reader can select has to hold focus to be selected, and it is a text input control
    // like the fields are. Read as one, a click on a help line would leave Enter dead.
    @Test
    void enterStillSavesAfterAClickOnTextThatIsOnlyForReading() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.prose::requestFocus);

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    @Test
    void clickingAwayOntoReadableTextStillReleasesTheField() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.field::requestFocus);

        onFxThread(() -> clickOn(page.prose));
        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    @Test
    void enterSavesFromARadioTheReaderJustChose() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.radio::requestFocus);

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    // A spinner answers the focus owner as itself, whether the caret is in its text or the reader
    // is on its arrows. So the two cases are indistinguishable from state alone.
    @Test
    void enterDoesNothingOnceTheCaretIsInASpinner() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(() -> {
            page.spinner.requestFocus();
            clickOn(page.spinner.getEditor());
        });

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isZero();
    }

    @Test
    void enterSavesAfterSteppingASpinner() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(() -> {
            page.spinner.requestFocus();
            clickOn(page.spinner.getEditor());
            page.spinner.fireEvent(arrowKey());
        });

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    @Test
    void theSaveButtonItselfStillSavesOnEnter() throws Exception {
        final Page page = onFxThread(PageHeaderTest::aPage);
        onFxThread(page.save::requestFocus);

        onFxThread(() -> press(page));

        assertThat(page.saves[0]).isOne();
    }

    private static void clickOn(final javafx.scene.Node target) {
        target.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
    }

    private static KeyEvent arrowKey() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.UP, false, false, false, false);
    }

    // Fired at whatever holds focus, not at the page. A key pressed at the page would reach the
    // page's own rule however the app routes it. The assertion would then hold even with that rule
    // wired to a control that never sees the key.
    private static void press(final Page page) {
        page.root.getScene().getFocusOwner().fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    private record Page(VBox root, Button save, TextField field, CheckBox elsewhere,
                        RadioButton radio, Spinner<Integer> spinner, TextArea prose, int[] saves) {
    }

    private static Page aPage() {
        final PageHeader.Result header = PageHeader.build("Settings", "test-save", null);
        final int[] saves = {0};
        header.save().setOnAction(_ -> saves[0]++);

        final var field = new TextField();
        final var elsewhere = new CheckBox("something that is not a field");
        final var radio = new RadioButton("a radio the reader just chose");
        final Spinner<Integer> spinner =
                SettingsRows.numberField(new SettingsView.NumberRange(1, 12, 1), 5);
        final TextArea prose = SelectableText.prose("a help line the reader can select");
        final var body = new VBox(field, elsewhere, radio, spinner, prose);
        final VBox root = PageHeader.pinnedOver(header, body);

        final var stage = new Stage();
        stage.setScene(new Scene(root, 400, 300));
        stage.show();
        return new Page(root, header.save(), field, elsewhere, radio, spinner, prose, saves);
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
}
