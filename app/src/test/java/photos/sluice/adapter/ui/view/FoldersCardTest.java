package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED_FOLDER;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.builtInAWindowThatScrolls;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.inView;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.presenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.rowOf;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.textsOfClass;

// A handful of structural claims rather than a second copy of SettingsPresenterTest. What the
// screen says is the presenter's, and is asserted there. This file guards the wiring only a built
// scene graph can be wrong about: whether a refusal reaches the folder row it belongs to.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class FoldersCardTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    // A refusal at the foot of a page that scrolls is a message about a field the reader cannot see.
    // The saved roots are fine, so the row starts unmarked. Only the folder typed in this test is
    // refused, which is what makes the mark afterwards mean the save put it there.
    @Test
    void aRefusedSaveMarksTheFolderRowAndKeepsASummaryAtTheFoot() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-library-root")).setText(REFUSED_FOLDER);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(textsOfClass(pane, "settings-violation")).contains("No folder could be found here.");
        assertThat(textsOfClass(pane, "settings-save-status"))
                .anyMatch(text -> text.contains("not saved"))
                .noneMatch(text -> text.contains("sluice.paths"));
    }

    // The roots this presenter opens on are fine, so the field starts unmarked. That is what makes
    // the mark afterwards mean this save put it there.
    @Test
    void aRefusedSaveMarksTheFieldAndNotOnlyTheLineUnderIt() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        final var field = (TextField) pane.lookup("#settings-library-root");
        assertThat(field.getPseudoClassStates()).doesNotContain(REFUSED);

        runOnFxThread(() -> {
            field.setText(REFUSED_FOLDER);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(field.getPseudoClassStates()).contains(REFUSED);
    }

    // Save is at the foot, so pressing it means already being at the bottom. That is the position
    // this test starts from. An unmoved page is then a refusal nobody sees, since the field at fault
    // is above the fold.
    @Test
    void aRefusedSaveBringsTheFolderAtFaultIntoView() throws Exception {
        final var pane = (ScrollPane) onFxThread(() -> builtInAWindowThatScrolls(presenterOn("anthropic")));
        final Node row = rowOf(pane, "#settings-library-root");
        runOnFxThread(() -> pane.setVvalue(pane.getVmax()));
        assertThat(inView(pane, row)).isFalse();

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-library-root")).setText(REFUSED_FOLDER);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inView(pane, row)).isTrue();
    }
}
