package photos.sluice.adapter.ui.view;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.adapter.ui.SettingsView;
import photos.sluice.adapter.ui.VisionProviderPresenter;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.MontageConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.ANTHROPIC_KEY;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.MODELS;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.REFUSED;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.SETUP_GUIDE;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.answerDialog;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.built;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.builtInAWindowThatScrolls;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.inView;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.oneStoredKey;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.onFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.presenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.visionProviderPresenterOn;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.rowOf;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.scrollOf;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.runOnFxThread;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.settingsUseCase;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.settledAtTheTop;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.textsOfClass;
import static photos.sluice.adapter.ui.view.SettingsPaneTestSupport.threeProviders;

// A handful of structural claims rather than a second copy of VisionProviderPresenterTest. What
// the screen says is the vision presenter's, and is asserted there. This file guards the wiring
// only a built scene graph can be wrong about. Which controls a provider shows, which parent a
// block ends up in, and whether a refusal or a credential change reaches the row it belongs to.
//
// Everything runs on the FX thread. Building the pane reads the desktop's colour preferences, and
// that call refuses any other thread.
class VisionProviderCardTest {

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
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        final Node apiKey = pane.lookup("#settings-api-key");
        assertThat(apiKey).isNotNull();
        assertThat(labelsIn(cardOwning(apiKey))).contains("VISION PROVIDER");
    }

    @Test
    void choosingAProviderShowsOnlyTheControlsThatProviderUses() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(shown(pane, "#settings-provider-fields")).isTrue();
        assertThat(shown(pane, "#settings-api-key")).isTrue();

        runOnFxThread(() -> select(pane, "external-agent"));

        assertThat(shown(pane, "#settings-provider-fields")).isFalse();
        assertThat(shown(pane, "#settings-api-key")).isFalse();
    }

    // Both providers here take a key, so a block following the wrong one still looks plausible.
    // Only anthropic's is stored, which is what makes the buttons say which provider is showing.
    @Test
    void theApiKeyBlockFollowsTheChosenProviderRatherThanTheSavedOne() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(buttonsIn(pane.lookup("#settings-api-key"))).contains("Replace key");

        runOnFxThread(() -> select(pane, "other-api"));

        assertThat(buttonsIn(pane.lookup("#settings-api-key")))
                .contains("Activate key").doesNotContain("Replace key");
    }

    // Unlike a text field, the model picker cannot carry an arbitrary unsaved value across a
    // provider it does not belong to. "a-model" is external-agent's id for nothing at all.
    //
    // Switching provider and back therefore re-resolves the picker from that provider's own saved
    // model, rather than preserving whatever the control last showed. This proves the round trip
    // lands back on anthropic's own saved choice, not on empty or some stale carry-over.
    // Two models rather than MODELS' single one, and the saved id is the one NOT recommended.
    // With only one choice, "the saved model" and "the recommended model" are the same string.
    // A round trip would then pass even if picked() ignored the saved model and always fell back
    // to the recommendation.
    @Test
    void switchingProviderAndBackReselectsThisProvidersSavedModel() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                visionProviderWithADistinctSavedModel()));

        runOnFxThread(() -> {
            select(pane, "external-agent");
            select(pane, "anthropic");
        });

        assertThat(onFxThread(() -> selectedModelId(pane))).isEqualTo("other-model");
    }

    // Empty until pressed, which is what proves the label reads Test's own answer rather than
    // something left over from building the row.
    @Test
    void pressingTestShowsWhatTheProviderSaidAboutTheTypedEndpoint() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> new ProviderCheck.Rejected())));
        runOnFxThread(() -> ((TextField) pane.lookup("#settings-endpoint")).setText("https://example.test"));

        runOnFxThread(() -> ((Button) pane.lookup("#settings-test-connection")).fire());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> !onFxThread(() -> testResultText(pane)).equals("Checking..."));

        assertThat(onFxThread(() -> testResultText(pane))).isEqualTo("This provider rejected the key.");
    }

    // The field is empty, so what a user reads there is only ever the prompt. Anthropic names a
    // default in this fixture and other-api does not. That is what proves the text is read from
    // the chosen provider, not a value every provider happens to share.
    @Test
    void anEmptyEndpointPromptsWithThisProvidersOwnDefault() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> new ProviderCheck.Rejected())));
        final var endpoint = (TextField) pane.lookup("#settings-endpoint");
        assertThat(endpoint.getPromptText()).isEqualTo("https://api.anthropic.com");

        runOnFxThread(() -> select(pane, "other-api"));

        assertThat(endpoint.getPromptText()).isNull();
    }

    @Test
    void theTestButtonIsEnabledWheneverThisProviderHasAStoredCredential() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> new ProviderCheck.Rejected())));
        final var test = (Button) pane.lookup("#settings-test-connection");

        // Enabled with no endpoint typed at all: anthropic's key is stored, and an empty endpoint
        // is a real thing to test, the provider's own default service.
        assertThat(test.isDisabled()).isFalse();

        runOnFxThread(() -> select(pane, "other-api"));

        // other-api's key is never stored in this fixture, so there is nothing to test with.
        assertThat(test.isDisabled()).isTrue();
    }

    // A check still in flight when the dropdown moves on belongs to the provider that was asked,
    // not whichever one is showing when it finally answers.
    @Test
    void aTestResultArrivingAfterAProviderSwitchIsDiscarded() throws Exception {
        final var checkStarted = new CountDownLatch(1);
        final var releaseCheck = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> {
            checkStarted.countDown();
            awaitRelease(releaseCheck);
            return new ProviderCheck.Rejected();
        });
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));
        runOnFxThread(() -> ((TextField) pane.lookup("#settings-endpoint")).setText("https://example.test"));

        runOnFxThread(() -> ((Button) pane.lookup("#settings-test-connection")).fire());
        if (!checkStarted.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("check never started");
        }
        runOnFxThread(() -> select(pane, "external-agent"));
        releaseCheck.countDown();
        // The background check's FX callback lands the instant the latch opens. A bounded window
        // proves it did not, the same shape a timed latch await gives when there is no latch to ask.
        WaitForAsyncUtils.sleep(300, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(onFxThread(() -> testResultText(pane))).isEmpty();
    }

    // A failed check draws an empty, disabled picker with the provider's own words and a Retry.
    // Pressing Retry against a now-working answer proves the row redraws rather than staying stuck.
    @Test
    void aFailedCheckDrawsAnUnavailablePickerAndRetrySucceeds() throws Exception {
        final var succeeding = new AtomicBoolean(false);
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> succeeding.get()
                ? new ProviderCheck.Accepted(MODELS) : new ProviderCheck.Unreachable("connect timed out"));
        // Built after a check has already landed. The pane's own build reads the visionProvider's
        // cache rather than checking anything itself. An unchecked visionProvider would open on the
        // static floor regardless of what checkById answers.
        visionProvider.refreshModels("anthropic");
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));

        assertThat(modelBox(pane).isDisabled()).isTrue();
        assertThat(textsOfClass(pane, "settings-violation")).anyMatch(text -> text.contains("connect timed out"));

        succeeding.set(true);
        runOnFxThread(() -> ((Button) pane.lookup("#settings-model-retry")).fire());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !onFxThread(() -> modelBox(pane).isDisabled()));

        assertThat(onFxThread(() -> selectedModelId(pane))).isEqualTo("a-model");
    }

    // The check answering is the one thing that changes this row without a user touching anything,
    // so a screen that only reads the picker when it is built would say it was checking for good.
    @Test
    void aPickerWaitingOnTheStartUpCheckRedrawsItselfOnceTheAnswerLands() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> {
            checking.countDown();
            awaitRelease(answering);
            return new ProviderCheck.Accepted(MODELS);
        });
        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));

        answering.countDown();
        startUp.join();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !onFxThread(() -> modelBox(pane).isDisabled()));

        assertThat(onFxThread(() -> selectedModelId(pane))).isEqualTo("a-model");
    }

    // A provider that has stopped answering is the one this button gets pressed against, and saying
    // so takes the full interactive timeout. A press that changes nothing on screen reads as one the
    // app did not receive.
    @Test
    void pressingRetryAnswersTheReaderBeforeTheCheckDoes() throws Exception {
        final var calls = new AtomicInteger();
        final var pressed = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> {
            if (calls.getAndIncrement() > 0) {
                pressed.countDown();
                awaitRelease(release);
            }
            return new ProviderCheck.Rejected();
        });
        visionProvider.refreshModels("anthropic");
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));
        final var retry = (Button) pane.lookup("#settings-model-retry");

        runOnFxThread(retry::fire);
        assertThat(pressed.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(onFxThread(retry::isDisabled)).isTrue();
        assertThat(onFxThread(retry::getText)).isEqualTo("Connecting...");
        release.countDown();
    }

    // Retry disables itself on the press, so a check that ends without redrawing the row leaves that
    // button dead for the life of the screen. A provider is asked to answer rather than throw, and
    // one that throws anyway is exactly the case the reader needs a way back from.
    @Test
    void aRetryWhoseCheckThrowsStillLeavesAWayBack() throws Exception {
        final var calls = new AtomicInteger();
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> {
            if (calls.getAndIncrement() > 0) {
                throw new IllegalStateException("the provider fell over");
            }
            return new ProviderCheck.Rejected();
        });
        visionProvider.refreshModels("anthropic");
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));

        runOnFxThread(((Button) pane.lookup("#settings-model-retry"))::fire);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> onFxThread(() -> pane.lookup("#settings-model-retry") != null
                        && !pane.lookup("#settings-model-retry").isDisabled()));

        assertThat(onFxThread(() -> ((Button) pane.lookup("#settings-model-retry")).getText()))
                .isEqualTo("Retry");
    }

    // Anthropic names where to get a key in this fixture and other-api does not. That is what proves
    // the line is read off the chosen provider rather than shown for every one of them.
    @Test
    void theCredentialCardSaysWhereThisProvidersKeyComesFrom() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(sentenceOf(pane.lookup("#settings-api-key-setup-guide"))).isEqualTo(SETUP_GUIDE);

        runOnFxThread(() -> select(pane, "other-api"));

        assertThat(pane.lookup("#settings-api-key-setup-guide")).isNull();
    }

    @Test
    void aPickerWaitingOnTheStartUpCheckOffersNothingAndBlamesNothing() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = checkingVisionProviderOn("anthropic", _ -> {
            checking.countDown();
            awaitRelease(answering);
            return new ProviderCheck.Accepted(MODELS);
        });
        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();

        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProvider));

        assertThat(modelBox(pane).isDisabled()).isTrue();
        assertThat(modelBox(pane).getPromptText()).isEqualTo("Loading...");
        assertThat(pane.lookup("#settings-model-retry")).isNull();
        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();
        answering.countDown();
        startUp.join();
    }

    @Test
    void theInfoGlyphsMarkSitsCentredInItsRing() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        assertThat(onFxThread(() -> markOffsetWithinRing(pane, "info"))).isZero();
    }

    @Test
    void theCautionGlyphsMarkSitsCentredInItsRing() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("a-provider-this-build-lacks"),
                visionProviderPresenterOn("a-provider-this-build-lacks")));

        assertThat(onFxThread(() -> markOffsetWithinRing(pane, "caution"))).isZero();
    }

    // Nothing was refused here, so the empty-violations assertion is half of what the name claims.
    @Test
    void anUnknownConfiguredProviderIsCautionedAboutWithoutBeingRefused() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("a-provider-this-build-lacks"),
                visionProviderPresenterOn("a-provider-this-build-lacks")));

        assertThat(textsOfClass(pane, "settings-caution"))
                .anyMatch(text -> text.contains("a-provider-this-build-lacks"));
        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();
    }

    // A key is stored the moment its own button is pressed, so the page around it holds choices that
    // were never saved. This screen opens on external-agent, so anthropic can only be showing
    // because the dropdown was changed and left unsaved, which is what makes the check meaningful.
    @Test
    void savingAKeyLeavesAProviderPickedButNotYetSavedAlone() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("external-agent"),
                visionProviderPresenterOn("external-agent")));
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
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));
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
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));

        final var pressed = WaitForAsyncUtils.asyncFx(
                () -> ((Button) pane.lookup("#settings-api-key-remove")).fire());
        answerDialog("Remove key");
        pressed.get(10, TimeUnit.SECONDS);

        assertThat(textsOfClass(pane, "settings-confirmation"))
                .anyMatch(text -> text.startsWith("Your key is removed."));
    }

    @Test
    void cancellingTheConfirmRemovesNothing() throws Exception {
        final var checks = new AtomicInteger(0);
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> {
            checks.incrementAndGet();
            return new ProviderCheck.NoCredential();
        })));

        final var pressed = WaitForAsyncUtils.asyncFx(
                () -> ((Button) pane.lookup("#settings-api-key-remove")).fire());
        answerDialog("Keep it");
        pressed.get(10, TimeUnit.SECONDS);

        assertThat(checks.get()).isZero();
        assertThat(textsOfClass(pane, "settings-confirmation")).isEmpty();
    }

    // A stored key changes which models this account can actually run. A save has to ask the
    // provider again, not leave the picker showing whatever an earlier or absent key gave.
    // The count is what proves this: the pane's own build never checks on its own.
    @Test
    void savingAKeyAsksTheProviderAgainForWhatItCanRun() throws Exception {
        final var checks = new AtomicInteger(0);
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> {
            checks.incrementAndGet();
            return new ProviderCheck.Accepted(MODELS);
        })));

        runOnFxThread(() -> {
            ((PasswordField) pane.lookup("#settings-api-key-entry")).setText("a-key");
            ((Button) pane.lookup("#settings-api-key-save")).fire();
        });
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> checks.get() > 0);

        assertThat(checks.get()).isEqualTo(1);
    }

    // A key taken away can drop the picker back to nothing usable, and only a fresh check tells
    // the picker that happened.
    @Test
    void removingAKeyAsksTheProviderAgainToo() throws Exception {
        final var checks = new AtomicInteger(0);
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"),
                checkingVisionProviderOn("anthropic", _ -> {
            checks.incrementAndGet();
            return new ProviderCheck.NoCredential();
        })));

        final var pressed = WaitForAsyncUtils.asyncFx(
                () -> ((Button) pane.lookup("#settings-api-key-remove")).fire());
        answerDialog("Remove key");
        pressed.get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> checks.get() > 0);

        assertThat(checks.get()).isEqualTo(1);
    }

    // Switching provider does not rebuild the pane, so anything a refusal put on screen would
    // otherwise outlive the choices it was about.
    @Test
    void changingProviderTakesTheLastRefusalOffTheScreen() throws Exception {
        final Parent pane = onFxThread(() -> built(presenterOn("anthropic"), visionProviderPresenterOn("anthropic")));
        runOnFxThread(() -> {
            clearModelSelection(pane);
            ((Button) pane.lookup("#settings-save-button")).fire();
        });
        final var model = modelBox(pane);
        assertThat(textsOfClass(pane, "settings-violation")).isNotEmpty();
        assertThat(pane.lookup("#settings-report-banner")).isNotNull();
        assertThat(model.getPseudoClassStates()).contains(REFUSED);

        runOnFxThread(() -> select(pane, "external-agent"));

        assertThat(textsOfClass(pane, "settings-violation")).isEmpty();
        assertThat(model.getPseudoClassStates()).doesNotContain(REFUSED);
    }

    @Test
    void aSaveRefusedForABlankModelDoesNotTravelToTheModelRow() throws Exception {
        final Parent page = onFxThread(() -> builtInAWindowThatScrolls(presenterOn("anthropic"),
                visionProviderPresenterOn("anthropic")));
        final ScrollPane scroll = scrollOf(page);
        final Node row = rowOf(scroll, "#settings-model");
        runOnFxThread(() -> scroll.setVvalue(scroll.getVmax()));
        assertThat(inView(scroll, row)).isFalse();

        runOnFxThread(() -> {
            clearModelSelection(page);
            ((Button) page.lookup("#settings-save-button")).fire();
        });
        settledAtTheTop(scroll);

        assertThat(inView(scroll, row)).isFalse();
        assertThat(modelBox(page).getPseudoClassStates()).contains(REFUSED);
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

    private static String testResultText(final Parent pane) {
        return ((TextArea) pane.lookup("#settings-test-result")).getText();
    }

    private static String selectedModelId(final Parent pane) {
        final SettingsView.ModelChoice selected = modelBox(pane).getSelectionModel().getSelectedItem();
        // The property's declared type is not nullable, so the IDE reads this guard as always
        // false. clearModelSelection and an Unavailable picker both leave nothing selected.
        //noinspection ConstantValue
        return selected == null ? "" : selected.id();
    }

    private static void clearModelSelection(final Parent pane) {
        modelBox(pane).getSelectionModel().clearSelection();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<SettingsView.ModelChoice> modelBox(final Parent pane) {
        return (ComboBox<SettingsView.ModelChoice>) pane.lookup("#settings-model");
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
        return root.lookupAll(".selectable-text").stream()
                .map(node -> ((TextInputControl) node).getText()).toList();
    }

    private static List<String> buttonsIn(final Node root) {
        return root.lookupAll(".button").stream().map(node -> ((Button) node).getText()).toList();
    }

    private static void awaitRelease(final CountDownLatch answering) {
        try {
            if (!answering.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released this check");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // The words as a reader sees them, whether they were drawn as plain text or as a link. Reading
    // only the Text nodes would silently drop every address out of the sentence.
    private static String sentenceOf(final Node flow) {
        return ((Parent) flow).getChildrenUnmodifiable().stream()
                .map(part -> part instanceof final Hyperlink link ? link.getText() : ((Text) part).getText())
                .collect(Collectors.joining());
    }

    // How far the mark's centre sits from the ring's, in the pane both are laid out in. Measured
    // off the built scene rather than off the numbers, so a change to either shape is caught.
    // Scoped to the ring's own glyph. More than one info glyph can stand on a screen, and a lookup
    // across the whole pane would measure one ring against another glyph's marks.
    private static double markOffsetWithinRing(final Parent pane, final String glyph) {
        final Node ring = pane.lookup("." + glyph + "-ring");
        assertThat(ring).isNotNull();
        final List<Node> mark = ring.getParent().getChildrenUnmodifiable().stream()
                .filter(node -> node.getStyleClass().contains(glyph + "-mark"))
                .toList();
        assertThat(mark).hasSize(2);
        final double top = mark.stream().mapToDouble(part -> part.getBoundsInParent().getMinY()).min().orElseThrow();
        final double bottom = mark.stream().mapToDouble(part -> part.getBoundsInParent().getMaxY()).max().orElseThrow();
        return (top + bottom) / 2 - ring.getBoundsInParent().getCenterY();
    }

    // visionProviderPresenterOn's own catalog is threeProviders(), whose check() and check(id, candidate)
    // both throw. A test that presses Test or Retry needs a real answer instead, so it builds its
    // vision presenter over this one rather than visionProviderPresenterOn.
    private static VisionProviderPresenter checkingVisionProviderOn(final String provider,
                                                                     final Function<String, ProviderCheck> checkById) {
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                provider, Map.of(provider, new CullProviderSettings("a-model", null, 2)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        return new VisionProviderPresenter(oneStoredKey(), checkingThreeProviders(checkById),
                settingsUseCase(settings));
    }

    private static VisionProviderCatalog checkingThreeProviders(final Function<String, ProviderCheck> checkById) {
        final VisionProviderCatalog delegate = threeProviders();
        return new VisionProviderCatalog() {
            @Override
            public List<VisionProviderDescriptor> providers() {
                return delegate.providers();
            }

            @Override
            public Optional<VisionProviderDescriptor> byId(final String id) {
                return delegate.byId(id);
            }

            @Override
            public ProviderCheck check(final String id) {
                return checkById.apply(id);
            }

            @Override
            public ProviderCheck check(final String id, final CullProviderSettings candidate) {
                return checkById.apply(id);
            }
        };
    }

    // anthropic offers two models here, unlike threeProviders()'s single-option MODELS, and the
    // saved one is not the recommendation. Only this shape can tell "the saved model survived"
    // apart from "the recommendation always wins".
    private static VisionProviderPresenter visionProviderWithADistinctSavedModel() {
        final var richModels = new ModelCatalog(List.of(
                new ModelOption("recommended-model", "Recommended model"),
                new ModelOption("other-model", "Other model")), "recommended-model");
        final var settings = new Settings(new PathSettings("D:\\repo", "D:\\library", "D:\\repo\\Inbox"),
                "anthropic", Map.of("anthropic", new CullProviderSettings("other-model", null, 2)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        final List<VisionProviderDescriptor> all = List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, richModels, null, null),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(), Set.of(), null, null, null, null));
        final VisionProviderCatalog catalog = new VisionProviderCatalog() {
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
        return new VisionProviderPresenter(oneStoredKey(), catalog, settingsUseCase(settings));
    }
}
