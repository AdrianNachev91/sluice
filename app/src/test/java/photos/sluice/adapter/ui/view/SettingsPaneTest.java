package photos.sluice.adapter.ui.view;

import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsView;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopiedAndMoved;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.MovedWithAFreshIndex;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.application.service.JobRunner;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A handful of structural claims rather than a second copy of SettingsPresenterTest. What the
// screen says is the presenter's, and is asserted there. This file guards the wiring only a built
// scene graph can be wrong about. Which controls a provider shows, which parent a block ends up in,
// and whether a refusal reaches the row it belongs to.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class SettingsPaneTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");
    private static final SecretId OTHER_KEY = new SecretId("other-api", "OTHER_API_KEY");
    private static final String REFUSED_FOLDER = "D:\\moved-away";
    private static final PseudoClass REFUSED = PseudoClass.getPseudoClass("refused");

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void theApiKeyBlockSitsInsideTheProviderCard() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));

        final Node apiKey = pane.lookup("#settings-api-key");
        assertThat(apiKey).isNotNull();
        assertThat(labelsIn(cardOwning(apiKey))).contains("VISION PROVIDER");
    }

    @Test
    void choosingAProviderShowsOnlyTheControlsThatProviderUses() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));

        assertThat(shown(pane, "#settings-provider-fields")).isTrue();
        assertThat(shown(pane, "#settings-api-key")).isTrue();
        assertThat(shown(pane, "#settings-watch-mode")).isFalse();

        runOnFxThread(() -> select(pane, "external-agent"));

        assertThat(shown(pane, "#settings-provider-fields")).isFalse();
        assertThat(shown(pane, "#settings-api-key")).isFalse();
        assertThat(shown(pane, "#settings-watch-mode")).isTrue();
    }

    // Both providers here take a key, so a block following the wrong one still looks plausible.
    // Only anthropic's is stored, which is what makes the buttons say which provider is showing.
    @Test
    void theApiKeyBlockFollowsTheChosenProviderRatherThanTheSavedOne() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));

        assertThat(buttonsIn(pane.lookup("#settings-api-key"))).contains("Replace");

        runOnFxThread(() -> select(pane, "other-api"));

        assertThat(buttonsIn(pane.lookup("#settings-api-key"))).contains("Save").doesNotContain("Replace");
    }

    // The provider's own controls are toggled rather than rebuilt, and this is why. A rebuild reads
    // the saved settings back, replacing a model just typed with the one on disk. The model field
    // is what the listener reaches; a folder field would survive any implementation.
    @Test
    void switchingProviderAndBackKeepsAnUnsavedModel() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-model")).setText("a-model-not-yet-saved");
            select(pane, "external-agent");
            select(pane, "anthropic");
        });

        assertThat(onFxThread(() -> ((TextField) pane.lookup("#settings-model")).getText()))
                .isEqualTo("a-model-not-yet-saved");
    }

    // A theme button carries its option's id as user data, and a save reads it back off whichever
    // is selected. Nothing else in the suite reaches that pair of casts, and neither one fails
    // loudly: a wrong id would save a theme the user did not pick.
    @Test
    void savingCarriesTheThemePickedOnTheScreen() throws Exception {
        final List<Settings> saved = new ArrayList<>();
        final Parent pane = onFxThread(() -> built(presenterSavingInto(saved)));
        assertThat(onFxThread(() -> selectedTheme(pane))).isEqualTo("DARK");

        runOnFxThread(() -> {
            themeButton(pane, "LIGHT").setSelected(true);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });

        assertThat(saved).isNotEmpty();
        assertThat(saved.getLast().theme()).isEqualTo(ThemeChoice.LIGHT);
    }

    // The one above cannot tell "the theme saves on its own" apart from "the ordinary save just
    // happens to carry it too". A radio picked and never followed by Save settles which one it is.
    @Test
    void pickingAThemeSavesItWithoutTouchingSave() throws Exception {
        final List<Settings> saved = new ArrayList<>();
        final Parent pane = onFxThread(() -> built(presenterSavingInto(saved)));

        runOnFxThread(() -> themeButton(pane, "LIGHT").setSelected(true));

        assertThat(saved).singleElement().extracting(Settings::theme).isEqualTo(ThemeChoice.LIGHT);
    }

    // Nothing was refused here, so the empty-violations assertion is half of what the name claims.
    @Test
    void anUnknownConfiguredProviderIsCautionedAboutWithoutBeingRefused() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("a-provider-this-build-lacks")));

        assertThat(textsOfClass(pane, "settings-caution"))
                .anyMatch(text -> text.contains("a-provider-this-build-lacks"));
        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();
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

    // A key is stored the moment its own button is pressed, so the page around it holds choices that
    // were never saved. This screen opens on external-agent, so anthropic can only be showing
    // because the dropdown was changed and left unsaved, which is what makes the check meaningful.
    @Test
    void savingAKeyLeavesAProviderPickedButNotYetSavedAlone() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("external-agent")));
        runOnFxThread(() -> select(pane, "anthropic"));
        assertThat(chosenProvider(pane)).isEqualTo("anthropic");

        runOnFxThread(() -> {
            ((PasswordField) pane.lookup("#settings-api-key-entry")).setText("a-key");
            ((Button) pane.lookup("#settings-api-key-save")).fire();
        });

        assertThat(chosenProvider(pane)).isEqualTo("anthropic");
    }

    @Test
    void savingAKeySaysSoBesideTheRowRatherThanAtTheTopOfThePage() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        assertThat(textsOfClass(pane, "settings-confirmation")).isEmpty();

        runOnFxThread(() -> {
            ((PasswordField) pane.lookup("#settings-api-key-entry")).setText("a-key");
            ((Button) pane.lookup("#settings-api-key-save")).fire();
        });

        // Looked for inside the key block rather than anywhere on the page. That is the half of the
        // name a search of the whole pane would not prove.
        assertThat(textsOfClass((Parent) pane.lookup("#settings-api-key"), "settings-confirmation"))
                .contains("API key saved.");
    }

    @Test
    void removingAKeySaysSoToo() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));

        runOnFxThread(() -> ((Button) pane.lookup("#settings-api-key-remove")).fire());

        assertThat(textsOfClass(pane, "settings-confirmation")).contains("API key removed.");
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

    // Switching provider does not rebuild the pane, so anything a refusal put on screen would
    // otherwise outlive the choices it was about.
    @Test
    void changingProviderTakesTheLastRefusalOffTheScreen() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic")));
        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-model")).setText("");
            ((Button) pane.lookup("#settings-save-button")).fire();
        });
        final var model = (TextField) pane.lookup("#settings-model");
        assertThat(textsOfClass(pane, "settings-violation")).isNotEmpty();
        assertThat(textsOfClass(pane, "settings-save-status")).anyMatch(text -> !text.isEmpty());
        assertThat(model.getPseudoClassStates()).contains(REFUSED);

        runOnFxThread(() -> select(pane, "external-agent"));

        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();
        assertThat(textsOfClass(pane, "settings-save-status")).allMatch(String::isEmpty);
        assertThat(model.getPseudoClassStates()).doesNotContain(REFUSED);
    }

    // Save is at the foot, so pressing it means already being at the bottom. That is the position
    // these two start from. An unmoved page is then a refusal nobody sees, since the field at fault
    // is above the fold in both cases.
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

    @Test
    void aSaveRefusedForABlankModelBringsTheModelIntoView() throws Exception {
        final var pane = (ScrollPane) onFxThread(() -> builtInAWindowThatScrolls(presenterOn("anthropic")));
        final Node row = rowOf(pane, "#settings-model");
        runOnFxThread(() -> pane.setVvalue(pane.getVmax()));
        assertThat(inView(pane, row)).isFalse();

        runOnFxThread(() -> {
            ((TextField) pane.lookup("#settings-model")).setText("");
            ((Button) pane.lookup("#settings-save-button")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inView(pane, row)).isTrue();
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
                "anthropic", new CullProviderSettings("a-model", null, false, 2), List.of(),
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

    private static Node rowOf(final ScrollPane pane, final String fieldId) {
        return pane.lookup(fieldId).getParent().getParent();
    }

    // Which slice of the page is on screen, and whether a node falls inside it. Worked out from the
    // scroll position rather than from the viewport's own origin, which a ScrollPane only moves
    // when its skin next runs. The node's position is taken in the body's coordinates, where
    // scrolling does not move anything.
    private static boolean inView(final ScrollPane pane, final Node node) {
        final var body = (Parent) pane.getContent();
        final double viewportHeight = pane.getViewportBounds().getHeight();
        final double scrollable = body.getBoundsInLocal().getHeight() - viewportHeight;
        final double firstVisible = pane.getVvalue() / pane.getVmax() * scrollable;
        final Bounds where = body.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return where.getMinY() >= firstVisible && where.getMaxY() <= firstVisible + viewportHeight;
    }

    // A window short enough that most of the page is off screen. At the height the other tests use,
    // the page is barely taller than the viewport. Nothing is ever out of view there, so a claim
    // about bringing something into view cannot be made either way.
    private static Parent builtInAWindowThatScrolls(final SettingsPresenter presenter) {
        return built(presenter, 300);
    }

    private static Parent built(final SettingsPresenter presenter) {
        return built(presenter, 700);
    }

    private static Parent built(final SettingsPresenter presenter, final int windowHeight) {
        final Parent pane = (Parent) SettingsPane.pane(presenter);
        // In a scene and laid out before anything is looked up. A ScrollPane holds its content
        // through a skin, and the skin is built when CSS is applied, so a lookup before that finds
        // nothing inside it.
        final var scene = new Scene(new StackPane(pane), 900, windowHeight);
        // Shown, because focus is a window's to give. A scene with no window on screen has nobody
        // to take focus from, so requesting it moves nothing and anything driven by losing it never
        // happens.
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return pane;
    }

    private static String chosenProvider(final Parent pane) {
        @SuppressWarnings("unchecked")
        final var box = (ComboBox<SettingsView.ProviderChoice>) pane.lookup("#settings-provider");
        return box.getSelectionModel().getSelectedItem().id();
    }

    private static void select(final Parent pane, final String providerId) {
        @SuppressWarnings("unchecked")
        final var box = (ComboBox<SettingsView.ProviderChoice>) pane.lookup("#settings-provider");
        box.getSelectionModel().select(box.getItems().stream()
                .filter(choice -> choice.id().equals(providerId)).findFirst().orElseThrow());
        pane.applyCss();
        pane.layout();
    }

    private static boolean shown(final Parent pane, final String id) {
        final Node node = pane.lookup(id);
        return node != null && node.isVisible() && node.isManaged();
    }

    // Throws rather than answering null, because a node this is asked about is one the test claims
    // sits in a card. Having no card above it is the failure, not an answer to assert on.
    private static Node cardOwning(final Node node) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getStyleClass().contains("card")) {
                return parent;
            }
        }
        throw new AssertionError("no card owns " + node.getId());
    }

    private static List<String> labelsIn(final Node root) {
        return root.lookupAll(".label").stream().map(node -> ((Label) node).getText()).toList();
    }

    private static List<String> buttonsIn(final Node root) {
        return root.lookupAll(".button").stream().map(node -> ((Button) node).getText()).toList();
    }

    // Only what the screen is actually saying. Every folder row builds a violation label whether or
    // not it has anything to report. Counting the empty ones makes a clean screen look full of
    // messages.
    private static List<String> textsOfClass(final Parent pane, final String styleClass) {
        return pane.lookupAll("." + styleClass).stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(text -> !text.isEmpty())
                .toList();
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

    // Its own presenter because the shared one refuses every save. That is what the refusal test
    // above needs, and it leaves nothing for a test reading a saved value to read.
    private static SettingsPresenter presenterSavingInto(final List<Settings> saved) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", new CullProviderSettings("a-model", null, false, 2), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.DARK);
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
                saved.add(toSave);
            }
        };
        return new SettingsPresenter(useCase, refusingLibraryRootUseCase(), oneStoredKey(),
                onlyRefusingOneFolder(), threeProviders());
    }

    private static SettingsPresenter presenterOn(final String provider) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                provider, new CullProviderSettings("a-model", null, false, 2), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        return new SettingsPresenter(settingsUseCase(settings), refusingLibraryRootUseCase(), oneStoredKey(),
                onlyRefusingOneFolder(), threeProviders());
    }

    private static SettingsUseCase settingsUseCase(final Settings settings) {
        return new SettingsUseCase() {
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
                throw new IllegalStateException("sluice.paths.library-root (/gone) is not an existing folder");
            }
        };
    }

    private static LibraryRootUseCase refusingLibraryRootUseCase() {
        return (_, _) -> {
            throw new AssertionError("no test here moves the library root");
        };
    }

    // Anthropic's key is stored, the second API provider's is not, so the two blocks read
    // differently and a test can tell which one is showing.
    private static SecretStore oneStoredKey() {
        return new SecretStore() {
            @Override
            public Optional<String> secret(final SecretId id) {
                return Optional.empty();
            }

            @Override
            public SecretStatus status(final SecretId id) {
                return ANTHROPIC_KEY.equals(id) ? new SecretStatus.InKeyring() : new SecretStatus.Absent();
            }

            @Override
            public List<SecretHolding> holdings(final SecretId id) {
                return ANTHROPIC_KEY.equals(id)
                        ? List.of(new SecretHolding(new SecretStatus.InKeyring(), SecretHolding.Holding.HOLDS))
                        : List.of();
            }

            @Override
            public Optional<SecretStatus.StoredLocation> whereASaveWouldStoreIt() {
                return Optional.of(new SecretStatus.InKeyring());
            }

            @Override
            public void save(final SecretId id, final String secret) {
            }

            @Override
            public void remove(final SecretId id) {
            }
        };
    }

    // Judges the candidate rather than answering the same list to everyone. The saved roots are
    // usable, so a screen opens clean, and only the folder a test types is refused.
    private static PathValidationUseCase onlyRefusingOneFolder() {
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return REFUSED_FOLDER.equals(candidate.libraryRoot())
                        ? List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of(REFUSED_FOLDER)))
                        : List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                return List.of();
            }
        };
    }

    private static VisionProviderCatalog threeProviders() {
        final var apiSettings = Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT,
                ProviderSetting.THINKING, ProviderSetting.RETRIES, ProviderSetting.CREDENTIAL);
        final List<VisionProviderDescriptor> all = List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic", apiSettings,
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY),
                new VisionProviderDescriptor("other-api", "Another model service", apiSettings,
                        Set.of(ProviderSetting.MODEL), OTHER_KEY),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null));
        return new VisionProviderCatalog() {
            @Override
            public List<VisionProviderDescriptor> providers() {
                return all;
            }

            @Override
            public Optional<VisionProviderDescriptor> byId(final String id) {
                return all.stream().filter(provider -> provider.id().equals(id)).findFirst();
            }
        };
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    // Named apart from the one above rather than overloading it. A lambda whose body is a single
    // call fits both shapes, and the compiler cannot pick between them.
    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
