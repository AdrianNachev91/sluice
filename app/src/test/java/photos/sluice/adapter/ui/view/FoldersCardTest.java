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
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportIsARefusal;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportText;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED_FOLDER;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.builtInAWindowThatScrolls;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.presenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.visionProviderPresenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.inView;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.rowOf;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.scrollOf;
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
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));
        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-library-root")).setText(REFUSED_FOLDER);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(textsOfClass(pane, "settings-violation")).contains("No folder could be found here.");
        assertThat(reportText(pane)).contains("not saved").doesNotContain("sluice.paths");
        assertThat(reportIsARefusal(pane)).isTrue();
    }

    // The roots this presenter opens on are fine, so the field starts unmarked. That is what makes
    // the mark afterwards mean this save put it there.
    @Test
    void aRefusedSaveMarksTheFieldAndNotOnlyTheLineUnderIt() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));
        final var field = (TextField) pane.lookup("#settings-library-root");
        assertThat(field.getPseudoClassStates()).doesNotContain(REFUSED);

        runOnFxThread(() -> {
            field.setText(REFUSED_FOLDER);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(field.getPseudoClassStates()).contains(REFUSED);
    }

    @Test
    void aRefusedSaveDoesNotTravelToTheFolderAtFault() throws Exception {
        final Parent page = onFxThread(() -> builtInAWindowThatScrolls(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));
        final ScrollPane scroll = scrollOf(page);
        final Node row = rowOf(scroll, "#settings-library-root");
        runOnFxThread(() -> scroll.setVvalue(scroll.getVmax()));
        assertThat(inView(scroll, row)).isFalse();

        runOnFxThread(() -> {
            ((TextField) page.lookup("#settings-library-root")).setText(REFUSED_FOLDER);
            ((Button) page.lookup("#settings-save-button")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inView(scroll, row)).isFalse();
        assertThat(reportText(page)).contains("not saved");
    }

    @Test
    void theCardSaysNoWorkRunsUntilAllThreeFoldersAreSet() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(pane.lookup("#folder-roots-required-legend")).isNotNull();
    }

    @Test
    void theFolderCardCarriesTheRulesAboutHowTheThreeRootsRelate() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(pane.lookup("#folder-roots-help")).isNotNull();
    }

    @Test
    void saveIsPinnedOutsideWhatScrolls() throws Exception {
        final Parent page = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(page.lookup("#settings-save-button")).isNotNull();
        assertThat(scrollOf(page).lookup("#settings-save-button")).isNull();
    }
}
