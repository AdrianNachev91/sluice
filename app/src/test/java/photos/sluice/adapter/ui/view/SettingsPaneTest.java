package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopiedAndMoved;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.MovedWithAFreshIndex;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.service.JobRunner;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.oneStoredKey;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onlyRefusingOneFolder;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.textsOfClass;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.threeProviders;

// What SettingsPane keeps for itself once every card owns its own rows: the assembly, and the
// save wiring. Specifically the library-root move dialog, the one flow this file still drives
// end to end. Everything else the screen does is asserted in its own card's test class.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class SettingsPaneTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    // A save that would move the library root raises a dialog, and only after it is answered does
    // the move actually run. Each of the three exercises one real branch inside
    // resolveLibraryRootMove: the copy resolution, the fresh-index resolution, and Cancel's early
    // return before anything runs.
    @Test
    void choosingCopyRunsTheCopyMoveAndShowsItsOwnOutcome() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new CopiedAndMoved(5, 5));
        };
        final Parent pane = onFxThread(() -> built(presenterNeedingLibraryRootResolution(library)));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> ((Button) pane.lookup("#settings-save-button")).fire());
        answerLibraryMoveDialog("Copy the old library across");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pane.lookup("#settings-saved-banner") != null);

        assertThat(received).containsExactly(LibraryRootResolution.COPY_AND_KEEP_INDEX);
        assertThat(onFxThread(() -> bannerText(pane))).isEqualTo("Copied 5 file(s) into the new library. "
                + "The old folder is untouched; remove it by hand once you have checked it.");
    }

    @Test
    void choosingStartFreshRunsTheFreshMoveAndShowsItsOwnOutcome() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new MovedWithAFreshIndex(null));
        };
        final Parent pane = onFxThread(() -> built(presenterNeedingLibraryRootResolution(library)));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> ((Button) pane.lookup("#settings-save-button")).fire());
        answerLibraryMoveDialog("Start the record fresh");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pane.lookup("#settings-saved-banner") != null);

        assertThat(received).containsExactly(LibraryRootResolution.START_A_FRESH_INDEX);
        assertThat(onFxThread(() -> bannerText(pane))).isEqualTo("The library root moved. Sluice had no record "
                + "yet of what was already in the library, so there was nothing to set aside.");
    }

    // onSave returning after Cancel proves nothing on its own. The library use case runs on its own
    // virtual thread, so a check made the moment the button handler returns can beat it there.
    // Proving the negative needs a bounded wait instead, the same shape a latch's own timed await
    // gives when there is no latch to ask.
    @Test
    void cancellingTheMoveDialogRunsNoMoveAtAll() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new CopiedAndMoved(1, 1));
        };
        final Parent pane = onFxThread(() -> built(presenterNeedingLibraryRootResolution(library)));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> ((Button) pane.lookup("#settings-save-button")).fire());
        answerLibraryMoveDialog("Cancel");
        saveFired.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> WaitForAsyncUtils.waitFor(500, TimeUnit.MILLISECONDS, () -> !received.isEmpty()))
                .isInstanceOf(TimeoutException.class);
        // A false positive: the IDE infers lookup() cannot answer null through onFxThread's generic
        // return, which is exactly what this assertion is proving otherwise.
        //noinspection DataFlowIssue
        assertThat(onFxThread(() -> pane.lookup("#settings-saved-banner"))).isNull();
    }

    // Not the failure handler. SettingsPresenter.moveLibraryRoot catches every RuntimeException a
    // move can throw and answers a failed MoveOutcome instead, so task.call() has no path left that
    // reaches task.setOnFailed through this presenter. This is the succeeded()-but-failed branch of
    // setOnSucceeded instead: the move ran, and what it ran into is the outcome, not a thrown one.
    @Test
    void aRefusedMoveShowsARefusalRatherThanABanner() throws Exception {
        final var jobRunner = new JobRunner();
        final LibraryRootUseCase library = (_, _) -> jobRunner.submit(_ -> {
            throw new IllegalStateException("cannot move: disk full");
        });
        final Parent pane = onFxThread(() -> built(presenterNeedingLibraryRootResolution(library)));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> ((Button) pane.lookup("#settings-save-button")).fire());
        answerLibraryMoveDialog("Copy the old library across");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> textsOfClass(pane, "settings-save-status").stream().anyMatch(text -> text.contains("disk full")));

        assertThat(pane.lookup("#settings-saved-banner")).isNull();
    }

    private static String bannerText(final Parent pane) {
        final var banner = (HBox) pane.lookup("#settings-saved-banner");
        return ((Label) banner.getChildren().getFirst()).getText();
    }

    // Polls from the test thread, since the FX thread is inside the dialog's own nested event loop
    // and cannot itself answer a lookup. A Platform.runLater task queued from any thread still runs
    // during that loop, which is what lets this method reach in and press one of its buttons.
    private static void answerLibraryMoveDialog(final String buttonText) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> currentDialogPane().isPresent());
        runOnFxThread(() -> {
            final DialogPane dialogPane = currentDialogPane().orElseThrow();
            dialogPane.applyCss();
            dialogPane.layout();
            final Button button = dialogPane.lookupAll(".button").stream()
                    .map(Button.class::cast)
                    .filter(candidate -> buttonText.equals(candidate.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no dialog button labelled '" + buttonText + "'"));
            button.fire();
        });
    }

    private static Optional<DialogPane> currentDialogPane() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(Window::getScene)
                .filter(scene -> scene != null && scene.getRoot() instanceof DialogPane)
                .map(scene -> (DialogPane) scene.getRoot())
                .findFirst();
    }

    // Its own settings use case rather than a shared fixture: this is the one save that must throw
    // the resolution exception rather than succeed or refuse.
    private static SettingsPresenter presenterNeedingLibraryRootResolution(final LibraryRootUseCase libraryRoot) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        final var useCase = new SettingsUseCase() {
            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
                return Optional.empty();
            }

            @Override
            public void save(final Settings toSave) {
                throw new LibraryRootMoveNeedsAResolutionException(Path.of("D:\\library"),
                        "sluice.paths.library-root would move");
            }
        };
        return new SettingsPresenter(useCase, libraryRoot, oneStoredKey(), onlyRefusingOneFolder(), threeProviders());
    }
}
