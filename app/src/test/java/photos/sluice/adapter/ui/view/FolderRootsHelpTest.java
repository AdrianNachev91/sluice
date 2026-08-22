package photos.sluice.adapter.ui.view;

import javafx.scene.control.Label;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;

// Whether each rule is TRUE is a reading of RootLayout, and no test can make that comparison. What
// this guards is that a rule cannot quietly leave. Both cards draw this one panel, so a rule
// dropped here is dropped from every surface a user could have learned it from.
class FolderRootsHelpTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void thePanelHoldsEveryRuleSomebodyChoosingFoldersNeeds() throws Exception {
        assertThat(rules()).hasSize(4).noneMatch(String::isBlank);
    }

    @Test
    void everyRuleWraps() throws Exception {
        final Node panel = onFxThread(FolderRootsHelp::panel);

        assertThat(panel.lookupAll(".folder-roots-help-rule"))
                .isNotEmpty()
                .allMatch(node -> ((Label) node).isWrapText());
    }

    @Test
    void everyRootIsNamedBySomeRule() throws Exception {
        final String said = String.join(" ", rules()).toLowerCase(Locale.ROOT);

        assertThat(said).contains("working root").contains("library").contains("inbox");
    }

    private static List<String> rules() throws Exception {
        final Node panel = onFxThread(FolderRootsHelp::panel);
        return panel.lookupAll(".folder-roots-help-rule").stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }
}
