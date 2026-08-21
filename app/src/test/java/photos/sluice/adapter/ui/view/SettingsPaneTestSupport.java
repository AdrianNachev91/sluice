package photos.sluice.adapter.ui.view;

import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.PathSettings;
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
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

// Fixtures and lookups shared across the Settings pane's test classes, one per card plus
// SettingsPaneTest itself for the save wiring. Each test class still registers its own FxToolkit
// lifecycle; JUnit runs that per class, not per fixture.
final class SettingsPaneTestSupport {

    static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");
    static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");
    static final String SETUP_GUIDE = "Get a key at https://console.example.test.";
    static final String REFUSED_FOLDER = "D:\\moved-away";
    static final PseudoClass REFUSED = PseudoClass.getPseudoClass("refused");

    private static final SecretId OTHER_KEY = new SecretId("other-api", "OTHER_API_KEY");

    private SettingsPaneTestSupport() {}

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

    static Parent built(final SettingsPresenter presenter) {
        return built(presenter, 700);
    }

    // A window short enough that most of the page is off screen. At the height the other tests use,
    // the page is barely taller than the viewport. Nothing is ever out of view there, so a claim
    // about bringing something into view cannot be made either way.
    static Parent builtInAWindowThatScrolls(final SettingsPresenter presenter) {
        return built(presenter, 300);
    }

    static Node rowOf(final ScrollPane pane, final String fieldId) {
        return pane.lookup(fieldId).getParent().getParent();
    }

    // Which slice of the page is on screen, and whether a node falls inside it. Worked out from the
    // scroll position rather than from the viewport's own origin, which a ScrollPane only moves
    // when its skin next runs. The node's position is taken in the body's coordinates, where
    // scrolling does not move anything.
    static boolean inView(final ScrollPane pane, final Node node) {
        final var body = (Parent) pane.getContent();
        final double viewportHeight = pane.getViewportBounds().getHeight();
        final double scrollable = body.getBoundsInLocal().getHeight() - viewportHeight;
        final double firstVisible = pane.getVvalue() / pane.getVmax() * scrollable;
        final Bounds where = body.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return where.getMinY() >= firstVisible && where.getMaxY() <= firstVisible + viewportHeight;
    }

    static SettingsPresenter presenterOn(final String provider) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                provider, Map.of(provider, new CullProviderSettings("a-model", null, 2)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        return new SettingsPresenter(settingsUseCase(settings), refusingLibraryRootUseCase(), oneStoredKey(),
                onlyRefusingOneFolder(), threeProviders());
    }

    static SettingsUseCase settingsUseCase(final Settings settings) {
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

    static LibraryRootUseCase refusingLibraryRootUseCase() {
        return (_, _) -> {
            throw new AssertionError("no test here moves the library root");
        };
    }

    // Anthropic's key is stored, the second API provider's is not, so the two blocks read
    // differently and a test can tell which one is showing.
    static SecretStore oneStoredKey() {
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
    static PathValidationUseCase onlyRefusingOneFolder() {
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

    static VisionProviderCatalog threeProviders() {
        final var apiSettings = Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT,
                ProviderSetting.CREDENTIAL);
        final List<VisionProviderDescriptor> all = List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic", apiSettings,
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, "https://api.anthropic.com",
                        SETUP_GUIDE),
                new VisionProviderDescriptor("other-api", "Another model service", apiSettings,
                        Set.of(ProviderSetting.MODEL), OTHER_KEY, MODELS, null, null),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null, null, null, null));
        return new VisionProviderCatalog() {
            @Override
            public List<VisionProviderDescriptor> providers() {
                return all;
            }

            @Override
            public Optional<VisionProviderDescriptor> byId(final String id) {
                return all.stream().filter(provider -> provider.id().equals(id)).findFirst();
            }

            @Override
            public ProviderCheck check(final String id) {
                throw new AssertionError("no test here presses a credential check");
            }

            @Override
            public ProviderCheck check(final String id, final CullProviderSettings candidate) {
                throw new AssertionError("no test here presses a credential check");
            }
        };
    }

    // Only what the screen is actually saying. Every folder row builds a violation label whether or
    // not it has anything to report. Counting the empty ones makes a clean screen look full of
    // messages.
    static List<String> textsOfClass(final Parent pane, final String styleClass) {
        return pane.lookupAll("." + styleClass).stream()
                .filter(Label.class::isInstance)
                .map(node -> ((Label) node).getText())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    // Named apart from the one above rather than overloading it. A lambda whose body is a single
    // call fits both shapes, and the compiler cannot pick between them.
    static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
