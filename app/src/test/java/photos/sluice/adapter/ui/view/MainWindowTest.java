package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.ShellPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
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
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The wiring only a built scene graph can be wrong about: which destination a nav entry shows, and
// where the welcome card's own link lands. What either screen says is asserted elsewhere
// (SettingsPaneTest for Settings; the Dashboard and Review panes carry nothing of their own yet).
//
// Runs on the FX thread throughout. Building the scene reads the desktop's colour preferences, same
// as SettingsPaneTest, and that call refuses any other thread.
class MainWindowTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

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
        final BorderPane root = onFxThread(() -> built(shellPresenter(false)));

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
    }

    @Test
    void clickingSettingsShowsTheSettingsPane() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(false)));

        clickNav(root, "#nav-settings");

        assertThat(currentScreen(root).getId()).isEqualTo("Settings");
        assertThat(currentScreen(root).lookup("#settings-provider")).isNotNull();
    }

    @Test
    void clickingReviewShowsTheReviewPane() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(false)));

        clickNav(root, "#nav-review");

        assertThat(currentScreen(root).getId()).isEqualTo("Review");
        assertThat(headingText(currentScreen(root))).isEqualTo("Review");
    }

    @Test
    void clickingDashboardAfterSettingsReturnsToTheDashboard() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(false)));
        clickNav(root, "#nav-settings");

        clickNav(root, "#nav-dashboard");

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
    }

    @Test
    void anUnconfiguredInstallOpensOnTheWelcomeCard() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(true)));

        assertThat(root.lookup("#welcome-settings-link")).isNotNull();
    }

    @Test
    void aConfiguredInstallOpensOnAPlainDashboardWithNoWelcomeCard() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(false)));

        assertThat(root.lookup("#welcome-settings-link")).isNull();
        assertThat(headingText(currentScreen(root))).isEqualTo("Dashboard");
    }

    @Test
    void theWelcomeCardsLinkOpensSettingsAndMarksItCurrentInTheSidebar() throws Exception {
        final BorderPane root = onFxThread(() -> built(shellPresenter(true)));

        runOnFxThread(() -> ((Hyperlink) root.lookup("#welcome-settings-link")).fire());

        assertThat(currentScreen(root).getId()).isEqualTo("Settings");
        assertThat(((ToggleButton) root.lookup("#nav-settings")).isSelected()).isTrue();
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

    private static BorderPane built(final ShellPresenter presenter) {
        final Scene scene = MainWindow.scene(presenter, settingsPresenter());
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return (BorderPane) scene.getRoot();
    }

    private static ShellPresenter shellPresenter(final boolean unconfigured) {
        return new ShellPresenter(unconfigured ? allRootsUnconfigured() : noViolations());
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

    private static SettingsPresenter settingsPresenter() {
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
            }
        };
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
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY));
        final VisionProviderCatalog catalog = new VisionProviderCatalog() {
            @Override
            public List<VisionProviderDescriptor> providers() {
                return providers;
            }

            @Override
            public Optional<VisionProviderDescriptor> byId(final String id) {
                return providers.stream().filter(provider -> provider.id().equals(id)).findFirst();
            }
        };
        return new SettingsPresenter(useCase, libraryRootUseCase, secretStore, noViolations(), catalog);
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
