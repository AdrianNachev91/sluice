package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
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
import photos.sluice.application.port.out.SecretHolding.Holding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStatus.Absent;
import photos.sluice.application.port.out.SecretStatus.InEnvironment;
import photos.sluice.application.port.out.SecretStatus.InKeyring;
import photos.sluice.application.port.out.SecretStatus.StoredLocation;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.SecretStoreException.Tier;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.StaleSecretNotClearedException;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class VisionProviderPresenterTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    private static final String SETUP_GUIDE = "Get a key at https://console.example.test.";

    @Test
    void theRowSaysWhereASaveWouldLand() {
        assertThat(secretRowFor(new InKeyring()).reassurance())
                .startsWith("Saved to this computer's own credential store.");
        assertThat(secretRowFor(new Absent()).reassurance())
                .contains("Never shown to any AI agent");
    }

    @Test
    void anEnvironmentStatusCarriesAnOverrideNoteNamingTheVariable() {
        final SettingsView.SecretRow row = secretRowFor(new InEnvironment("ANTHROPIC_API_KEY"));

        assertThat(row.environmentOverride()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    void aStoredStatusCarriesNoEnvironmentOverrideNote() {
        assertThat(secretRowFor(new InKeyring()).environmentOverride()).isNull();
    }

    @Test
    void aThrowingStatusCallDegradesTheRowRatherThanTheWholeScreen() {
        final SecretStore throwing = new FixedSecretStore(new Absent()) {
            @Override
            public SecretStatus status(final SecretId id) {
                throw new SecretStoreException(Tier.FILE, "the credential file could not be read");
            }
        };
        final SettingsView.SecretRow row = visionProviderOver(throwing).secretRow("anthropic");

        // The store's own words are kept, since they are the only thing telling one refusal from
        // another. What they are not is the whole message: a user needs what happened and what to
        // do, and neither is in a sentence written for a log.
        assertThat(row.errorMessage())
                .contains("the credential file could not be read")
                .contains("bug in Sluice");
    }

    @Test
    void twoHoldersProduceAMultiHolderNote() {
        final var store = new FixedSecretStore(new Absent(), List.of(
                new SecretHolding(new InEnvironment("ANTHROPIC_API_KEY"), Holding.HOLDS),
                new SecretHolding(new InKeyring(), Holding.HOLDS),
                new SecretHolding(new SecretStatus.InFile(), Holding.EMPTY)));

        assertThat(visionProviderOver(store).secretRow("anthropic").multiHolder()).isNotNull();
    }

    @Test
    void oneHolderProducesNoMultiHolderNote() {
        final var store = new FixedSecretStore(new InKeyring(), List.of(new SecretHolding(new InKeyring(), Holding.HOLDS)));

        assertThat(visionProviderOver(store).secretRow("anthropic").multiHolder()).isNull();
    }

    @Test
    void hasStoredValueReflectsHoldingsRatherThanWhichTierAnswers() {
        // The environment answers status(), but nothing is actually stored: Remove has nothing to do.
        final var envOnly = new FixedSecretStore(new InEnvironment("ANTHROPIC_API_KEY"),
                List.of(new SecretHolding(new InEnvironment("ANTHROPIC_API_KEY"), Holding.HOLDS)));
        assertThat(visionProviderOver(envOnly).secretRow("anthropic").hasStoredValue()).isFalse();

        // The keyring both answers and holds a value: Remove has something to do.
        final var stored = new FixedSecretStore(new InKeyring(), List.of(new SecretHolding(new InKeyring(), Holding.HOLDS)));
        assertThat(visionProviderOver(stored).secretRow("anthropic").hasStoredValue()).isTrue();
    }

    @Test
    void theModelPickerOpensOnTheProvidersOwnStaticFloorBeforeAnyCheck() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> {
                    throw new AssertionError("no check requested by this test");
                });

        final SettingsView.ModelPicker picker = visionProvider.modelPickerFor("anthropic").picker();

        assertThat(picker).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.sourceNote()).contains("before you connect to your provider"));
    }

    // Nothing has ever been saved for this provider, so the picker falls back to a recommendation
    // nobody chose. That fallback must never read as a caution. The caution's own claim is that a
    // cull will fail on what the user configured, and here nothing was configured at all.
    @Test
    void aProviderNeverConfiguredDrawsNoUnrecognisedCautionEvenThoughThePickerFellBackToARecommendation() {
        final var settings = new Settings(new PathSettings(null, null, null), "anthropic",
                Map.of(), List.of(), new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM);
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings,
                _ -> {
                    throw new AssertionError("no check requested by this test");
                });

        final VisionProviderPresenter.ModelPickerResult result = visionProvider.modelPickerFor("anthropic");

        assertThat(result.picker()).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.selected()).isNotBlank());
        assertThat(result.unrecognisedNote()).isNull();
    }

    @Test
    void refreshingModelsAfterASuccessfulCheckShowsTheAccountsRealCatalog() {
        // Two models, and the saved one (settings(null, null, null) saves claude-opus-5) is not
        // real-model's own recommendation. A single-option catalog could not tell "the saved model
        // survived the check" apart from "the recommendation always wins".
        final ModelCatalog live = new ModelCatalog(List.of(new ModelOption("real-model", "Real model"),
                new ModelOption("claude-opus-5", "Claude Opus 5")), "real-model");
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Accepted(live));

        visionProvider.refreshModels("anthropic");
        final VisionProviderPresenter.ModelPickerResult result = visionProvider.modelPickerFor("anthropic");

        assertThat(result.picker()).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class, options -> {
            assertThat(options.choices()).extracting(SettingsView.ModelChoice::id)
                    .containsExactlyInAnyOrder("real-model", "claude-opus-5");
            assertThat(options.selected()).isEqualTo("claude-opus-5");
            assertThat(options.sourceNote()).contains("your account can run");
        });
        assertThat(result.unrecognisedNote()).isNull();
    }

    // The agent provider takes no key, and so has nowhere to send anybody.
    @Test
    void eachProviderChoiceCarriesWhereItsOwnCredentialComesFrom() {
        final VisionProviderPresenter visionProvider = visionProviderOver(new FixedSecretStore(new Absent()));

        final List<SettingsView.ProviderChoice> choices = visionProvider.providerChoices();

        assertThat(choices).filteredOn(choice -> choice.id().equals("anthropic"))
                .singleElement()
                .extracting(SettingsView.ProviderChoice::setupGuide)
                .isEqualTo(SETUP_GUIDE);
        assertThat(choices).filteredOn(choice -> choice.id().equals("external-agent"))
                .singleElement()
                .extracting(SettingsView.ProviderChoice::setupGuide)
                .isNull();
    }

    @Test
    void theModelPickerHasNothingToShowWhileTheStartUpCheckIsStillRunning() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS)));

        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        final SettingsView.ModelPicker whileChecking = visionProvider.modelPickerFor("anthropic").picker();
        answering.countDown();
        startUp.join();

        assertThat(whileChecking).isInstanceOf(SettingsView.ModelPicker.Pending.class);
        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.sourceNote()).contains("your account can run"));
    }

    @Test
    void aStartUpCheckLeavesEveryOtherProvidersPickerAlone() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = new VisionProviderPresenter(new FixedSecretStore(new InKeyring()),
                checkingCatalog(twoApiProvidersAndAnAgent(),
                        _ -> answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS))),
                new FixedSettingsUseCase(settings(null, null, null)));

        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        final SettingsView.ModelPicker unchecked = visionProvider.modelPickerFor("other-api").picker();
        answering.countDown();
        startUp.join();

        assertThat(unchecked).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.sourceNote()).contains("before you connect"));
    }

    @Test
    void awaitingAStartUpCheckReturnsOnceItHasSettled() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS)));

        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        final var waiter = Thread.ofVirtual().start(() -> visionProvider.awaitStartUpCheck("anthropic"));
        assertThat(waiter.join(Duration.ofMillis(200))).isFalse();
        answering.countDown();
        waiter.join();
        startUp.join();

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOf(SettingsView.ModelPicker.Options.class);
    }

    @Test
    void awaitingAStartUpCheckThatIsNotRunningReturnsAtOnce() throws Exception {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Accepted(MODELS));

        final var waiter = Thread.ofVirtual().start(() -> visionProvider.awaitStartUpCheck("anthropic"));

        assertThat(waiter.join(Duration.ofSeconds(10))).isTrue();
    }

    // The provider's own static list would be the comfortable answer here and the wrong one. It
    // sits under a note saying nothing has been asked yet, and by this point something has been
    // asked and did not come back.
    @Test
    void aStartUpCheckThatOutlastsItsBudgetSaysSoRatherThanFallingBackToAGuess() {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS)));

        visionProvider.refreshModelsAtStartup("anthropic", Duration.ofMillis(50));

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation()).contains("could not reach").contains("timed out"));
        answering.countDown();
    }

    // Answering that there is nothing to check is not a failed check. Drawing it as one would open
    // every fresh install on an error the user has not caused and cannot clear.
    @Test
    void aStartUpCheckOnAnInstallWithNoKeyOpensOnTheStaticListRatherThanAFailure() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.NoCredential());

        visionProvider.refreshModelsAtStartup("anthropic");

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.sourceNote()).contains("before you connect"));
    }

    // A provider is asked to answer rather than throw. One that throws anyway leaves the same reader
    // with the same empty picker, so it is reported the same way.
    @Test
    void aStartUpCheckThatThrowsSaysSoRatherThanFallingBackToAGuess() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null), _ -> {
            throw new IllegalStateException("the provider fell over");
        });

        visionProvider.refreshModelsAtStartup("anthropic");

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation()).contains("the provider fell over"));
    }

    @Test
    void anAnswerStoredWhileTheStartUpCheckIsStillOutIsWhatThePickerDraws() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final ModelCatalog retried = new ModelCatalog(List.of(new ModelOption("retried-model", "Retried model")),
                "retried-model");
        final var calls = new AtomicInteger();
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> calls.getAndIncrement() == 0
                        ? answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS))
                        : new ProviderCheck.Accepted(retried));

        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        visionProvider.refreshModels("anthropic");
        final SettingsView.ModelPicker stillChecking = visionProvider.modelPickerFor("anthropic").picker();
        answering.countDown();
        startUp.join();

        assertThat(stillChecking).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.choices()).extracting(SettingsView.ModelChoice::id)
                        .containsExactly("retried-model"));
    }

    // The Retry answer here offers a model the start-up answer does not, so the two cannot be
    // confused for each other.
    @Test
    void aStartUpCheckLandingLateDoesNotReplaceAnAnswerSomethingElseHasSinceStored() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final ModelCatalog retried = new ModelCatalog(List.of(new ModelOption("retried-model", "Retried model")),
                "retried-model");
        final var calls = new AtomicInteger();
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> calls.getAndIncrement() == 0
                        ? answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS))
                        : new ProviderCheck.Accepted(retried));

        final Thread startUp = Thread.ofVirtual().start(() -> visionProvider.refreshModelsAtStartup("anthropic"));
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        visionProvider.refreshModels("anthropic");
        answering.countDown();
        startUp.join();

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.choices()).extracting(SettingsView.ModelChoice::id)
                        .containsExactly("retried-model"));
    }

    // Counted rather than guarded by a throwing stub. The start-up check runs its provider call
    // through a future, and that future wraps an AssertionError into the same failure the presenter
    // deliberately swallows. A stub that threw here would prove nothing.
    @Test
    void noStartUpCheckIsMadeForAProviderThatOffersNoModels() {
        final var settings = new Settings(new PathSettings(null, null, null), "external-agent", Map.of(),
                List.of(), new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM);
        final var checks = new AtomicInteger();
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings, _ -> {
            checks.incrementAndGet();
            return new ProviderCheck.Rejected();
        });

        visionProvider.refreshModelsAtStartup("external-agent");

        assertThat(checks).hasValue(0);
        assertThat(visionProvider.modelPickerFor("external-agent").picker()).isNull();
    }

    @Test
    void aRejectedCheckLeavesNothingToSelect() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Rejected());

        visionProvider.refreshModels("anthropic");
        final SettingsView.ModelPicker picker = visionProvider.modelPickerFor("anthropic").picker();

        assertThat(picker).isInstanceOfSatisfying(SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation()).isEqualTo("This provider rejected the key."));
    }

    @Test
    void anAccountWithNoUsableModelDrawsItsOwnViolation() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.NoUsableModels());

        visionProvider.refreshModels("anthropic");

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation())
                        .isEqualTo("This key works, but this account cannot run any model Sluice needs."));
    }

    @Test
    void aRefusalCarriesTheProvidersOwnWords() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Refused("your credit balance is too low"));

        visionProvider.refreshModels("anthropic");

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation()).contains("credit balance is too low"));
    }

    @Test
    void anUnreachableProviderCarriesWhatFailed() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Unreachable("connect timed out"));

        visionProvider.refreshModels("anthropic");

        assertThat(visionProvider.modelPickerFor("anthropic").picker()).isInstanceOfSatisfying(
                SettingsView.ModelPicker.Unavailable.class,
                unavailable -> assertThat(unavailable.violation()).contains("connect timed out"));
    }

    // The saved settings name a model MODELS does not offer; a-model is the only one it has.
    // This exercises the caution note against the provider's own static floor, no check performed.
    @Test
    void aSavedModelTheProviderDoesNotOfferDrawsACaution() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> {
                    throw new AssertionError("no check requested by this test");
                });

        final VisionProviderPresenter.ModelPickerResult result = visionProvider.modelPickerFor("anthropic");

        assertThat(result.unrecognisedNote()).contains("claude-opus-5").contains("does not offer");
        assertThat(result.picker()).isInstanceOfSatisfying(SettingsView.ModelPicker.Options.class,
                options -> assertThat(options.selected()).isEqualTo("a-model"));
    }

    @Test
    void testConnectionAsksTheCatalogAboutTheCandidateEndpointRatherThanWhatIsStored() {
        final var received = new ArrayList<CullProviderSettings>();
        final VisionProviderPresenter visionProvider = new VisionProviderPresenter(new FixedSecretStore(new InKeyring()),
                candidateCapturingCatalog(received), new FixedSettingsUseCase(settings(null, null, null)));

        final VisionProviderPresenter.ConnectionCheckResult result =
                visionProvider.testConnection("anthropic", "https://example.test");

        assertThat(received).containsExactly(new CullProviderSettings(null, "https://example.test", null));
        assertThat(result.message()).contains("This works");
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void testConnectionWordsARejectionTheSameWayThePickerDoes() {
        final VisionProviderPresenter visionProvider = visionProviderChecking(settings(null, null, null),
                _ -> new ProviderCheck.Rejected());

        final VisionProviderPresenter.ConnectionCheckResult result =
                visionProvider.testConnection("anthropic", "https://example.test");

        assertThat(result.message()).isEqualTo("This provider rejected the key.");
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void aProviderCallingAModelUsesTheModelSettingsAndNotTheWatchMode() {
        final var fields = choiceFor("anthropic", visionProviderOver(new FixedSecretStore(new Absent()))).fields();

        assertThat(fields).isEqualTo(new SettingsView.ProviderFields(true, true, false, true));
    }

    @Test
    void theExternalAgentUsesTheWatchModeAndNoneOfTheModelSettings() {
        final var fields = choiceFor("external-agent", visionProviderOver(new FixedSecretStore(new Absent()))).fields();

        assertThat(fields).isEqualTo(new SettingsView.ProviderFields(false, false, true, false));
    }

    @Test
    void removingAKeyAnEnvironmentVariableAlsoHoldsSaysTheProviderKeepsWorking() {
        final VisionProviderPresenter visionProvider = visionProviderOver(
                new FixedSecretStore(new InEnvironment("ANTHROPIC_API_KEY")));

        final VisionProviderPresenter.SecretRemoval removal = visionProvider.secretRemoval("anthropic");

        assertThat(removal.question()).contains("environment variable");
        assertThat(removal.question()).doesNotContain("stop working");
        assertThat(removal.removed()).contains("using the key in your environment");
    }

    @Test
    void removingTheOnlyKeySaysTheProviderStopsWorking() {
        final VisionProviderPresenter visionProvider = visionProviderOver(new FixedSecretStore(new InKeyring()));

        final VisionProviderPresenter.SecretRemoval removal = visionProvider.secretRemoval("anthropic");

        assertThat(removal.question()).contains("stop working");
        assertThat(removal.removed()).contains("cannot run until you add a new key");
    }

    @Test
    void saveSecretReportsNoErrorOnSuccess() {
        final VisionProviderPresenter visionProvider = visionProviderOver(new FixedSecretStore(new Absent()));

        assertThat(visionProvider.saveSecret("anthropic", "a-fresh-key")).isNull();
    }

    @Test
    void saveSecretReportsAStaleCopyMessageForThatException() {
        final SecretStore refusing = new FixedSecretStore(new Absent()) {
            @Override
            public void save(final SecretId id, final String secret) {
                throw new StaleSecretNotClearedException("a stale copy above it could not be cleared");
            }
        };
        final VisionProviderPresenter visionProvider = visionProviderOver(refusing);

        // The store's own words are kept, since they are the only thing telling one refusal from
        // another. They are not the whole message: a user needs what happened and what to try.
        assertThat(visionProvider.saveSecret("anthropic", "a-fresh-key"))
                .contains("a stale copy above it could not be cleared")
                .contains("Try Remove");
    }

    @Test
    void removeSecretReportsAnErrorMessageOnFailure() {
        final SecretStore refusing = new FixedSecretStore(new Absent()) {
            @Override
            public void remove(final SecretId id) {
                throw new SecretStoreException(Tier.KEYRING, "the keyring refused this entry");
            }
        };
        final VisionProviderPresenter visionProvider = visionProviderOver(refusing);

        assertThat(visionProvider.removeSecret("anthropic"))
                .contains("the keyring refused this entry")
                .contains("bug in Sluice");
    }

    private static SettingsView.SecretRow secretRowFor(final SecretStatus status) {
        return visionProviderOver(new FixedSecretStore(status)).secretRow("anthropic");
    }

    private static VisionProviderPresenter visionProviderOver(final SecretStore secretStore) {
        return new VisionProviderPresenter(secretStore, twoProviders(),
                new FixedSettingsUseCase(settings(null, null, null)));
    }

    private static VisionProviderPresenter visionProviderChecking(final Settings settings,
                                                                   final Function<String, ProviderCheck> checkById) {
        return new VisionProviderPresenter(new FixedSecretStore(new InKeyring()), checkingCatalog(checkById),
                new FixedSettingsUseCase(settings));
    }

    private static SettingsView.ProviderChoice choiceFor(final String id, final VisionProviderPresenter visionProvider) {
        return visionProvider.providerChoices().stream()
                .filter(choice -> choice.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no choice offered for provider '" + id + "'"));
    }

    // One of each type, and only the API one takes a key. Every question this screen asks a provider
    // is answered differently by these two, so a presenter reading the wrong one shows it.
    private static VisionProviderCatalog twoProviders() {
        return catalogOf(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, SETUP_GUIDE),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null, null, null, null));
    }

    private static VisionProviderCatalog checkingCatalog(final Function<String, ProviderCheck> checkById) {
        return checkingCatalog(List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, null),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null, null, null, null)), checkById);
    }

    private static VisionProviderCatalog checkingCatalog(final List<VisionProviderDescriptor> all,
                                                          final Function<String, ProviderCheck> checkById) {
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
                return checkById.apply(id);
            }

            @Override
            public ProviderCheck check(final String id, final CullProviderSettings candidate) {
                return checkById.apply(id);
            }
        };
    }

    // A second provider that also offers models, so a picker for one nobody asked about can be told
    // apart from the one being checked.
    private static List<VisionProviderDescriptor> twoApiProvidersAndAnAgent() {
        final var apiSettings = Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT,
                ProviderSetting.CREDENTIAL);
        return List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic", apiSettings,
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, null),
                new VisionProviderDescriptor("other-api", "Another model service", apiSettings,
                        Set.of(ProviderSetting.MODEL), new SecretId("other-api", "OTHER_API_KEY"), MODELS, null, null),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null, null, null, null));
    }

    // A check that says it has started, then hangs until the test releases it. That is what makes
    // the in-flight state something to assert against rather than a window to race.
    private static ProviderCheck answerOnceReleased(final CountDownLatch checking, final CountDownLatch answering,
                                                     final ProviderCheck answer) {
        checking.countDown();
        try {
            if (!answering.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released this check");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return answer;
    }

    // checkingCatalog ignores the candidate its own check(id, candidate) is given. This is the one
    // fixture that reads it back, so a test can prove testConnection forwards the screen's typed
    // endpoint rather than what is stored.
    private static VisionProviderCatalog candidateCapturingCatalog(final List<CullProviderSettings> received) {
        final VisionProviderCatalog delegate = checkingCatalog(_ -> new ProviderCheck.Rejected());
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
                throw new AssertionError("testConnection does not call the plain check");
            }

            @Override
            public ProviderCheck check(final String id, final CullProviderSettings candidate) {
                received.add(candidate);
                return new ProviderCheck.Accepted(MODELS);
            }
        };
    }

    private static VisionProviderCatalog catalogOf(final VisionProviderDescriptor... providers) {
        final List<VisionProviderDescriptor> all = List.of(providers);
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

    private static Settings settings(final @Nullable String repoRoot, final @Nullable String libraryRoot,
                                     final @Nullable String inbox) {
        return new Settings(new PathSettings(repoRoot, libraryRoot, inbox), "anthropic",
                Map.of("anthropic", new CullProviderSettings("claude-opus-5", null, null)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
    }

    private record FixedSettingsUseCase(Settings settings) implements SettingsUseCase {

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.empty();
        }

        @Override
        public void save(final Settings settings) {
            throw new AssertionError("no test here saves through the document");
        }
    }

    private static class FixedSecretStore implements SecretStore {

        private final SecretStatus status;
        private final List<SecretHolding> holdings;
        private final StoredLocation whereASaveWouldStoreIt;

        FixedSecretStore(final SecretStatus status) {
            this(status, List.of());
        }

        FixedSecretStore(final SecretStatus status, final List<SecretHolding> holdings) {
            this.status = status;
            this.holdings = holdings;
            this.whereASaveWouldStoreIt = new InKeyring();
        }

        @Override
        public Optional<String> secret(final SecretId id) {
            return Optional.empty();
        }

        @Override
        public SecretStatus status(final SecretId id) {
            return this.status;
        }

        @Override
        public List<SecretHolding> holdings(final SecretId id) {
            return this.holdings;
        }

        @Override
        public Optional<StoredLocation> whereASaveWouldStoreIt() {
            return Optional.of(this.whereASaveWouldStoreIt);
        }

        @Override
        public void save(final SecretId id, final String secret) {
        }

        @Override
        public void remove(final SecretId id) {
        }
    }
}
