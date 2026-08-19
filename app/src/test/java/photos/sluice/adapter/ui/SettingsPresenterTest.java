package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopiedAndMoved;
import photos.sluice.application.port.in.LibraryRootResolution;
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
import photos.sluice.application.port.out.SecretHolding.Holding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStatus.Absent;
import photos.sluice.application.port.out.SecretStatus.InEnvironment;
import photos.sluice.application.port.out.SecretStatus.InFile;
import photos.sluice.application.port.out.SecretStatus.InKeyring;
import photos.sluice.application.port.out.SecretStatus.StoredLocation;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.SecretStoreException.Tier;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.SettingOverride.ByEnvironmentVariable;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.StaleSecretNotClearedException;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.JobRunner;
import photos.sluice.domain.cull.CullCategory;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.Overlap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsPresenterTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    // A NUL character, which every filesystem in the matrix refuses. One only Windows refuses would
    // leave the tests using this proving nothing on the other two runners.
    private static final String UNUSABLE_PATH = "photos" + (char) 0 + "inbox";

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    // The theme selection is process-wide, so a test that sets one would otherwise decide what the
    // next one starts from.
    @AfterEach
    void resetThemeSelection() throws Exception {
        WaitForAsyncUtils.asyncFx(ThemeSelection::clear).get(10, TimeUnit.SECONDS);
    }

    @Test
    void viewReadsFolderFieldsFromTheSettingsInForce() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"), new FixedSecretStore(new Absent()));

        final SettingsView view = presenter.view();

        assertThat(view.workingRoot().value()).isEqualTo("/repo");
        assertThat(view.libraryRoot().value()).isEqualTo("/library");
        assertThat(view.inbox().value()).isEqualTo("/inbox");
    }

    @Test
    void viewShowsAnEmptyFolderFieldAsBlankRatherThanNull() {
        final var presenter = presenterOver(settings(null, null, null), new FixedSecretStore(new Absent()));

        assertThat(presenter.view().workingRoot().value()).isEmpty();
    }

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
        final SettingsView view = presenterOver(settings(null, null, null), throwing).view();

        // The store's own words are kept, since they are the only thing telling one refusal from
        // another. What they are not is the whole message: a user needs what happened and what to
        // do, and neither is in a sentence written for a log.
        assertThat(view.secret().errorMessage())
                .contains("the credential file could not be read")
                .contains("bug in Sluice");
    }

    @Test
    void twoHoldersProduceAMultiHolderNote() {
        final var store = new FixedSecretStore(new Absent(), List.of(
                new SecretHolding(new InEnvironment("ANTHROPIC_API_KEY"), Holding.HOLDS),
                new SecretHolding(new InKeyring(), Holding.HOLDS),
                new SecretHolding(new InFile(), Holding.EMPTY)));

        assertThat(presenterOver(settings(null, null, null), store).view().secret().multiHolder()).isNotNull();
    }

    @Test
    void oneHolderProducesNoMultiHolderNote() {
        final var store = new FixedSecretStore(new InKeyring(), List.of(new SecretHolding(new InKeyring(), Holding.HOLDS)));

        assertThat(presenterOver(settings(null, null, null), store).view().secret().multiHolder()).isNull();
    }

    @Test
    void hasStoredValueReflectsHoldingsRatherThanWhichTierAnswers() {
        // The environment answers status(), but nothing is actually stored: Remove has nothing to do.
        final var envOnly = new FixedSecretStore(new InEnvironment("ANTHROPIC_API_KEY"),
                List.of(new SecretHolding(new InEnvironment("ANTHROPIC_API_KEY"), Holding.HOLDS)));
        assertThat(presenterOver(settings(null, null, null), envOnly).view().secret().hasStoredValue()).isFalse();

        // The keyring both answers and holds a value: Remove has something to do.
        final var stored = new FixedSecretStore(new InKeyring(), List.of(new SecretHolding(new InKeyring(), Holding.HOLDS)));
        assertThat(presenterOver(settings(null, null, null), stored).view().secret().hasStoredValue()).isTrue();
    }

    @Test
    void aFolderWithNoViolationCarriesNoMessage() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"), new FixedSecretStore(new Absent()),
                noViolations());

        assertThat(presenter.view().workingRoot().violation()).isNull();
    }

    @Test
    void aFolderViolationCarriesAMessageOnItsOwnRole() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"), new FixedSecretStore(new Absent()),
                violating(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("/library")))));

        final SettingsView view = presenter.view();
        assertThat(view.libraryRoot().violation()).isNotNull();
        assertThat(view.workingRoot().violation()).isNull();
        assertThat(view.inbox().violation()).isNull();
    }

    @Test
    void anOverlapCarriesAMessageOnBothRoles() {
        final var presenter = presenterOver(settings("/repo", "/library", "/repo"), new FixedSecretStore(new Absent()),
                violating(List.of(new Overlap(PathRole.REPO_ROOT, PathRole.INBOX))));

        final SettingsView view = presenter.view();
        assertThat(view.workingRoot().violation()).isNotNull();
        assertThat(view.inbox().violation()).isNotNull();
        assertThat(view.libraryRoot().violation()).isNull();
    }

    @Test
    void saveKeepsTheCategoriesAlreadyConfiguredSinceThisScreenDoesNotEditThem() {
        final var category = new CullCategory("junk", "not worth keeping");
        final var settingsUseCase = new FixedSettingsUseCase(new Settings(new PathSettings(null, null, null),
                "anthropic", Map.of(), List.of(category),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.categories()).containsExactly(category);
    }

    // Swapping provider rewrites the model field, so a save carrying only the edited block would
    // destroy the model configured for the provider just left, with no way back to it.
    @Test
    void savingOneProvidersSettingsLeavesAnothersAlone() {
        final var settingsUseCase = new FixedSettingsUseCase(new Settings(new PathSettings(null, null, null),
                "external-agent",
                Map.of("anthropic", new CullProviderSettings("claude-opus-5", null, 3)),
                List.of(), new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "", "", "external-agent", "", "", null, true, 224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.providerSettings("anthropic").model()).isEqualTo("claude-opus-5");
        assertThat(saved.providerSettings("anthropic").maxRetries()).isEqualTo(3);
    }

    @Test
    void savingWritesTheEditedValuesUnderTheProviderBeingSaved() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "", "", "anthropic", "claude-haiku-4-5", "https://mine.invalid", 1, false,
                224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.providerSettingsById()).containsOnlyKeys("anthropic");
        assertThat(saved.providerSettings("anthropic"))
                .isEqualTo(new CullProviderSettings("claude-haiku-4-5", "https://mine.invalid", 1));
    }

    // The screen falls back to a provider this install has, so the fields under the dropdown have
    // to be that provider's rather than the ones saved under a name nothing recognises.
    @Test
    void theFieldsShownBelongToTheProviderTheDropdownFellBackTo() {
        final var settings = new Settings(new PathSettings(null, null, null), "gone-provider",
                Map.of("gone-provider", new CullProviderSettings("a-model-of-theirs", null, null)),
                List.of(), new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM);

        final SettingsView view = presenterOver(settings, new FixedSecretStore(new Absent())).view();

        assertThat(view.provider()).isEqualTo("external-agent");
        assertThat(view.model()).isNull();
    }

    @Test
    void aProviderCallingAModelUsesTheModelSettingsAndNotTheWatchMode() {
        final var fields = choiceFor("anthropic",
                presenterOver(settings(null, null, null), new FixedSecretStore(new Absent()))).fields();

        assertThat(fields).isEqualTo(new SettingsView.ProviderFields(true, true, true, false, true));
    }

    @Test
    void theExternalAgentUsesTheWatchModeAndNoneOfTheModelSettings() {
        final var fields = choiceFor("external-agent",
                presenterOver(settings(null, null, null), new FixedSecretStore(new Absent()))).fields();

        assertThat(fields).isEqualTo(new SettingsView.ProviderFields(false, false, false, true, false));
    }

    @Test
    void anUnknownProviderFallsBackToOneNeedingNoCredential() {
        final var configured = new Settings(new PathSettings(null, null, null), "a-provider-this-build-lacks",
                Map.of(), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);

        final var presenter = presenterOver(configured, new FixedSecretStore(new Absent()));

        assertThat(presenter.view().provider()).isEqualTo("external-agent");
    }

    @Test
    void anUnknownProviderIsSaidToBeUnknown() {
        final var configured = new Settings(new PathSettings(null, null, null), "a-provider-this-build-lacks",
                Map.of(), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);

        final String note = presenterOver(configured, new FixedSecretStore(new Absent()))
                .view().providerUnrecognised();

        assertThat(note).contains("a-provider-this-build-lacks", "Saving replaces");
    }

    @Test
    void aProviderThisBuildHasDrawsNoUnrecognisedNote() {
        final var note = presenterOver(settings(null, null, null), new FixedSecretStore(new Absent()))
                .view().providerUnrecognised();

        assertThat(note).isNull();
    }

    // The one screen that can correct a broken working root, asked to draw itself while one is in
    // force. A config file is hand-editable, so nothing stops that value reaching a launch.
    @Test
    void theScreenStillDrawsWhenTheWorkingRootIsNotAPathThisSystemCouldHave() {
        final var presenter = presenterOver(settings(UNUSABLE_PATH, "/library", null),
                new FixedSecretStore(new Absent()));

        final SettingsView view = presenter.view();

        assertThat(view.workingRoot().value()).isEqualTo(UNUSABLE_PATH);
        assertThat(view.inbox().suggestion()).isEqualTo(SettingsPresenter.inboxSuggestion(null))
                .doesNotContain(UNUSABLE_PATH);
    }

    // The refusal for an unusable root belongs to the save seam, so the presenter's job is to reach
    // it. Throwing on the way leaves a pressed button with nothing to show for it.
    @Test
    void aWorkingRootThatIsNotAPathReachesTheSaveSeamRatherThanThrowing() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save(UNUSABLE_PATH, "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5,
                "SYSTEM");

        assertThat(settingsUseCase.saved).isNotNull();
    }

    // Judged against the values being saved rather than the ones in force. A refused save leaves
    // those untouched, so marking against them would mark nothing at all.
    @Test
    void aRefusedSaveMarksTheFolderRowAtFault() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new IllegalStateException("Sluice cannot work with these folder settings");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()),
                violating(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("/gone")))));

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/gone", "/inbox",
                "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(outcome.libraryRoot()).isNotNull();
        assertThat(outcome.workingRoot()).isNull();
        assertThat(outcome.inbox()).isNull();
    }

    // The thrown message names configuration properties, which nobody using this screen has seen.
    // Once a row says what is wrong, the summary only has to send the reader to it.
    @Test
    void aRefusedSaveKeepsTheThrownMessageOutOfTheSummaryOnceARowCarriesIt() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new IllegalStateException("sluice.paths.library-root (/gone) is not a folder");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()),
                violating(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("/gone")))));

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/gone", "/inbox",
                "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(outcome.message()).doesNotContain("sluice.paths");
        assertThat(outcome.libraryRoot()).doesNotContain("sluice.paths");
    }

    // The one refusal already written for a user, so it reaches the summary as it is. No row can
    // carry it, and rewording it would tell them less than the seam already does.
    @Test
    void aBusyJobIsReportedInTheWordsTheSeamAlreadyChose() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new JobInProgressException("Sluice is running a job. Finish it first.");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/library", "/inbox",
                "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(outcome.message()).isEqualTo("Sluice is running a job. Finish it first.");
    }

    // Anything else carries a message written for a log, or none. What reaches the foot of the page
    // says what happened in this app's voice and what to do, keeping the thrown text only to quote.
    @Test
    void anUnforeseenRefusalIsWordedForAUserRatherThanShownRaw() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new IllegalStateException("writeAndApply: NoSuchFileException /etc/x");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/library", "/inbox",
                "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(outcome.message())
                .startsWith("These settings were not saved")
                .contains("bug in Sluice")
                .contains("NoSuchFileException");
    }

    @Test
    void aProviderCallingAModelRefusesToSaveWithoutOne() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "", "", "anthropic", "  ", "", null, false, 224, 5,
                "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Refused.class);
        assertThat(settingsUseCase.saved).isNull();
    }

    // The same blank field, and the other provider saves on it. That is the whole point of asking
    // per provider rather than per field.
    @Test
    void theExternalAgentSavesWithNoModelAtAll() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "", "", "external-agent", "", "", null, false, 224, 5,
                "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Saved.class);
        assertThat(settingsUseCase.saved).isNotNull();
    }

    @Test
    void viewOffersEveryThemeAndNamesTheOneInForce() {
        final var presenter = presenterOver(settingsWithTheme(ThemeChoice.DARK), new FixedSecretStore(new Absent()));

        final SettingsView view = presenter.view();

        assertThat(view.theme()).isEqualTo("DARK");
        assertThat(view.themes()).extracting(SettingsView.ThemeOption::id)
                .containsExactly("SYSTEM", "LIGHT", "DARK");
    }

    @Test
    void savingAThemePersistsTheChoice() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "DARK");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.theme()).isEqualTo(ThemeChoice.DARK);
    }

    // Starts from an explicit LIGHT, so a save that changed nothing would leave LIGHT behind and
    // fail. Starting from the SYSTEM default would pass on a light desktop against an empty save.
    @Test
    void savingAThemePutsItInForceForWindowsAlreadyOpen() throws Exception {
        final var presenter = presenter(new FixedSettingsUseCase(settings(null, null, null)), new FixedSecretStore(new Absent()), noViolations());

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            presenter.save("/repo", "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "DARK");
            return ThemeSelection.effectiveTheme().getValue();
        })).isEqualTo(Theme.DARK);
    }

    // Starts from an explicit LIGHT rather than the default, so this cannot pass by accident on a
    // machine whose desktop is already light.
    @Test
    void aRefusedSaveLeavesTheLookAlone() throws Exception {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        settingsUseCase.saveFailure = new IllegalStateException("Sluice is busy");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            presenter.save("/repo", "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "DARK");
            return ThemeSelection.effectiveTheme().getValue();
        })).isEqualTo(Theme.LIGHT);
    }

    // Saved DARK against a LIGHT starting point, so an applySavedTheme that did nothing at all
    // fails. Seeding LIGHT and asserting LIGHT would hold against an empty method on a light
    // desktop, since SYSTEM already resolves there.
    @Test
    void applySavedThemePutsTheStoredChoiceInForce() throws Exception {
        final var presenter = presenterOver(settingsWithTheme(ThemeChoice.DARK), new FixedSecretStore(new Absent()));

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            presenter.applySavedTheme();
            return ThemeSelection.effectiveTheme().getValue();
        })).isEqualTo(Theme.DARK);
    }

    @Test
    void anOverriddenPropertyCarriesANoteNamingTheVariable() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        settingsUseCase.overrides.put("sluice.cull.provider", new ByEnvironmentVariable("sluice.cull.provider", "SLUICE_CULL_PROVIDER"));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        assertThat(presenter.view().providerOverride()).contains("SLUICE_CULL_PROVIDER");
    }

    @Test
    void saveWithNoLibraryRootChangeSucceeds() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "",
                null, false, 224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Saved.class);
        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.provider()).isEqualTo("anthropic");
    }

    @Test
    void saveThatMovesTheLibraryRootAsksForAResolution() {
        final Path libraryRoot = Path.of("/library");
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new LibraryRootMoveNeedsAResolutionException(
                libraryRoot, "refused, for a log");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/new-library", "/inbox", "anthropic", "claude-opus-5", "",
                null, false, 224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution.class);
        final var resolution = (SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution) outcome;
        assertThat(resolution.newLibraryRoot()).isEqualTo(Path.of("/new-library"));
        // The refusal's own message is written for a log. Handing it to a dialog is what this asks
        // about, so the fixture's message is text no user should ever be shown. The path is compared
        // as a Path renders it, since a literal separator is right on one platform and wrong on the
        // other.
        assertThat(resolution.message())
                .contains(libraryRoot.toString())
                .doesNotContain("refused, for a log");
    }

    @Test
    void aRefusedSaveCarriesTheRefusalMessage() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        settingsUseCase.saveFailure = new JobInProgressException("Sluice is busy");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(outcome).isEqualTo(new SettingsPresenter.SaveOutcome.Refused("Sluice is busy"));
    }

    @Test
    void viewReportsWatchAutomaticallyTrueOnlyForWatchMode() {
        final var watching = new Settings(new PathSettings(null, null, null), "external-agent",
                Map.of(), List.of(), new ExternalAgentSettings(WatchMode.WATCH),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);

        assertThat(presenterOver(watching, new FixedSecretStore(new Absent())).view().watchAutomatically()).isTrue();
        assertThat(presenterOver(settings(null, null, null), new FixedSecretStore(new Absent())).view()
                .watchAutomatically()).isFalse();
    }

    // Which resolution each named method sends, asserted together so swapping the two is a failure.
    // One keeps the record of what the library holds; the other files it aside and starts over.
    @Test
    void eachNamedMoveSendsItsOwnResolution() {
        final Path destination = Path.of("/new-library");

        final var copying = new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(3, 3));
        presenterMoving(copying).moveLibraryRootCopyingTheIndex(destination);
        assertThat(copying.received).isEqualTo(LibraryRootResolution.COPY_AND_KEEP_INDEX);

        final var starting = new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(0, 0));
        presenterMoving(starting).moveLibraryRootWithAFreshIndex(destination);
        assertThat(starting.received).isEqualTo(LibraryRootResolution.START_A_FRESH_INDEX);
    }

    @Test
    void moveLibraryRootReportsAFilesCopiedOutcome() {
        final var jobRunner = new JobRunner();
        final var library = new SucceedingLibraryRootUseCase(jobRunner, new CopiedAndMoved(12, 12));
        final var presenter = new SettingsPresenter(new FixedSettingsUseCase(settings(null, null, null)), library,
                new FixedSecretStore(new Absent()), noViolations(), twoProviders());

        final var outcome = presenter.moveLibraryRoot(Path.of("/new-library"), LibraryRootResolution.COPY_AND_KEEP_INDEX);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.message()).contains("12");
    }

    @Test
    void moveLibraryRootReportsAFailureFromTheJob() {
        final var jobRunner = new JobRunner();
        final var library = new FailingLibraryRootUseCase(jobRunner, new IllegalStateException("cannot move"));
        final var presenter = new SettingsPresenter(new FixedSettingsUseCase(settings(null, null, null)), library,
                new FixedSecretStore(new Absent()), noViolations(), twoProviders());

        final var outcome = presenter.moveLibraryRoot(Path.of("/new-library"), LibraryRootResolution.START_A_FRESH_INDEX);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.message()).isEqualTo("cannot move");
    }

    @Test
    void saveSecretReportsNoErrorOnSuccess() {
        final var presenter = presenterOver(settings(null, null, null), new FixedSecretStore(new Absent()));

        assertThat(presenter.saveSecret("anthropic", "a-fresh-key")).isNull();
    }

    @Test
    void saveSecretReportsAStaleCopyMessageForThatException() {
        final SecretStore refusing = new FixedSecretStore(new Absent()) {
            @Override
            public void save(final SecretId id, final String secret) {
                throw new StaleSecretNotClearedException("a stale copy above it could not be cleared");
            }
        };
        final var presenter = presenterOver(settings(null, null, null), refusing);

        // The store's own words are kept, since they are the only thing telling one refusal from
        // another. They are not the whole message: a user needs what happened and what to try.
        assertThat(presenter.saveSecret("anthropic", "a-fresh-key"))
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
        final var presenter = presenterOver(settings(null, null, null), refusing);

        assertThat(presenter.removeSecret("anthropic"))
                .contains("the keyring refused this entry")
                .contains("bug in Sluice");
    }

    @Test
    void saveNeverCreatesAFolderForAPathThatIsNotOurOwnSuggestion(final @TempDir Path tempDir) {
        final Path untouched = tempDir.resolve("a-user-typed-folder-that-does-not-exist-yet");
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save(untouched.toString(), "", "", "anthropic", "claude-opus-5", "", null, false, 224, 5, "SYSTEM");

        assertThat(untouched).doesNotExist();
    }

    @Test
    void aRefusedSaveCreatesNoFolder(final @TempDir Path tempDir) {
        final Path inbox = tempDir.resolve("Inbox");
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var refused = presenter.save(tempDir.toString(), "", inbox.toString(), "anthropic", "", "",
                null, false, 224, 5, "SYSTEM");

        assertThat(refused).isInstanceOf(SettingsPresenter.SaveOutcome.Refused.class);
        assertThat(inbox).doesNotExist();

        presenter.save(tempDir.toString(), "", inbox.toString(), "anthropic", "claude-opus-5", "",
                null, false, 224, 5, "SYSTEM");

        assertThat(inbox).exists();
    }

    @Test
    void inboxSuggestionTracksTheWorkingRootField() {
        assertThat(SettingsPresenter.inboxSuggestion("/custom/root")).isEqualTo(Path.of("/custom/root/Inbox").toString());
    }

    @Test
    void inboxSuggestionFallsBackToTheWorkingRootSuggestionWhenItIsUnset() {
        assertThat(SettingsPresenter.inboxSuggestion(null))
                .isEqualTo(Path.of(SettingsPresenter.workingRootSuggestion(), "Inbox").toString());
    }

    private static SettingsView.SecretRow secretRowFor(final SecretStatus status) {
        return presenterOver(settings(null, null, null), new FixedSecretStore(status)).view().secret();
    }

    private static SettingsPresenter presenterOver(final Settings settings, final SecretStore secretStore) {
        return presenterOver(settings, secretStore, noViolations());
    }

    private static SettingsPresenter presenterOver(final Settings settings, final SecretStore secretStore,
                                                    final PathValidationUseCase pathValidation) {
        return presenter(new FixedSettingsUseCase(settings), secretStore, pathValidation);
    }

    private static SettingsView.ProviderChoice choiceFor(final String id, final SettingsPresenter presenter) {
        return presenter.view().providers().stream()
                .filter(choice -> choice.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no choice offered for provider '" + id + "'"));
    }

    private static SettingsPresenter presenterMoving(final LibraryRootUseCase libraryRoot) {
        return new SettingsPresenter(new FixedSettingsUseCase(settings(null, null, null)), libraryRoot,
                new FixedSecretStore(new Absent()), noViolations(), twoProviders());
    }

    private static SettingsPresenter presenter(final SettingsUseCase settingsUseCase, final SecretStore secretStore,
                                               final PathValidationUseCase pathValidation) {
        return new SettingsPresenter(settingsUseCase, failingLibraryRootUseCase(), secretStore, pathValidation,
                twoProviders());
    }

    // One of each type, and only the API one takes a key. Every question this screen asks a provider
    // is answered differently by these two, so a presenter reading the wrong one shows it.
    private static VisionProviderCatalog twoProviders() {
        return catalogOf(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT,
                                ProviderSetting.RETRIES, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(ProviderSetting.WATCH_MODE), Set.of(), null, null));
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
        };
    }

    private static LibraryRootUseCase failingLibraryRootUseCase() {
        return (_, _) -> {
            throw new AssertionError("not exercised by this test");
        };
    }

    private static PathValidationUseCase noViolations() {
        return violating(List.of());
    }

    private static PathValidationUseCase violating(final List<PathViolation> violations) {
        return new PathValidationUseCase() {
            @Override
            public List<PathViolation> violations(final PathSettings candidate) {
                return violations;
            }

            @Override
            public List<PathViolation> violationsInForce() {
                return violations;
            }
        };
    }

    private static <T> T onFxThread(final Callable<T> work) throws Exception {
        return WaitForAsyncUtils.asyncFx(work).get(10, TimeUnit.SECONDS);
    }

    private static Settings settingsWithTheme(final ThemeChoice theme) {
        final Settings base = settings(null, null, null);
        return new Settings(base.paths(), base.provider(), base.providerSettingsById(), base.categories(),
                base.externalAgent(), base.montage(), theme);
    }

    private static Settings settings(final @Nullable String repoRoot, final @Nullable String libraryRoot,
                                     final @Nullable String inbox) {
        return new Settings(new PathSettings(repoRoot, libraryRoot, inbox), "anthropic",
                Map.of("anthropic", new CullProviderSettings("claude-opus-5", null, null)), List.of(),
                new ExternalAgentSettings(WatchMode.MANUAL), new MontageConfig(224, 5), ThemeChoice.SYSTEM);
    }

    private static final class FixedSettingsUseCase implements SettingsUseCase {

        private final Settings settings;
        private final Map<String, SettingOverride> overrides = new HashMap<>();
        private @Nullable RuntimeException saveFailure;
        private @Nullable Settings saved;

        FixedSettingsUseCase(final Settings settings) {
            this.settings = settings;
        }

        @Override
        public Settings settings() {
            return this.settings;
        }

        @Override
        public Optional<SettingOverride> overriddenAboveTheConfigFile(final String property) {
            return Optional.ofNullable(this.overrides.get(property));
        }

        @Override
        public void save(final Settings settings) {
            if (this.saveFailure != null) {
                throw this.saveFailure;
            }
            this.saved = settings;
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

    private static final class SucceedingLibraryRootUseCase implements LibraryRootUseCase {
        private final JobRunner jobRunner;
        private final LibraryRootMoveOutcome outcome;
        private @Nullable LibraryRootResolution received;

        private SucceedingLibraryRootUseCase(final JobRunner jobRunner, final LibraryRootMoveOutcome outcome) {
            this.jobRunner = jobRunner;
            this.outcome = outcome;
        }

        @Override
        public JobHandle<LibraryRootMoveOutcome> moveLibraryRoot(final Path newLibraryRoot,
                                                                 final LibraryRootResolution resolution) {
            this.received = resolution;
            return this.jobRunner.submit(_ -> this.outcome);
        }
    }

    private record FailingLibraryRootUseCase(JobRunner jobRunner, RuntimeException failure)
            implements LibraryRootUseCase {
        @Override
        public JobHandle<LibraryRootMoveOutcome> moveLibraryRoot(final Path newLibraryRoot,
                                                                 final LibraryRootResolution resolution) {
            return this.jobRunner.submit(_ -> {
                throw this.failure;
            });
        }
    }
}
