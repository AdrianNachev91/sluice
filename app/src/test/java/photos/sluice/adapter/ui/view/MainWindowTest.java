package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

// The wiring only a built scene graph can be wrong about: which destination a nav entry shows, and
// which of its two states the Dashboard rests in. What either screen says is asserted elsewhere
// (SettingsPaneTest for Settings, FirstRunCardTest for the first-run card; the Dashboard and
// Review panes carry nothing of their own yet).
//
// Runs on the FX thread throughout. Building the scene reads the desktop's colour preferences, same
// as SettingsPaneTest, and that call refuses any other thread.
class MainWindowTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void opensOnTheDashboard() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
    }

    @Test
    void clickingSettingsShowsTheSettingsPane() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        clickNav(root, "#nav-settings");

        assertThat(currentScreen(root).getId()).isEqualTo("Settings");
        assertThat(currentScreen(root).lookup("#settings-provider")).isNotNull();
    }

    @Test
    void clickingReviewShowsTheReviewPane() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        clickNav(root, "#nav-review");

        assertThat(currentScreen(root).getId()).isEqualTo("Review");
        assertThat(headingText(currentScreen(root))).isEqualTo("Review");
    }

    @Test
    void clickingDashboardAfterSettingsReturnsToTheDashboard() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));
        clickNav(root, "#nav-settings");

        clickNav(root, "#nav-dashboard");

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
    }

    @Test
    void anInstallWithNoFolderChosenOpensOnTheFirstRunCard() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(true)));

        assertThat(root.lookup("#first-run-save-button")).isNotNull();
    }

    @Test
    void aConfiguredInstallOpensOnAPlainDashboardWithNoFirstRunCard() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        assertThat(root.lookup("#first-run-save-button")).isNull();
        assertThat(headingText(currentScreen(root))).isEqualTo("Dashboard");
    }

    @Test
    void anInstallWithOneFolderStillUnchosenOpensOnTheFirstRunCard() throws Exception {
        final BorderPane root =
                onFxThread(() -> built(firstRunPresenterMissingOnly(PathRole.INBOX)));

        assertThat(root.lookup("#first-run-save-button")).isNotNull();
    }

    @Test
    void theFirstRunCardTakesTheHeightTheWindowHas() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(true)));

        assertThat(VBox.getVgrow(currentScreen(root))).isEqualTo(Priority.ALWAYS);
    }

    @Test
    void openingTheShellAsksTheConfiguredProviderWhatThisAccountCanRun() throws Exception {
        final var checked = new ArrayBlockingQueue<String>(1);

        onFxThread(() -> built(firstRunPresenter(false), settingsPresenter(checked::offer)));

        assertThat(checked.poll(10, TimeUnit.SECONDS)).isEqualTo("anthropic");
    }

    @Test
    void theStartUpCheckRunsOffTheThreadThatDrawsTheWindow() throws Exception {
        final var ranOnTheFxThread = new ArrayBlockingQueue<Boolean>(1);

        onFxThread(() -> built(firstRunPresenter(false),
                settingsPresenter(_ -> ranOnTheFxThread.offer(Platform.isFxApplicationThread()))));

        assertThat(ranOnTheFxThread.poll(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void aSaveThatFinishesFirstRunReplacesTheCardWithTheDashboard() throws Exception {
        final var install = new MovingRoots(PathRole.INBOX);
        final BorderPane root = onFxThread(() -> built(new FirstRunPresenter(install),
                settingsPresenter(_ -> { }, install)));
        assertThat(root.lookup("#first-run-save-button")).isNotNull();

        runOnFxThread(() -> ((Button) root.lookup("#first-run-save-button")).fire());

        assertThat(root.lookup("#first-run-save-button")).isNull();
        assertThat(headingText(currentScreen(root))).isEqualTo("Dashboard");
    }

    // A freshly drawn screen needs its own applyCss/layout pass before a lookup can reach inside it.
    // SettingsPaneTest's own built() runs one for that reason too. MainWindow.show swaps the content
    // in without running one, so a screen switched to here needs it done by hand.
    private static void clickNav(final BorderPane root, final String navId) throws Exception {
        runOnFxThread(() -> {
            ((ToggleButton) root.lookup(navId)).fire();
            root.applyCss();
            root.layout();
        });
    }

    // The content VBox itself carries no id; MainWindow.show sets one on whichever screen it holds.
    private static Node currentScreen(final BorderPane root) {
        return ((Parent) root.getCenter()).getChildrenUnmodifiable().getFirst();
    }

    private static String headingText(final Node screen) {
        return ((Label) screen.lookup(".pane-heading")).getText();
    }

    // The presenter pair the Settings screen and its VISION PROVIDER card read and write through.
    private record Presenters(SettingsPresenter settings, VisionProviderPresenter vision) {
    }

    private static BorderPane built(final FirstRunPresenter presenter) {
        return built(presenter, settingsPresenter());
    }

    private static BorderPane built(final FirstRunPresenter presenter, final Presenters presenters) {
        final Scene scene = MainWindow.scene(presenter, presenters.settings(), presenters.vision(),
                photoCategoriesPresenter());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return (BorderPane) scene.getRoot();
    }

    private static FirstRunPresenter firstRunPresenter(final boolean unfinished) {
        return new FirstRunPresenter(unfinished ? allRootsUnconfigured() : noViolations());
    }

    private static FirstRunPresenter firstRunPresenterMissingOnly(final PathRole role) {
        return new FirstRunPresenter(new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                return List.of(new NotConfigured(role));
            }
        });
    }

    // Roots that change under the window, which is what a save does. A fixed double can never show
    // the Dashboard swapping from one of its states to the other.
    private static final class MovingRoots implements PathValidationUseCase, SettingsUseCase {

        private final Settings settings = new Settings(new PathSettings("D:\\repo", "D:\\library", null),
                "anthropic", Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        private List<PathViolation> inForce;

        private MovingRoots(final PathRole... unset) {
            this.inForce = Arrays.stream(unset).<PathViolation>map(NotConfigured::new).toList();
        }

        @Override
        public Settings settings() {
            return this.settings;
        }

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.empty();
        }

        // The card's Save is what moves this install on, the same way a real one does.
        @Override
        public void save(final Settings toSave) {
            this.inForce = List.of();
        }

        @Override
        public List<PathViolation> violations(final PathSettings candidate) {
            return List.of();
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return this.inForce;
        }
    }

    private static PathValidationUseCase noViolations() {
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                return List.of();
            }
        };
    }

    private static PathValidationUseCase allRootsUnconfigured() {
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                return Arrays.stream(PathRole.values()).<PathViolation>map(NotConfigured::new).toList();
            }
        };
    }

    private static Presenters settingsPresenter() {
        return settingsPresenter(_ -> {});
    }

    // One card is enough. What this test needs is a real pane to exist, not any particular thing
    // drawn inside it.
    private static PhotoCategoriesPresenter photoCategoriesPresenter() {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of(), List.of(CullCategory.of("junk", "Not worth keeping")),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        return new PhotoCategoriesPresenter(new SettingsUseCase() {
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
                throw new AssertionError("no test here saves photo categories");
            }
        });
    }

    private static Presenters settingsPresenter(final Consumer<String> onCheck) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of("anthropic", new CullProviderSettings("a-model", null, 2)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        return settingsPresenter(onCheck, new SettingsUseCase() {
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
            }
        });
    }

    private static Presenters settingsPresenter(final Consumer<String> onCheck,
                                                final SettingsUseCase useCase) {
        final LibraryRootUseCase libraryRootUseCase = (_, _) -> {
            throw new AssertionError("no test here moves the library root");
        };
        final SecretStore secretStore = new SecretStore() {
            @Override
            public Optional<String> secret(final SecretId id) {
                return Optional.empty();
            }

            @Override
            public SecretStatus status(final SecretId id) {
                return new SecretStatus.InKeyring();
            }

            @Override
            public List<SecretHolding> holdings(final SecretId id) {
                return List.of();
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
        // A fixture builder, kept as one method rather than split, the same call SettingsPaneTest's
        // own equivalent makes.
        //noinspection ExtractMethodRecommender
        final var apiSettings = Set.of(ProviderSetting.MODEL, ProviderSetting.CREDENTIAL);
        final List<VisionProviderDescriptor> providers = List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic", apiSettings,
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, null));
        final VisionProviderCatalog catalog = new VisionProviderCatalog() {
            @Override
            public List<VisionProviderDescriptor> providers() {
                return providers;
            }

            @Override
            public Optional<VisionProviderDescriptor> byId(final String id) {
                return providers.stream().filter(provider -> provider.id().equals(id)).findFirst();
            }

            @Override
            public ProviderCheck check(final String id) {
                onCheck.accept(id);
                return new ProviderCheck.Accepted(MODELS);
            }

            @Override
            public ProviderCheck check(final String id, final CullProviderSettings candidate) {
                throw new AssertionError("no test here tries an endpoint the screen has not saved");
            }
        };
        final var vision = new VisionProviderPresenter(secretStore, catalog, useCase);
        return new Presenters(
                new SettingsPresenter(useCase, libraryRootUseCase, noViolations(), catalog, vision),
                vision);
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
