package photos.sluice.adapter.ui.view;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.ThemeSelection;
import photos.sluice.application.port.out.ThemeChoice;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Everything runs on the FX thread, because dressing a scene reads the desktop's preferences and
// that call refuses any other thread.
class StylesheetTest {

    private static final String DARK_SHEET = "sluice-dark.css";

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void resetSelection() throws Exception {
        WaitForAsyncUtils.asyncFx(ThemeSelection::clear).get(10, TimeUnit.SECONDS);
    }

    @Test
    void aDressedSceneWearsTheDarkOverlayOnlyWhileTheDarkLookIsInForce() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            return sheetsOf(scene);
        })).anyMatch(sheet -> sheet.endsWith(DARK_SHEET));

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            return sheetsOf(scene);
        })).noneMatch(sheet -> sheet.endsWith(DARK_SHEET));
    }

    // A listener that fires once leaves the dark sheet in place from the first change, so asking
    // for it again afterwards proves nothing. The second look has to be one that takes the sheet
    // away.
    @Test
    void aSceneKeepsTrackingTheLookAfterTheFirstChange() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        final List<String> afterFirst = onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            return sheetsOf(scene);
        });
        final List<String> afterSecond = onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            return sheetsOf(scene);
        });

        assertThat(afterFirst).anyMatch(sheet -> sheet.endsWith(DARK_SHEET));
        assertThat(afterSecond).noneMatch(sheet -> sheet.endsWith(DARK_SHEET));
    }

    @Test
    void everyLookKeepsTheBaseSheetUnderneath() throws Exception {
        final Scene scene = onFxThread(() -> Stylesheet.applyTo(new Scene(new StackPane())));

        for (final ThemeChoice choice : ThemeChoice.values()) {
            final List<String> sheets = onFxThread(() -> {
                ThemeSelection.set(choice);
                return sheetsOf(scene);
            });
            assertThat(sheets).isNotEmpty();
            assertThat(sheets.getFirst()).endsWith("sluice.css");
        }
    }

    private static List<String> sheetsOf(final Scene scene) {
        return List.copyOf(scene.getStylesheets());
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
