package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.control.Spinner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.presenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;

// A handful of structural claims rather than a second copy of SettingsPresenterTest. What the
// screen says is the presenter's, and is asserted there. This file guards the wiring only a built
// scene graph can be wrong about: whether a typed number actually commits.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class PhotoSheetsCardTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    // An editable Spinner does not commit its editor's text on its own. A number typed and then left
    // behind would be discarded, in favour of whatever the spinner last held.
    @Test
    void aTypedNumberIsTakenWhenTheFieldIsLeft() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        final var tileSize = (Spinner<?>) pane.lookup("#settings-tile-size");
        assertThat(tileSize.getValue()).isEqualTo(224);

        // The listener fires on losing focus, so the editor has to hold it first. setText alone
        // leaves nothing to lose.
        runOnFxThread(() -> {
            tileSize.getEditor().requestFocus();
            tileSize.getEditor().setText("300");
            pane.lookup("#settings-library-root").requestFocus();
        });

        assertThat(tileSize.getValue()).isEqualTo(300);
    }

    // Clearing the field is how a value gets replaced, so empty is allowed while typing. Leaving it
    // empty is not a value, and the field has to show what it will actually save.
    @Test
    void anEmptiedFieldShowsItsValueAgainWhenLeft() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        final var tileSize = (Spinner<?>) pane.lookup("#settings-tile-size");

        runOnFxThread(() -> {
            tileSize.getEditor().requestFocus();
            tileSize.getEditor().setText("");
            pane.lookup("#settings-library-root").requestFocus();
        });

        assertThat(tileSize.getEditor().getText()).isEqualTo("224");
        assertThat(tileSize.getValue()).isEqualTo(224);
    }
}
