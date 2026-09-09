package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.MontageConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.oneStoredKey;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onlyRefusingOneFolder;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.refusingLibraryRootUseCase;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.threeProviders;

// A handful of structural claims rather than a second copy of SettingsPresenterTest. What the
// screen says is the presenter's, and is asserted there. This file guards the wiring only a built
// scene graph can be wrong about: whether picking a theme actually saves it.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class AppearanceCardTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    // A theme button carries its option's id as user data, and a save reads it back off whichever
    // is selected. Neither cast fails loudly, so a wrong id would save a theme nobody picked.
    @Test
    void savingCarriesTheThemePickedOnTheScreen() throws Exception {
        final List<Settings> saved = new ArrayList<>();
        final Presenters presenters = presenterSavingInto(saved);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));
        assertThat(onFxThread(() -> selectedTheme(pane))).isEqualTo("DARK");

        runOnFxThread(() -> {
            themeButton(pane, "LIGHT").setSelected(true);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(saved).isNotEmpty();
        assertThat(saved.getLast().theme()).isEqualTo(ThemeChoice.LIGHT);
    }

    // A radio picked and never followed by Save. That is what tells "the theme saves on its own"
    // apart from "the ordinary save just happens to carry it too".
    @Test
    void pickingAThemeSavesItWithoutTouchingSave() throws Exception {
        final List<Settings> saved = new ArrayList<>();
        final Presenters presenters = presenterSavingInto(saved);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        runOnFxThread(() -> themeButton(pane, "LIGHT").setSelected(true));

        assertThat(saved).singleElement().extracting(Settings::theme).isEqualTo(ThemeChoice.LIGHT);
    }

    // The attempt count is what pins the re-entrancy guard: without it the count reads two, and
    // every other assertion here passes either way.
    @Test
    void aThemeTheFileWouldNotTakeLeavesTheRadioWhereItWasAndSaysWhy() throws Exception {
        final List<Settings> attempted = new ArrayList<>();
        final Presenters presenters = presenterRefusingEverySave(attempted);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        runOnFxThread(() -> themeButton(pane, "LIGHT").setSelected(true));

        assertThat(attempted).singleElement().extracting(Settings::theme).isEqualTo(ThemeChoice.LIGHT);
        assertThat(onFxThread(() -> selectedTheme(pane))).isEqualTo("DARK");
        assertThat(onFxThread(() -> ((TextArea) pane.lookup(".settings-banner-text")).getText()))
                .startsWith("Your theme was not saved.")
                .contains("could not be reached");
    }

    private static RadioButton themeButton(final Parent pane, final String themeId) {
        return ((Parent) pane.lookup("#settings-theme")).getChildrenUnmodifiable().stream()
                .map(RadioButton.class::cast)
                .filter(button -> themeId.equals(button.getUserData()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no theme button for '" + themeId + "'"));
    }

    private static String selectedTheme(final Parent pane) {
        return (String) ((Parent) pane.lookup("#settings-theme")).getChildrenUnmodifiable().stream()
                .map(RadioButton.class::cast)
                .filter(RadioButton::isSelected)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no theme is selected"))
                .getUserData();
    }

    private record Presenters(SettingsPresenter settings, VisionProviderPresenter vision) {
    }

    private static Presenters presenterSavingInto(final List<Settings> saved) {
        return presenterOver(saved::add);
    }

    private static Presenters presenterRefusingEverySave(final List<Settings> attempted) {
        return presenterOver(settings -> {
            attempted.add(settings);
            throw new UncheckedIOException(new IOException("the settings file is held open"));
        });
    }

    private static Presenters presenterOver(final Consumer<Settings> save) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.DARK);
        final var useCase = new SettingsUseCase() {
            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public Optional<SettingOverride> higherPrecedenceOverride(final String property) {
                return Optional.empty();
            }

            @Override
            public void save(final Settings toSave) {
                save.accept(toSave);
            }
        };
        final var vision = new VisionProviderPresenter(oneStoredKey(), threeProviders(), useCase);
        return new Presenters(
                new SettingsPresenter(useCase, refusingLibraryRootUseCase(), onlyRefusingOneFolder(),
                        threeProviders(), vision, new FxProgressPort()),
                vision);
    }
}
