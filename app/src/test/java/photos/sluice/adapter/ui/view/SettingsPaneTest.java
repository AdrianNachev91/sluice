package photos.sluice.adapter.ui.view;

import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.LibraryRootResolutionRequiredException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopiedAndMoved;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.MovedWithFreshIndex;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.service.JobRunner;
import photos.sluice.domain.cull.MontageConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.answerDialog;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.mounted;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.oneStoredKey;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.presenterStoringOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.visionProviderPresenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onlyRefusingOneFolder;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportIsARefusal;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportText;
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

    @Test
    void aScreenDrawnFromDiskHasNothingToLose() throws Exception {
        final SettingsPane.Mounted screen =
                onFxThread(() -> mounted(presenterStoringOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(onFxThread(() -> screen.hasUnsavedEdits().getAsBoolean())).isFalse();
    }

    @Test
    void aTypedFolderPathIsSomethingToLose() throws Exception {
        final SettingsPane.Mounted screen =
                onFxThread(() -> mounted(presenterStoringOn("anthropic"), visionProviderPresenterOn("anthropic")));

        runOnFxThread(() -> ((TextField) screen.node().lookup("#settings-inbox")).setText("D:\\somewhere-else"));

        assertThat(onFxThread(() -> screen.hasUnsavedEdits().getAsBoolean())).isTrue();
    }

    // A save rebuilds every card, so the question has to reach the controls that draw put up. The
    // edit has to come after the save. The fields the save replaced hold exactly what it stored, so
    // reading those would agree with disk and report nothing to lose.
    @Test
    void anEditAfterASaveIsStillSomethingToLose() throws Exception {
        final SettingsPane.Mounted screen =
                onFxThread(() -> mounted(presenterStoringOn("anthropic"), visionProviderPresenterOn("anthropic")));
        final var pane = (Parent) screen.node();

        runOnFxThread(() -> ((Button) pane.lookup("#settings-save-button")).fire());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> reportText(pane).contains("saved"));
        runOnFxThread(() -> ((TextField) pane.lookup("#settings-inbox")).setText("D:\\typed-after-the-save"));

        assertThat(onFxThread(() -> screen.hasUnsavedEdits().getAsBoolean())).isTrue();
    }

    @Test
    void aSaveLeavesNothingToLose() throws Exception {
        final SettingsPane.Mounted screen =
                onFxThread(() -> mounted(presenterStoringOn("anthropic"), visionProviderPresenterOn("anthropic")));
        final var pane = (Parent) screen.node();

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-inbox")).setText("D:\\somewhere-else");
            ((Button) pane.lookup("#settings-save-button")).fire();
        });
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> reportText(pane).contains("saved"));

        assertThat(onFxThread(() -> screen.hasUnsavedEdits().getAsBoolean())).isFalse();
    }

    @Test
    void choosingCopyRunsTheCopyMoveAndShowsItsOwnOutcome() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new CopiedAndMoved(5, 5, Path.of("/old-library")));
        };
        final Presenters presenters = presenterNeedingLibraryRootResolution(library);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> saveMovingTheLibraryRoot(pane));
        answerDialog("Copy the old Library across");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pane.lookup("#settings-report-banner") != null);

        assertThat(received).containsExactly(LibraryRootResolution.COPY_AND_KEEP_INDEX);
        assertThat(onFxThread(() -> bannerText(pane))).isEqualTo("Copied 5 file(s) into the new Library. Every one of them is still in "
                + Path.of("/old-library") + ", so remove that folder by hand once you have "
                + "checked the new one.");
    }

    @Test
    void choosingStartFreshRunsTheFreshMoveAndShowsItsOwnOutcome() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new MovedWithFreshIndex(null));
        };
        final Presenters presenters = presenterNeedingLibraryRootResolution(library);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> saveMovingTheLibraryRoot(pane));
        answerDialog("Start the record fresh");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pane.lookup("#settings-report-banner") != null);

        assertThat(received).containsExactly(LibraryRootResolution.START_A_FRESH_INDEX);
        assertThat(onFxThread(() -> bannerText(pane))).isEqualTo("The Library root moved. There was no record "
                + "yet of what was already in the Library, so there was nothing to keep.");
    }

    // onSave returning after Cancel proves nothing on its own. The library use case runs on its own
    // virtual thread, so a check made the moment the button handler returns can beat it there.
    // Proving the negative needs a bounded wait instead.
    @Test
    void cancellingTheMoveDialogRunsNoMoveAtAll() throws Exception {
        final var jobRunner = new JobRunner();
        final var received = new ArrayList<LibraryRootResolution>();
        final LibraryRootUseCase library = (_, resolution) -> {
            received.add(resolution);
            return jobRunner.submit(_ -> new CopiedAndMoved(1, 1, Path.of("/old-library")));
        };
        final Presenters presenters = presenterNeedingLibraryRootResolution(library);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> saveMovingTheLibraryRoot(pane));
        answerDialog("Cancel");
        saveFired.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> WaitForAsyncUtils.waitFor(500, TimeUnit.MILLISECONDS, () -> !received.isEmpty()))
                .isInstanceOf(TimeoutException.class);
        // A false positive: the IDE infers lookup() cannot answer null through onFxThread's generic
        // return, which is exactly what this assertion is proving otherwise.
        //noinspection DataFlowIssue
        assertThat(onFxThread(() -> pane.lookup("#settings-report-banner"))).isNull();
    }

    // Not the failure handler, but the succeeded()-but-failed branch: the move ran, and what it ran
    // into is the outcome rather than a thrown exception.
    @Test
    void aFailedMoveIsReportedAsARefusalRatherThanAConfirmation() throws Exception {
        final var jobRunner = new JobRunner();
        final LibraryRootUseCase library = (_, _) -> jobRunner.submit(_ -> {
            throw new IllegalStateException("cannot move: disk full");
        });
        final Presenters presenters = presenterNeedingLibraryRootResolution(library);
        final Parent pane = onFxThread(() -> built(presenters.settings(), presenters.vision()));

        final var saveFired = WaitForAsyncUtils.asyncFx(() -> saveMovingTheLibraryRoot(pane));
        answerDialog("Copy the old Library across");
        saveFired.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> reportText(pane).contains("disk full"));

        assertThat(reportIsARefusal(pane)).isTrue();
    }

    // A library root other than the one in force is the only thing that raises the dialog.
    private static void saveMovingTheLibraryRoot(final Parent pane) {
        ((TextField) pane.lookup("#settings-library-root")).setText("D:\\moved-library");
        ((Button) pane.lookup("#settings-save-button")).fire();
    }

    private static String bannerText(final Parent pane) {
        final var banner = (HBox) pane.lookup("#settings-report-banner");
        return ((TextArea) banner.getChildren().getFirst()).getText();
    }

    private record Presenters(SettingsPresenter settings, VisionProviderPresenter vision) {
    }

    // Refuses only while the library root being saved differs from the one in force, which is the
    // real seam's own rule. A double that refused every save would also refuse the one the presenter
    // makes after the move. That save keeps the rest of what the user was storing.
    private static Presenters presenterNeedingLibraryRootResolution(final LibraryRootUseCase libraryRoot) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        final var libraryRootInForce = new AtomicReference<>("D:\\library");
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
                if (!libraryRootInForce.get().equals(toSave.paths().libraryRoot())) {
                    throw new LibraryRootResolutionRequiredException(Path.of(libraryRootInForce.get()),
                            "sluice.paths.library-root would move");
                }
            }
        };
        // The move is what puts the new root in force, so the save after it has nothing left to ask
        // about. Recorded when the job is submitted rather than when it finishes, since the
        // presenter joins that job before saving and nothing else reads this in between.
        final LibraryRootUseCase moving = (newLibraryRoot, resolution) -> {
            final var handle = libraryRoot.moveLibraryRoot(newLibraryRoot, resolution);
            libraryRootInForce.set(newLibraryRoot.toString());
            return handle;
        };
        final var vision = new VisionProviderPresenter(oneStoredKey(), threeProviders(), useCase);
        return new Presenters(
                new SettingsPresenter(useCase, moving, onlyRefusingOneFolder(), threeProviders(), vision,
                        new FxProgressPort()),
                vision);
    }
}
