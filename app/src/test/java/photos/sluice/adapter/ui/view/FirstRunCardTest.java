package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import photos.sluice.adapter.ui.FirstRunPresenter;
import photos.sluice.adapter.ui.FxProgressPort;
import photos.sluice.adapter.ui.SettingsPresenter;
import photos.sluice.adapter.ui.SettingsView;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.PathsMisconfiguredException;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ExternalAgentSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotConfigured;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED_FOLDER;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.oneStoredKey;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportIsARefusal;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.reportText;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.scrollOf;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.textsOfClass;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.threeProviders;

// The install below is a working double rather than a set of canned answers: a save changes what
// it reports as unset. Nothing else can exercise the loop where a save redraws the card it was
// pressed on.
class FirstRunCardTest {

    private static final String WORKING_ROOT = "D:\\staging";
    private static final String LIBRARY_ROOT = "D:\\keepers";
    private static final String INBOX = "D:\\dropbox-in";

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void closeStages() throws Exception {
        FxToolkit.cleanupStages();
    }

    @Test
    void theCardOffersTheThreeFoldersAndTheProviderChoice() throws Exception {
        final Parent pane = onFxThread(() -> built(new Install(nothingChosen())).card());

        assertThat(pane.lookup("#settings-working-root")).isNotNull();
        assertThat(pane.lookup("#settings-library-root")).isNotNull();
        assertThat(pane.lookup("#settings-inbox")).isNotNull();
        assertThat(pane.lookup("#first-run-provider")).isNotNull();
    }

    @Test
    void theCardSaysNoWorkRunsUntilAllThreeFoldersAreSet() throws Exception {
        final Parent pane = onFxThread(() -> built(new Install(nothingChosen())).card());

        assertThat(pane.lookup("#folder-roots-required-legend")).isNotNull();
    }

    @Test
    void theCardCarriesTheFolderRulesSomebodyChoosingNeeds() throws Exception {
        final Parent pane = onFxThread(() -> built(new Install(nothingChosen())).card());

        assertThat(pane.lookup("#folder-roots-help")).isNotNull();
    }

