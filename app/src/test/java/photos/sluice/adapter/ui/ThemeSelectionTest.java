package photos.sluice.adapter.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.application.port.out.ThemeChoice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Everything here runs on the FX thread, because resolving a look reads the desktop's preferences
// and that call refuses any other thread. Which is also why nothing outside adapter/ui resolves one.
class ThemeSelectionTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void resetSelection() throws Exception {
        runOnFxThread(ThemeSelection::clear);
    }

    // Only as strong as the machine it runs on. Both sides read the same desktop scheme. So an
    // implementation ignoring the scheme, and hardcoding whichever look the desktop is set to, would
    // pass here. Probed on a light desktop: hardcoding DARK fails this. On a dark desktop it would
    // not, and nothing in the suite would catch it. Setting the scheme is not ours to do.
    @Test
    void followsTheDesktopUntilAChoiceIsSet() throws Exception {
        assertThat(onFxThread(ThemeSelectionTest::effectiveLook)).isEqualTo(onFxThread(ThemeSelectionTest::desktopLook));
    }

    @Test
    void aChosenLookOverridesTheDesktop() throws Exception {
        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            return effectiveLook();
        })).isEqualTo(Theme.DARK);

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            return effectiveLook();
        })).isEqualTo(Theme.LIGHT);
    }

    @Test
    void goingBackToSystemHandsTheAnswerBackToTheDesktop() throws Exception {
        final Theme afterReturning = onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.DARK);
            ThemeSelection.set(ThemeChoice.SYSTEM);
            return effectiveLook();
        });

        assertThat(afterReturning).isEqualTo(onFxThread(ThemeSelectionTest::desktopLook));
    }

    // The whole point of the property: a window binds once and is told, rather than polling.
    @Test
    void watchersAreToldWhenTheChoiceChanges() throws Exception {
        final List<Theme> seen = onFxThread(() -> {
            final var changes = new ArrayList<Theme>();
            ThemeSelection.effectiveTheme().addListener((_, _, current) -> changes.add(current));
            ThemeSelection.set(ThemeChoice.DARK);
            ThemeSelection.set(ThemeChoice.LIGHT);
            return changes;
        });

        assertThat(seen).containsExactly(Theme.DARK, Theme.LIGHT);
    }

    private static Theme effectiveLook() {
        return ThemeSelection.effectiveTheme().getValue();
    }

    private static Theme desktopLook() {
        return Theme.matching(Platform.getPreferences().getColorScheme());
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
