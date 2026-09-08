package photos.sluice.adapter.ui.view;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextArea;
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
import org.jspecify.annotations.Nullable;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.PhotoCategoriesPresenter;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.ReviewPresenter;
import photos.sluice.adapter.ui.RunLauncherPresenter;
import photos.sluice.adapter.ui.RunMode;
import photos.sluice.adapter.ui.RunsPresenter;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.TroubleshootPresenter;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.InboxTally;
import photos.sluice.application.port.in.ReviewListing;
import photos.sluice.application.port.in.SortedTally;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.Pipeline;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.CullRunSummary;
import photos.sluice.domain.cull.CullRuns;
import photos.sluice.domain.cull.PrepDirHealth;
import photos.sluice.domain.cull.PrepDirHealth.State;
import photos.sluice.domain.job.ShardTally;
import photos.sluice.domain.model.SortSummary;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.secrets.SecretHolding;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStatus;
import photos.sluice.secrets.SecretStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The wiring only a built scene graph can be wrong about: which destination a nav entry shows, and
// which of its two states the Dashboard rests in. What each screen says is asserted elsewhere
// (SettingsPaneTest for Settings, FirstRunCardTest for the first-run card, RunLauncherPaneTest for
// the launcher the configured Dashboard rests on; the Review pane carries nothing of its own yet).
//
// Runs on the FX thread throughout. Building the scene reads the desktop's colour preferences, same
// as SettingsPaneTest, and that call refuses any other thread.
class MainWindowTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    private @Nullable CompletableFuture<SortSummary> sortJob;

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
    void clickingRunsShowsTheRunsScreen() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        clickNav(root, "#nav-runs");

        assertThat(currentScreen(root).getId()).isEqualTo("Runs");
        assertThat(currentScreen(root).lookup("#runs-clear-completed")).isNotNull();
    }

    @Test
    void theRunsEntryCarriesNoBadgeWithNothingOutstanding() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        assertThat(root.lookup("#nav-runs-count").isManaged()).isFalse();
    }

    @Test
    void theDashboardEntryCarriesNoMarkWithNothingHappeningThere() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        assertThat(root.lookup("#nav-dashboard-mark").isManaged()).isFalse();
    }

    @Test
    void aRunUnderWayMarksTheDashboardEntryAsRunning() throws Exception {
        final RunLauncherPresenter launcher = this.launcherRunningASort();
        final BorderPane root = onFxThread(() ->
                built(firstRunPresenter(false), settingsPresenter(), runsPresenter(), launcher));

        final Label mark = (Label) root.lookup("#nav-dashboard-mark");
        assertThat(mark.isManaged()).isTrue();
        assertThat(mark.getText()).isEqualTo("●");
        assertThat(mark.getStyleClass()).contains("nav-mark-running").doesNotContain("nav-mark-finished");
        assertThat(mark.getTooltip()).isNotNull();
    }

    @Test
    void aRunEndingSwitchesTheMarkToTheOneAskingToBeRead() throws Exception {
        final RunLauncherPresenter launcher = this.launcherRunningASort();
        final BorderPane root = onFxThread(() ->
                built(firstRunPresenter(false), settingsPresenter(), runsPresenter(), launcher));

        runOnFxThread(() -> requireNonNull(this.sortJob).complete(sortSummary()));
        WaitForAsyncUtils.waitForFxEvents();

        final Label mark = (Label) root.lookup("#nav-dashboard-mark");
        assertThat(mark.getStyleClass()).contains("nav-mark-finished").doesNotContain("nav-mark-running");
        assertThat(mark.getTooltip()).isNotNull();
    }

    // Selecting the Dashboard as the shell is built raises no action event, so nothing on the
    // opening path counts unless the shell asks for it. A reader coming back to unfinished sifts
    // would otherwise be told nothing until they happened to press a nav entry.
    @Test
    void theRunsEntryCountsWhatIsOutstandingWithoutWaitingForANavPress() throws Exception {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of(
                unfinishedRun("2019"), unfinishedRun("2018"))));
        final BorderPane root = onFxThread(() ->
                built(firstRunPresenter(false), settingsPresenter(), runsPresenter(pipeline)));

        assertThat(waitFor(() -> "2".equals(((Label) root.lookup("#nav-runs-count")).getText())))
                .isTrue();
    }

    @Test
    void aScreenThatWillNotBuildPutsAFailureUpRatherThanLeavingThePressDead() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(answersOnceThenThrows()), settingsPresenter()));
        clickNav(root, "#nav-settings");

        clickNav(root, "#nav-dashboard");

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
        assertThat(currentScreen(root).lookupAll(".selectable-text").stream()
                .map(label -> ((TextInputControl) label).getText()))
                .anySatisfy(text -> assertThat(text).isEqualTo("Dashboard"))
                .anySatisfy(text -> assertThat(text).startsWith("This screen would not open"));
    }

    @Test
    void theFailurePanelCarriesWhatTheReaderIsAskedToReport() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(answersOnceThenThrows()), settingsPresenter()));
        clickNav(root, "#nav-settings");

        clickNav(root, "#nav-dashboard");

        assertThat(((TextArea) currentScreen(root).lookup("#screen-failure-text")).getText())
                .contains("something this screen reads is in no state to be read");
    }

    @Test
    void theFailureCanBeSelectedAndCopied() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(alwaysThrows()), settingsPresenter()));

        final var trace = (TextArea) currentScreen(root).lookup("#screen-failure-text");

        assertThat(trace.isEditable()).isFalse();
        assertThat(trace.isDisabled()).isFalse();
        assertThat(currentScreen(root).lookup("#screen-failure-copy")).isNotNull();
    }

    // The frames name paths from the reader's own machine, and the panel asks them to quote this
    // into a bug report. Revealing that is their press, not something the screen does for them.
    @Test
    void theFailureStartsFoldedAwayAndTheReaderOpensIt() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(alwaysThrows()), settingsPresenter()));
        final var trace = (TextArea) currentScreen(root).lookup("#screen-failure-text");
        assertThat(trace.isVisible()).isFalse();

        onFxThread(() -> {
            ((Button) currentScreen(root).lookup("#screen-failure-toggle")).fire();
            return root;
        });

        assertThat(trace.isVisible()).isTrue();
    }

    // Copy works without opening the fold first, so handing the failure to somebody who can read
    // it never requires reading it yourself.
    @Test
    void copyingTheFailureDoesNotNeedTheFoldOpen() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(alwaysThrows()), settingsPresenter()));
        final var copy = (Button) currentScreen(root).lookup("#screen-failure-copy");

        assertThat(copy.isDisabled()).isFalse();
        assertThat(currentScreen(root).lookup("#screen-failure-text").isVisible()).isFalse();
    }

    // The Dashboard is drawn as the shell is built, before any press.
    @Test
    void aDashboardThatWillNotBuildAsTheWindowOpensStillLeavesAWindow() throws Exception {
        final BorderPane root = onFxThread(() ->
                built(new FirstRunPresenter(alwaysThrows()), settingsPresenter()));

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
        assertThat(currentScreen(root).lookupAll(".selectable-text").stream()
                .map(label -> ((TextInputControl) label).getText()))
                .anySatisfy(text -> assertThat(text).startsWith("This screen would not open"));
    }

    @Test
    void clickingDashboardAfterSettingsReturnsToTheDashboard() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));
        clickNav(root, "#nav-settings");

        clickNav(root, "#nav-dashboard");

        assertThat(currentScreen(root).getId()).isEqualTo("Dashboard");
    }

    // The launcher is the configured Dashboard's whole content, and the shell is the only thing that
    // puts it there. Without this, it could stop being wired in and every other Dashboard assertion
    // here would still pass, because they read the screen's name rather than what is on it.
    @Test
    void aConfiguredDashboardRestsOnTheRunLauncher() throws Exception {
        final BorderPane root = onFxThread(() -> built(firstRunPresenter(false)));

        assertThat(root.lookup("#run-mode-sort")).isNotNull();
        assertThat(root.lookup("#run-scope-field")).isNotNull();
        assertThat(root.lookup("#run-start")).isNotNull();
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
        return ((TextInputControl) screen.lookup(".pane-heading")).getText();
    }

    // The presenter pair the Settings screen and its VISION PROVIDER card read and write through.
    private record Presenters(SettingsPresenter settings, VisionProviderPresenter vision) {
    }

    private static BorderPane built(final FirstRunPresenter presenter) {
        return built(presenter, settingsPresenter());
    }

    private static BorderPane built(final FirstRunPresenter presenter, final Presenters presenters) {
        return built(presenter, presenters, runsPresenter());
    }

    private static BorderPane built(final FirstRunPresenter presenter, final Presenters presenters,
                                    final RunsPresenter runs) {
        return built(presenter, presenters, runs, runLauncherPresenter());
    }

    private static BorderPane built(final FirstRunPresenter presenter, final Presenters presenters,
                                    final RunsPresenter runs, final RunLauncherPresenter launcher) {
        final Scene scene = MainWindow.scene(presenter, presenters.settings(), presenters.vision(),
                photoCategoriesPresenter(), launcher, runs, troubleshootPresenter(),
                reviewPresenter(), new AtomicReference<>(() -> false));
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return (BorderPane) scene.getRoot();
    }

    private static CullRunSummary unfinishedRun(final String scope) {
        return new CullRunSummary(scope, Path.of("logs", "sift-prep", scope),
                new PrepDirHealth(State.WAITING, List.of()), new ShardTally(1, 1, 2), Instant.now());
    }

    // Polled rather than asserted straight away: the count is read on a thread of its own and drawn
    // a frame later, which is the whole point of the path being tested.
    private static boolean waitFor(final Callable<Boolean> settled) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, settled);
            return true;
        } catch (final java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private static RunLauncherPresenter runLauncherPresenter() {
        return new RunLauncherPresenter(mock(Pipeline.class), new FxProgressPort());
    }

    @SuppressWarnings("unchecked")
    private RunLauncherPresenter launcherRunningASort() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.inboxTally()).thenReturn(new InboxTally(1204, 4_000_000_000L));
        when(pipeline.sortedTally()).thenReturn(new SortedTally(List.of(), 0));
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));
        final JobHandle<SortSummary> handle = mock(JobHandle.class);
        this.sortJob = new CompletableFuture<>();
        when(handle.onComplete()).thenReturn(this.sortJob);
        when(pipeline.sort(any())).thenReturn(handle);
        final var launcher = new RunLauncherPresenter(pipeline, new FxProgressPort());
        launcher.setup().refreshCounts();
        launcher.setup().setMode(RunMode.SORT);
        launcher.start();
        return launcher;
    }

    private static SortSummary sortSummary() {
        return new SortSummary(12, 0, 0, 12, 0, 0, 0, 0, List.of(), SortSummary.Guessed.NONE,
                List.of(), Set.of(2019), List.of(), false, 0);
    }

    // A mock answers cullRuns() with null, so it is given an empty listing instead. The sidebar's
    // count and the runs screen then both draw their real nothing-here state.
    private static RunsPresenter runsPresenter() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.cullRuns()).thenReturn(new CullRuns.Listed(List.of()));
        return runsPresenter(pipeline);
    }

    private static RunsPresenter runsPresenter(final Pipeline pipeline) {
        return new RunsPresenter(pipeline, new RunLauncherPresenter(pipeline, new FxProgressPort()));
    }

    // A mock answers reviewListing() with null, so it is given an empty one instead. The review
    // screen then draws its real nothing-here state.
    private static ReviewPresenter reviewPresenter() {
        final Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.reviewListing()).thenReturn(new ReviewListing(List.of(), List.of()));
        return new ReviewPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));
    }

    private static TroubleshootPresenter troubleshootPresenter() {
        final Pipeline pipeline = mock(Pipeline.class);
        return new TroubleshootPresenter(pipeline,
                new RunLauncherPresenter(pipeline, new FxProgressPort()));
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
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
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

    private static PathValidationUseCase alwaysThrows() {
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                throw new IllegalStateException("something this screen reads is in no state to be read");
            }
        };
    }

    // Answers once so the shell reaches the state this test is about, then throws on the press.
    private static PathValidationUseCase answersOnceThenThrows() {
        final var asked = new AtomicBoolean(false);
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return List.of();
            }

            @Override
            public List<PathViolation> violationsInForce() {
                if (asked.getAndSet(true)) {
                    throw new IllegalStateException("something this screen reads is in no state to be read");
                }
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
                "anthropic", Map.of(), List.of(CullCategory.of("blurry", "Not worth keeping")),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
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
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
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
                new SettingsPresenter(useCase, libraryRootUseCase, noViolations(), catalog, vision,
                        new FxProgressPort()),
                vision);
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static void runOnFxThread(final Runnable work) throws Exception {
        WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }
}