    @Test
    void aSaveNamingEveryFolderHandsOverToTheDashboard() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            type(built.card(), "#settings-library-root", LIBRARY_ROOT);
            type(built.card(), "#settings-inbox", INBOX);
            press(built.pane());
        });

        assertThat(built.handedOver().get()).isEqualTo(1);
    }

    @Test
    void aSaveLeavingOneFolderUnchosenKeepsTheCardUp() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            type(built.card(), "#settings-library-root", LIBRARY_ROOT);
            press(built.pane());
        });

        assertThat(built.handedOver().get()).isZero();
        assertThat(built.pane().lookup("#first-run-save-button")).isNotNull();
    }

    @Test
    void aSaveLeavingOneFolderUnchosenSaysThatItLandedAndWhatIsLeft() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            type(built.card(), "#settings-library-root", LIBRARY_ROOT);
            press(built.pane());
        });

        assertThat(bannerText(built.pane())).isEqualTo(
                "Saved. Sluice still needs your Inbox before it can start any work on your photos.");
    }

    @Test
    void theRedrawnCardShowsTheFoldersThatWereJustSaved() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            press(built.pane());
        });

        assertThat(((TextField) built.pane().lookup("#settings-working-root")).getText())
                .isEqualTo(WORKING_ROOT);
    }

    @Test
    void aRefusedSaveMarksTheFolderAtFaultAndSaysWhyOnTheBar() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));
        assertThat(textsOfClass(built.pane(), "settings-violation")).isEmpty();

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            type(built.card(), "#settings-library-root", REFUSED_FOLDER);
            type(built.card(), "#settings-inbox", INBOX);
            press(built.pane());
        });

        assertThat(built.handedOver().get()).isZero();
        assertThat(textsOfClass(built.pane(), "settings-violation"))
                .contains("No folder could be found here.");
        assertThat(reportText(built.pane())).contains("not saved");
        assertThat(reportIsARefusal(built.pane())).isTrue();
    }

    @Test
    void theProviderTheCardOpensOnIsWhatASaveStores() throws Exception {
        // Configured to something else, so the assertion cannot pass on the fixture's own opening
        // value. The card's dropdown resolves to the provider it shows, and that is what has to
        // reach the settings.
        final var install = new Install(startingOn("anthropic"));
        final Built built = onFxThread(() -> built(install));

        runOnFxThread(() -> {
            selectProvider(built.card(), "external-agent");
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            press(built.pane());
        });

        assertThat(install.settings().provider()).isEqualTo("external-agent");
    }

    @Test
    void choosingTheProviderThatNeedsAModelStillSaves() throws Exception {
        final var install = new Install(nothingChosen());
        final Built built = onFxThread(() -> built(install));

        runOnFxThread(() -> {
            selectProvider(built.card(), "anthropic");
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            press(built.pane());
        });

        assertThat(install.settings().provider()).isEqualTo("anthropic");
        assertThat(install.settings().providerSettings("anthropic").model()).isNotBlank();
        assertThat(textsOfClass(built.pane(), "settings-violation")).isEmpty();
    }

    @Test
    void aCardOpenedOnAHalfFilledInstallSaysWhatIsStillNeeded() throws Exception {
        final Built built = onFxThread(() -> built(new Install(
                new Settings(new PathSettings(WORKING_ROOT, LIBRARY_ROOT, null), "external-agent",
                        Map.of("external-agent", CullProviderSettings.unset()), List.of(),
                        new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5),
                        ThemeChoice.SYSTEM))));

        assertThat(textsOfClass(built.card(), "first-run-opening"))
                .containsExactly("Sluice still needs your Inbox before it can start any work on your photos.");
    }

    @Test
    void theOpeningLineIsWhateverThePresenterSays() throws Exception {
        final var install = new Install(nothingChosen());
        final Built built = onFxThread(() -> built(install));

        assertThat(textsOfClass(built.card(), "first-run-opening"))
                .containsExactly(new FirstRunPresenter(install).opening());
    }

    @Test
    void saveIsPinnedOutsideWhatScrolls() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));

        assertThat(built.pane().lookup("#first-run-save-button")).isNotNull();
        assertThat(scrollOf(built.pane()).lookup("#first-run-save-button")).isNull();
    }

    @Test
    void aRefusalIsReplacedByTheNextSaveThatLands() throws Exception {
        final Built built = onFxThread(() -> built(new Install(nothingChosen())));
        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", REFUSED_FOLDER);
            press(built.pane());
        });
        assertThat(reportIsARefusal(built.pane())).isTrue();

        runOnFxThread(() -> {
            type(built.card(), "#settings-working-root", WORKING_ROOT);
            press(built.pane());
        });

        assertThat(reportIsARefusal(built.pane())).isFalse();
    }

    private static void selectProvider(final Parent card, final String providerId) {
        final var box = (ComboBox<?>) card.lookup("#first-run-provider");
        for (int at = 0; at < box.getItems().size(); at++) {
            if (((SettingsView.ProviderChoice) box.getItems().get(at)).id().equals(providerId)) {
                box.getSelectionModel().select(at);
                return;
            }
        }
        throw new AssertionError("no provider called " + providerId + " is offered");
    }

    private static String bannerText(final Parent pane) {
        return ((Label) pane.lookup("#settings-report-banner").lookup(".label")).getText();
    }

    private static void type(final Parent card, final String fieldId, final String text) {
        ((TextField) card.lookup(fieldId)).setText(text);
    }

    // Found on the page rather than the card: Save is pinned in the header, outside what scrolls.
    private static void press(final Parent page) {
        ((Button) page.lookup("#first-run-save-button")).fire();
    }

    /**
     * @param pane the scrolling pane the shell would be handed
     * @param handedOver how many times the card has said first run is over
     */
    private record Built(Parent pane, AtomicInteger handedOver) {

        // The card itself, found again each time. A save replaces it, so a reference taken once
        // would go on answering for the card that has already left the screen.
        Parent card() {
            return (Parent) this.pane.lookup(".first-run-card");
        }
    }

    private static Built built(final Install install) {
        final var handedOver = new AtomicInteger();
        // The card's own library-move arm cannot be driven here: the dialog it opens waits on a person.
        final var visionProvider = new VisionProviderPresenter(oneStoredKey(), threeProviders(), install);
        final var presenter = new SettingsPresenter(install, (_, _) -> {
            throw new AssertionError("the move arm is covered at the presenter, not here");
        }, install, threeProviders(), visionProvider, new FxProgressPort());
        final Node pane = FirstRunCard.pane(presenter, new FirstRunPresenter(install), _ -> handedOver.incrementAndGet());
        final var scene = new Scene(new StackPane(pane), 900, 700);
        final var stage = new Stage();
        stage.setScene(scene);
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Built((Parent) pane, handedOver);
    }

    private static Settings startingOn(final String provider) {
        return new Settings(new PathSettings(null, null, null), provider,
                Map.of(provider, CullProviderSettings.unset()), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
    }

    private static Settings nothingChosen() {
        return new Settings(new PathSettings(null, null, null), "external-agent",
                Map.of("external-agent", CullProviderSettings.unset()), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
    }

    // A small working model of an install rather than canned answers. What it reports as unset is
    // whatever its current settings leave blank, so a save moves it on. It also refuses the folder
    // this package's other tests use for a refusal, the way the real save seam refuses one that is
    // not there.
    private static final class Install implements SettingsUseCase, PathValidationUseCase {

        private Settings settings;

        private Install(final Settings initial) {
            this.settings = initial;
        }

        @Override
        public Settings settings() {
            return this.settings;
        }

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.empty();
        }

        @Override
        public void save(final Settings toSave) {
            final List<PathViolation> refusals = this.violations(toSave.paths()).stream()
                    .filter(violation -> !(violation instanceof NotConfigured))
                    .toList();
            if (!refusals.isEmpty()) {
                throw new PathsMisconfiguredException(refusals);
            }
            this.settings = toSave;
        }

        @Override
        public List<PathViolation> violations(final PathSettings paths) {
            final var found = new ArrayList<PathViolation>();
            judge(found, PathRole.WORKING_ROOT, paths.repoRoot());
            judge(found, PathRole.LIBRARY_ROOT, paths.libraryRoot());
            judge(found, PathRole.INBOX, paths.inbox());
            return List.copyOf(found);
        }

        @Override
        public List<PathViolation> violationsInForce() {
            return this.violations(this.settings.paths());
        }

        private static void judge(final List<PathViolation> found, final PathRole role,
                                  final @Nullable String value) {
            if (value == null || value.isBlank()) {
                found.add(new NotConfigured(role));
            } else if (REFUSED_FOLDER.equals(value)) {
                found.add(new NotADirectory(role, Path.of(value)));
            }
        }
    }
}
