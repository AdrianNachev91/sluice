package photos.sluice.adapter.ui.view;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import photos.sluice.adapter.ui.PathRoleLabels;
import photos.sluice.adapter.ui.SettingsView;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;

// The rows both the first-run card and the Settings folders card are built from.
class FolderRootRowsTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void eachRowIsLabelledTheWayEverySurfaceNamesThatRoot() throws Exception {
        assertThat(labelOf(FolderRootRows::workingRoot)).isEqualTo(PathRoleLabels.WORKING_ROOT);
        assertThat(labelOf(FolderRootRows::libraryRoot)).isEqualTo(PathRoleLabels.LIBRARY_ROOT);
        assertThat(labelOf(FolderRootRows::inbox)).isEqualTo(PathRoleLabels.INBOX);
    }

    @Test
    void theThreeRowsCarryDistinctFieldIds() throws Exception {
        final List<String> ids = onFxThread(() -> List.of(
                FolderRootRows.workingRoot(view()).field().getId(),
                FolderRootRows.libraryRoot(view()).field().getId(),
                FolderRootRows.inbox(view()).field().getId()));

        assertThat(ids).doesNotHaveDuplicates().doesNotContainNull();
    }

    @Test
    void eachRowExplainsWhatItsOwnFolderIsFor() throws Exception {
        for (final Function<SettingsView, SettingsRows.FolderRow> row
                : List.<Function<SettingsView, SettingsRows.FolderRow>>of(FolderRootRows::workingRoot,
                FolderRootRows::libraryRoot, FolderRootRows::inbox)) {
            final VBox built = onFxThread(() -> row.apply(view()).row());
            assertThat(built.lookupAll(".settings-help")).hasSize(1);
            assertThat(((Label) built.lookup(".settings-help")).getText()).isNotBlank();
        }
    }

    @Test
    void aRowOpensOnTheStoredValueAndOffersTheSuggestionAsAPrompt() throws Exception {
        final TextField field =
                onFxThread(() -> FolderRootRows.workingRoot(viewHolding("D:\\stored")).field());

        assertThat(field.getText()).isEqualTo("D:\\stored");
        assertThat(field.getPromptText()).isEqualTo("D:\\suggested");
    }

    @Test
    void theSuggestionCanBeTakenWithoutTypingIt() throws Exception {
        final VBox row = onFxThread(() -> FolderRootRows.workingRoot(view()).row());
        final var field = (TextField) row.lookup("#settings-working-root");
        assertThat(field.getText()).isEmpty();

        runOnFxThread(() -> useSuggested(row).fire());

        assertThat(field.getText()).isEqualTo("D:\\suggested");
    }

    @Test
    void takingTheSuggestionIsOfferedOnlyWhileTheFieldIsEmpty() throws Exception {
        final VBox row = onFxThread(() -> FolderRootRows.libraryRoot(viewHolding("D:\\chosen")).row());

        assertThat(useSuggested(row).isDisabled()).isTrue();
    }

    @Test
    void anEmptyFieldTakesTheSuggestionOnRightArrowAndOnEnd() throws Exception {
        for (final KeyCode key : List.of(KeyCode.RIGHT, KeyCode.END)) {
            final TextField field = onFxThread(() -> FolderRootRows.inbox(view()).field());
            runOnFxThread(() -> field.fireEvent(press(key)));

            assertThat(field.getText()).isEqualTo("D:\\suggested");
            assertThat(field.getCaretPosition()).isEqualTo("D:\\suggested".length());
        }
    }

    @Test
    void aFieldWithAPathInItKeepsRightArrowAndEndForMoving() throws Exception {
        for (final KeyCode key : List.of(KeyCode.RIGHT, KeyCode.END)) {
            final TextField field =
                    onFxThread(() -> FolderRootRows.inbox(viewHolding("D:\\chosen")).field());
            runOnFxThread(() -> field.fireEvent(press(key)));

            assertThat(field.getText()).isEqualTo("D:\\chosen");
        }
    }

    @Test
    void anEmptyFieldDoesNotTakeTheSuggestionOnTab() throws Exception {
        final TextField field = onFxThread(() -> FolderRootRows.inbox(view()).field());

        runOnFxThread(() -> field.fireEvent(press(KeyCode.TAB)));

        assertThat(field.getText()).isEmpty();
    }

    private static KeyEvent press(final KeyCode key) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false);
    }

    private static Button useSuggested(final VBox row) {
        return row.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> "Use suggested".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Use suggested button on the row"));
    }

    private static String labelOf(final Function<SettingsView, SettingsRows.FolderRow> row) throws Exception {
        final VBox built = onFxThread(() -> row.apply(view()).row());
        return ((Label) built.lookup(".settings-field-label")).getText();
    }

    private static SettingsView view() {
        return viewHolding("");
    }

    private static SettingsView viewHolding(final String value) {
        final var field = new SettingsView.FolderField(value, "D:\\suggested", null);
        return new SettingsView(field, field, field, "external-agent",
                List.of(new SettingsView.ProviderChoice("external-agent", "External agent",
                        new SettingsView.ProviderFields(false, false, true, false), null, null)),
                null, null, null, null, null, null, null, false, null,
                new SettingsView.SecretRow("", null, null, null, false), 224,
                new SettingsView.NumberRange(16, 1024, 16), null, 5,
                new SettingsView.NumberRange(1, 12, 1), null, "SYSTEM",
                List.of(new SettingsView.ThemeOption("SYSTEM", "System default")), null, 200, 200, 200);
    }
}
