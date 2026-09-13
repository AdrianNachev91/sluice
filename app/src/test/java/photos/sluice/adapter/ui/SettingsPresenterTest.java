package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.LibraryRootResolutionRequiredException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopiedAndMoved;
import photos.sluice.application.port.in.LibraryRootMoveOutcome.CopyCancelled;
import photos.sluice.application.port.in.LibraryRootResolution;
import photos.sluice.application.port.in.LibraryRootUseCase;
import photos.sluice.application.port.in.PathValidationUseCase;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.SiftProviderSettings;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ModelOption;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.SettingOverride.ByEnvironmentVariable;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.application.service.JobHandle;
import photos.sluice.application.service.JobRunner;
import photos.sluice.domain.sift.SiftCategory;
import photos.sluice.domain.sift.MontageConfig;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.secrets.SecretHolding;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStatus;
import photos.sluice.secrets.SecretStatus.Absent;
import photos.sluice.secrets.SecretStatus.InKeyring;
import photos.sluice.secrets.SecretStatus.StoredLocation;
import photos.sluice.secrets.SecretStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsPresenterTest {

    private static final SecretId ANTHROPIC_KEY = new SecretId("anthropic", "ANTHROPIC_API_KEY");

    private static final ModelCatalog MODELS =
            new ModelCatalog(List.of(new ModelOption("a-model", "A model")), "a-model");

    private static final String SETUP_GUIDE = "Get a key at https://console.example.test.";

    // A NUL character, which every filesystem in the matrix refuses. One only Windows refuses would
    // leave the tests using this proving nothing on the other two runners.
    private static final String UNUSABLE_PATH = "photos" + (char) 0 + "inbox";

    private static final Path OLD_LIBRARY = Path.of("/old-library");

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
                violating(List.of(new Overlap(PathRole.WORKING_ROOT, PathRole.INBOX))));

        final SettingsView view = presenter.view();
        assertThat(view.workingRoot().violation()).isNotNull();
        assertThat(view.inbox().violation()).isNotNull();
        assertThat(view.libraryRoot().violation()).isNull();
    }

    @Test
    void saveKeepsTheCategoriesAlreadyConfiguredSinceThisScreenDoesNotEditThem() {
        final var category = SiftCategory.of("blurry", "not worth keeping");
        final var settingsUseCase = new FixedSettingsUseCase(new Settings(new PathSettings(null, null, null),
                "anthropic", Map.of(), List.of(category),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.categories()).containsExactly(category);
    }

    @Test
    void savingOneProvidersSettingsLeavesAnothersAlone() {
        final var settingsUseCase = new FixedSettingsUseCase(new Settings(new PathSettings(null, null, null),
                "external-agent",
                Map.of("anthropic", new SiftProviderSettings("claude-opus-5", null, 3)),
                List.of(), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "/library", "/inbox", "external-agent", "", "", 224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.providerSettings("anthropic").model()).isEqualTo("claude-opus-5");
        assertThat(saved.providerSettings("anthropic").maxRetries()).isEqualTo(3);
    }

    @Test
    void savingWritesTheEditedValuesUnderTheProviderBeingSaved() {
        final var configured = new Settings(new PathSettings(null, null, null), "anthropic",
                Map.of("anthropic", new SiftProviderSettings("claude-opus-5", null, 3)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
        final var settingsUseCase = new FixedSettingsUseCase(configured);
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-haiku-4-5", "https://mine.invalid", 224, 5, "SYSTEM");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.providerSettingsById()).containsOnlyKeys("anthropic");
        // The retry count carries through untouched: this screen never asks about it.
        assertThat(saved.providerSettings("anthropic"))
                .isEqualTo(new SiftProviderSettings("claude-haiku-4-5", "https://mine.invalid", 3));
    }

    @Test
    void theFieldsShownBelongToTheProviderTheDropdownFellBackTo() {
        final var settings = new Settings(new PathSettings(null, null, null), "gone-provider",
                Map.of("gone-provider", new SiftProviderSettings("a-model-of-theirs", null, null)),
                List.of(), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM);

        final SettingsView view = presenterOver(settings, new FixedSecretStore(new Absent())).view();

        assertThat(view.provider()).isEqualTo("external-agent");
        assertThat(view.model()).isNull();
        assertThat(view.modelUnrecognised()).isNull();
    }

    // Every provider left in this fixture offers models, so a check made against the configured id
    // instead would find no catalog and ask nothing.
    @Test
    void theStartUpCheckFollowsTheProviderTheDropdownFellBackTo() {
        final var settings = new Settings(new PathSettings(null, null, null), "gone-provider", Map.of(),
                List.of(), new MontageConfig(224, 5),
                ThemeChoice.SYSTEM);
        final List<String> checked = new ArrayList<>();
        final List<VisionProviderDescriptor> apiProvidersOnly = twoApiProvidersAndAnAgent().stream()
                .filter(provider -> provider.models() != null)
                .toList();
        final var settingsUseCase = new FixedSettingsUseCase(settings);
        final var catalog = checkingCatalog(apiProvidersOnly, id -> {
            checked.add(id);
            return new ProviderCheck.Accepted(MODELS);
        });
        final SettingsPresenter presenter = new SettingsPresenter(
                settingsUseCase, failingLibraryRootUseCase(), noViolations(), catalog,
                new VisionProviderPresenter(new FixedSecretStore(new InKeyring()), catalog, settingsUseCase),
                new FxProgressPort());

        presenter.refreshModelsAtStartup();

        assertThat(checked).containsExactly(presenter.view().provider());
    }

    @Test
    void anUnknownProviderFallsBackToOneNeedingNoCredential() {
        final var configured = new Settings(new PathSettings(null, null, null), "a-provider-this-build-lacks",
                Map.of(), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);

        final var presenter = presenterOver(configured, new FixedSecretStore(new Absent()));

        assertThat(presenter.view().provider()).isEqualTo("external-agent");
    }

    @Test
    void anUnknownProviderIsSaidToBeUnknown() {
        final var configured = new Settings(new PathSettings(null, null, null), "a-provider-this-build-lacks",
                Map.of(), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);

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

    // A config file is hand-editable, so an unusable root can reach a launch, and this is the one
    // screen that can correct it.
    @Test
    void theScreenStillDrawsWhenTheWorkingRootIsNotAPathThisSystemCouldHave() {
        final var presenter = presenterOver(settings(UNUSABLE_PATH, "/library", null),
                new FixedSecretStore(new Absent()));

        final SettingsView view = presenter.view();

        assertThat(view.workingRoot().value()).isEqualTo(UNUSABLE_PATH);
        assertThat(view.inbox().suggestion()).isEqualTo(SettingsPresenter.inboxSuggestion(null))
                .doesNotContain(UNUSABLE_PATH);
    }

    @Test
    void aWorkingRootThatIsNotAPathReachesTheSaveSeamRatherThanThrowing() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save(UNUSABLE_PATH, "", "", "anthropic", "claude-opus-5", "", 224, 5,
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
                "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome.libraryRoot()).isNotNull();
        assertThat(outcome.workingRoot()).isNull();
        assertThat(outcome.inbox()).isNull();
    }

    // The thrown message names configuration properties, which nobody using this screen has seen.
    @Test
    void aRefusedSaveKeepsTheThrownMessageOutOfTheSummaryOnceARowCarriesIt() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new IllegalStateException("sluice.paths.library-root (/gone) is not a folder");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()),
                violating(List.of(new NotADirectory(PathRole.LIBRARY_ROOT, Path.of("/gone")))));

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/gone", "/inbox",
                "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome.message()).doesNotContain("sluice.paths");
        assertThat(outcome.libraryRoot()).doesNotContain("sluice.paths");
    }

    @Test
    void aBusyJobIsReportedInTheWordsTheSeamAlreadyChose() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new JobInProgressException("Sluice is running a job. Finish it first.");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/library", "/inbox",
                "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome.message()).isEqualTo("Sluice is running a job. Finish it first.");
    }

    @Test
    void anUnforeseenRefusalIsWordedForAUserRatherThanShownRaw() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new IllegalStateException("writeAndApply: NoSuchFileException /etc/x");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = (SettingsPresenter.SaveOutcome.Refused) presenter.save("/repo", "/library", "/inbox",
                "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome.message())
                .startsWith("Your settings were not saved")
                .contains("bug")
                .contains("NoSuchFileException");
    }

    @Test
    void aProviderCallingAModelRefusesToSaveWithoutOne() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/library", "/inbox", "anthropic", "  ", "", 224, 5,
                "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Refused.class);
        assertThat(settingsUseCase.saved).isNull();
    }

    @Test
    void aRefusalWhileTheStartUpCheckIsOutNamesNoRetry() throws Exception {
        final var checking = new CountDownLatch(1);
        final var answering = new CountDownLatch(1);
        final Presenters presenters = presenterChecking(settings(null, null, null),
                _ -> answerOnceReleased(checking, answering, new ProviderCheck.Accepted(MODELS)));

        final Thread startUp = Thread.ofVirtual().start(presenters.settings()::refreshModelsAtStartup);
        assertThat(checking.await(10, TimeUnit.SECONDS)).isTrue();
        final SettingsPresenter.SaveOutcome outcome = presenters.settings()
                .save("", "", "", "anthropic", "", "", 224, 5, "SYSTEM");
        answering.countDown();
        startUp.join();

        assertThat(outcome).isInstanceOfSatisfying(SettingsPresenter.SaveOutcome.Refused.class,
                refused -> assertThat(refused.model()).doesNotContain("Retry").contains("Still asking"));
    }

    @Test
    void aRefusalAfterAFailedCheckSendsTheReaderToRetry() {
        final Presenters presenters = presenterChecking(settings(null, null, null),
                _ -> new ProviderCheck.Rejected());
        presenters.vision().refreshModels("anthropic");

        final SettingsPresenter.SaveOutcome outcome = presenters.settings()
                .save("", "", "", "anthropic", "", "", 224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOfSatisfying(SettingsPresenter.SaveOutcome.Refused.class,
                refused -> assertThat(refused.model()).contains("Retry"));
    }

    @Test
    void theExternalAgentSavesWithNoModelAtAll() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/library", "/inbox", "external-agent", "", "", 224, 5,
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

        presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "DARK");

        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.theme()).isEqualTo(ThemeChoice.DARK);
    }

    @Test
    void savingAThemePutsItInForceForWindowsAlreadyOpen() throws Exception {
        final var presenter = presenter(new FixedSettingsUseCase(settings(null, null, null)), new FixedSecretStore(new Absent()), noViolations());

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "DARK");
            return ThemeSelection.effectiveTheme().getValue();
        })).isEqualTo(Theme.DARK);
    }

    // Seeded with an explicit LIGHT rather than the SYSTEM default, so the assertion cannot pass by
    // accident on a machine whose desktop is already light.
    @Test
    void aRefusedSaveLeavesTheLookAlone() throws Exception {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        settingsUseCase.saveFailure = new IllegalStateException("Sluice is busy");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        assertThat(onFxThread(() -> {
            ThemeSelection.set(ThemeChoice.LIGHT);
            presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "DARK");
            return ThemeSelection.effectiveTheme().getValue();
        })).isEqualTo(Theme.LIGHT);
    }

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
        settingsUseCase.overrides.put("sluice.sift.provider", new ByEnvironmentVariable("sluice.sift.provider", "SLUICE_SIFT_PROVIDER"));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        assertThat(presenter.view().providerOverride()).contains("SLUICE_SIFT_PROVIDER");
    }

    @Test
    void saveWithNoLibraryRootChangeSucceeds() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "",
                224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Saved.class);
        final Settings saved = settingsUseCase.saved;
        assertThat(saved).isNotNull();
        assertThat(saved.provider()).isEqualTo("anthropic");
    }

    @Test
    void saveThatMovesTheLibraryRootAsksForAResolution() {
        final Path libraryRoot = Path.of("/library");
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new LibraryRootResolutionRequiredException(
                libraryRoot, "refused, for a log");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/new-library", "/inbox", "anthropic", "claude-opus-5", "",
                224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution.class);
        final var resolution = (SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution) outcome;
        assertThat(resolution.newLibraryRoot()).isEqualTo(Path.of("/new-library"));
        // The path is compared as a Path renders it, since a literal separator is right on one
        // platform and wrong on the other.
        assertThat(resolution.message())
                .contains(libraryRoot.toString())
                .doesNotContain("refused, for a log");
    }

    @Test
    void emptyingAConfiguredLibraryRootIsRefusedOnTheFieldRatherThanAsked() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new LibraryRootResolutionRequiredException(
                Path.of("/library"), "refused, for a log");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Refused.class);
        final var refused = (SettingsPresenter.SaveOutcome.Refused) outcome;
        assertThat(refused.libraryRoot()).contains("cannot be left blank");
        assertThat(refused.workingRoot()).isNull();
        assertThat(refused.inbox()).isNull();
    }

    @Test
    void aRefusedSaveCarriesTheRefusalMessage() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        settingsUseCase.saveFailure = new JobInProgressException("Sluice is busy");
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.save("/repo", "/library", "/inbox", "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(outcome).isEqualTo(new SettingsPresenter.SaveOutcome.Refused("Sluice is busy"));
    }

    // Both asserted together, so swapping the two resolutions is a failure.
    @Test
    void eachNamedMoveSendsItsOwnResolution() {
        final Path destination = Path.of("/new-library");

        final var copying = new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(3, 3, OLD_LIBRARY));
        presenterMoving(copying).moveLibraryRootCopyingTheIndex(refusedMoveTo(destination.toString()));
        assertThat(copying.received).isEqualTo(LibraryRootResolution.COPY_AND_KEEP_INDEX);

        final var starting = new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(0, 0, OLD_LIBRARY));
        presenterMoving(starting).moveLibraryRootWithAFreshIndex(refusedMoveTo(destination.toString()));
        assertThat(starting.received).isEqualTo(LibraryRootResolution.START_A_FRESH_INDEX);
    }

    @Test
    void moveLibraryRootReportsAFilesCopiedOutcome() {
        final var jobRunner = new JobRunner();
        final var library = new SucceedingLibraryRootUseCase(jobRunner, new CopiedAndMoved(12, 12, OLD_LIBRARY));
        final var presenter = presenterOverLibrary(new FixedSettingsUseCase(settings(null, null, null)), library);

        final var outcome =
                presenter.moveLibraryRoot(refusedMoveTo("/new-library"), LibraryRootResolution.COPY_AND_KEEP_INDEX);

        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.Moved.class);
        assertThat(outcome.message()).contains("12");
    }

    @Test
    void moveLibraryRootReportsAFailureFromTheJob() {
        final var jobRunner = new JobRunner();
        final var library = new FailingLibraryRootUseCase(jobRunner, new IllegalStateException("cannot move"));
        final var presenter = presenterOverLibrary(new FixedSettingsUseCase(settings(null, null, null)), library);

        final var outcome =
                presenter.moveLibraryRoot(refusedMoveTo("/new-library"), LibraryRootResolution.START_A_FRESH_INDEX);

        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.Failed.class);
        assertThat(outcome.message()).isEqualTo("cannot move");
    }

    @Test
    void aMoveStoresTheRestOfTheSaveItWasAskedAbout() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        final var presenter = presenterOverLibrary(settingsUseCase,
                new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(3, 3, OLD_LIBRARY)));
        final Settings pending = settings("/new-repo", "/new-library", "/new-inbox");

        final var outcome = presenter.moveLibraryRoot(
                new SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution(Path.of("/new-library"), pending,
                        "asking"),
                LibraryRootResolution.COPY_AND_KEEP_INDEX);

        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.Moved.class);
        assertThat(settingsUseCase.saved).isEqualTo(pending);
    }

    @Test
    void aCancelledCopySavesNothingAndSaysTheLibraryStayedPut() {
        final Path copiedInto = Path.of("/new-library");
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        final var presenter = presenterOverLibrary(settingsUseCase,
                new SucceedingLibraryRootUseCase(new JobRunner(), new CopyCancelled(2, 9, copiedInto)));

        final var outcome = presenter.moveLibraryRoot(refusedMoveTo("/new-library"),
                LibraryRootResolution.COPY_AND_KEEP_INDEX);

        assertThat(settingsUseCase.saved).isNull();
        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.NothingChanged.class);
        assertThat(outcome.message())
                .contains("You stopped the copy")
                .contains("still at its old folder")
                .contains("continues copying")
                .contains("never remove a Library folder")
                .contains(copiedInto.toString());
    }

    @Test
    void aMoveWhoseFollowingSaveFailsSaysTheLibraryMovedAnyway() {
        final var settingsUseCase = new FixedSettingsUseCase(settings("/repo", "/library", "/inbox"));
        settingsUseCase.saveFailure = new JobInProgressException("Sluice is running a job. Finish it first.");
        final var presenter = presenterOverLibrary(settingsUseCase,
                new SucceedingLibraryRootUseCase(new JobRunner(), new CopiedAndMoved(3, 3, OLD_LIBRARY)));

        final var outcome = presenter.moveLibraryRoot(refusedMoveTo("/new-library"),
                LibraryRootResolution.COPY_AND_KEEP_INDEX);

        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.Failed.class);
        assertThat(outcome.message())
                .contains("Your Library moved")
                .contains("Sluice is running a job");
    }

    @Test
    void aMoveFailingWithNothingToSaySaysSoRatherThanNothing() {
        final var library = new FailingLibraryRootUseCase(new JobRunner(), new IllegalStateException());
        final var presenter = presenterOverLibrary(new FixedSettingsUseCase(settings(null, null, null)), library);

        final var outcome = presenter.moveLibraryRoot(refusedMoveTo("/new-library"),
                LibraryRootResolution.START_A_FRESH_INDEX);

        assertThat(outcome).isInstanceOf(SettingsPresenter.MoveOutcome.Failed.class);
        assertThat(outcome.message())
                .contains("The Library did not move")
                .contains("IllegalStateException");
    }

    @Test
    void savingFolderRootsCarriesTheModelAProviderThatNeedsOneWouldBeRefusedWithout() {
        final var settingsUseCase = new FixedSettingsUseCase(new Settings(new PathSettings(null, null, null),
                "external-agent", Map.of(), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var outcome = presenter.saveFolderRootsAndProvider("/repo", "/library", "/inbox", "anthropic");

        assertThat(outcome).isInstanceOf(SettingsPresenter.SaveOutcome.Saved.class);
        assertThat(settingsUseCase.saved).isNotNull();
        assertThat(settingsUseCase.saved.providerSettings("anthropic").model())
                .isEqualTo(MODELS.recommended());
    }

    @Test
    void savingFolderRootsStoresNoModelForAProviderThatRunsNone() {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.saveFolderRootsAndProvider("/repo", "/library", "/inbox", "external-agent");

        assertThat(settingsUseCase.saved).isNotNull();
        assertThat(settingsUseCase.saved.providerSettings("external-agent").model()).isNull();
    }

    @Test
    void savingFolderRootsLeavesEverySettingItDoesNotAskAboutAsItWas() {
        final var configured = new Settings(new PathSettings(null, null, null), "external-agent",
                Map.of("external-agent", new SiftProviderSettings(null, "https://proxy.test", 7)), List.of(),
                new MontageConfig(320, 4), ThemeChoice.DARK);
        final var settingsUseCase = new FixedSettingsUseCase(configured);
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.saveFolderRootsAndProvider("/repo", "/library", "/inbox", "external-agent");

        assertThat(settingsUseCase.saved).isNotNull();
        assertThat(settingsUseCase.saved.montage()).isEqualTo(new MontageConfig(320, 4));
        assertThat(settingsUseCase.saved.theme()).isEqualTo(ThemeChoice.DARK);
        assertThat(settingsUseCase.saved.providerSettings("external-agent").endpoint())
                .isEqualTo("https://proxy.test");
        assertThat(settingsUseCase.saved.providerSettings("external-agent").maxRetries()).isEqualTo(7);
    }

    @Test
    void saveNeverCreatesAFolderForAPathThatIsNotOurOwnSuggestion(final @TempDir Path tempDir) {
        final Path untouched = tempDir.resolve("a-user-typed-folder-that-does-not-exist-yet");
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        presenter.save(untouched.toString(), "", "", "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

        assertThat(untouched).doesNotExist();
    }

    @Test
    void aRefusedSaveCreatesNoFolder(final @TempDir Path tempDir) {
        final Path inbox = tempDir.resolve("Inbox");
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var presenter = presenter(settingsUseCase, new FixedSecretStore(new Absent()), noViolations());

        final var refused = presenter.save(tempDir.toString(), "", inbox.toString(), "anthropic", "", "",
                224, 5, "SYSTEM");

        assertThat(refused).isInstanceOf(SettingsPresenter.SaveOutcome.Refused.class);
        assertThat(inbox).doesNotExist();

        presenter.save(tempDir.toString(), "", inbox.toString(), "anthropic", "claude-opus-5", "", 224, 5, "SYSTEM");

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

    @Test
    void aMoveThatHasReportedNothingYetSaysOnlyThatItIsMoving() throws Exception {
        final var lines = new ArrayList<String>();

        try (AutoCloseable _ = presenterWatching(new FxProgressPort(Runnable::run)).reportMoving(lines::add)) {
            assertThat(lines).containsExactly("Moving the library...");
        }
    }

    @Test
    void aMoveInFlightSaysWhichPhaseItIsOnAndHowFarThroughItIs() throws Exception {
        final var port = new FxProgressPort(Runnable::run);
        final var lines = new ArrayList<String>();

        try (AutoCloseable _ = presenterWatching(port).reportMoving(lines::add)) {
            port.phaseStarted("Copying");
            port.tick("Copying", 1500, 12000);
        }

        assertThat(lines).last().isEqualTo("Copying... 1,500 of 12,000");
    }

    @Test
    void aPhaseWithNoTotalToCountAgainstIsNamedWithoutANumber() throws Exception {
        final var port = new FxProgressPort(Runnable::run);
        final var lines = new ArrayList<String>();

        try (AutoCloseable _ = presenterWatching(port).reportMoving(lines::add)) {
            port.phaseStarted("Reading the library");
        }

        assertThat(lines).last().isEqualTo("Reading the library...");
    }

    @Test
    void aClosedHandleIgnoresPhasesReportedAfterIt() throws Exception {
        final var port = new FxProgressPort(Runnable::run);
        final var lines = new ArrayList<String>();
        presenterWatching(port).reportMoving(lines::add).close();

        port.phaseStarted("Copying");

        assertThat(lines).containsExactly("Moving the library...");
    }

    @Test
    void aScreenShowingWhatIsStoredHasNothingToLose() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"),
                new FixedSecretStore(new Absent()));

        assertThat(presenter.hasUnsavedEdits(drawnBy(presenter))).isFalse();
    }

    @Test
    void aTypedFolderPathIsSomethingToLose() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"),
                new FixedSecretStore(new Absent()));
        final SettingsPresenter.SettingsEdits typed = drawnBy(presenter);

        assertThat(presenter.hasUnsavedEdits(new SettingsPresenter.SettingsEdits("/somewhere-else",
                typed.libraryRoot(), typed.inbox(), typed.provider(), typed.model(), typed.endpoint(),
                typed.tileSize(), typed.tilesPerRow()))).isTrue();
    }

    @Test
    void aSheetNumberNudgedIsSomethingToLose() {
        final var presenter = presenterOver(settings("/repo", "/library", "/inbox"),
                new FixedSecretStore(new Absent()));
        final SettingsPresenter.SettingsEdits typed = drawnBy(presenter);

        assertThat(presenter.hasUnsavedEdits(new SettingsPresenter.SettingsEdits(typed.workingRoot(),
                typed.libraryRoot(), typed.inbox(), typed.provider(), typed.model(), typed.endpoint(),
                typed.tileSize(), typed.tilesPerRow() + 1))).isTrue();
    }

    // Built from view() the way the screen's controls are filled from it, so this is the round trip
    // a reader who changes nothing would make.
    private static SettingsPresenter.SettingsEdits drawnBy(final SettingsPresenter presenter) {
        final SettingsView view = presenter.view();
        final String model = view.model() instanceof final SettingsView.ModelPicker.Options options
                ? options.selected() : "";
        return new SettingsPresenter.SettingsEdits(view.workingRoot().value(), view.libraryRoot().value(),
                view.inbox().value(), view.provider(), model,
                view.endpoint() == null ? "" : view.endpoint(),
                view.tileSize(), view.tilesPerRow());
    }

    private static SettingsPresenter presenterOver(final Settings settings, final SecretStore secretStore) {
        return presenterOver(settings, secretStore, noViolations());
    }

    private static SettingsPresenter presenterOver(final Settings settings, final SecretStore secretStore,
                                                    final PathValidationUseCase pathValidation) {
        return presenter(new FixedSettingsUseCase(settings), secretStore, pathValidation);
    }

    // The refusal a move answers, built where a save would have produced one. Only the destination
    // and the document it carries matter to the methods under test.
    private static SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution refusedMoveTo(final String destination) {
        return new SettingsPresenter.SaveOutcome.NeedsLibraryRootResolution(Path.of(destination),
                settings("/repo", destination, "/inbox"), "asking");
    }

    private static SettingsPresenter presenterMoving(final LibraryRootUseCase libraryRoot) {
        return presenterOverLibrary(new FixedSettingsUseCase(settings(null, null, null)), libraryRoot);
    }

    private static SettingsPresenter presenterWatching(final FxProgressPort progress) {
        final var settingsUseCase = new FixedSettingsUseCase(settings(null, null, null));
        final var visionProvider = new VisionProviderPresenter(new FixedSecretStore(new Absent()),
                twoProviders(), settingsUseCase);
        return new SettingsPresenter(settingsUseCase, failingLibraryRootUseCase(), noViolations(),
                twoProviders(), visionProvider, progress);
    }

    private static SettingsPresenter presenter(final SettingsUseCase settingsUseCase, final SecretStore secretStore,
                                               final PathValidationUseCase pathValidation) {
        final var visionProvider = new VisionProviderPresenter(secretStore, twoProviders(), settingsUseCase);
        return new SettingsPresenter(settingsUseCase, failingLibraryRootUseCase(), pathValidation, twoProviders(),
                visionProvider, new FxProgressPort());
    }

    // The library-root move tests never touch the credential or model catalogue, so the vision
    // presenter behind this one is a fixed, unremarkable fixture.
    private static SettingsPresenter presenterOverLibrary(final SettingsUseCase settingsUseCase,
                                                           final LibraryRootUseCase libraryRootUseCase) {
        final var visionProvider = new VisionProviderPresenter(new FixedSecretStore(new Absent()), twoProviders(),
                settingsUseCase);
        return new SettingsPresenter(settingsUseCase, libraryRootUseCase, noViolations(), twoProviders(),
                visionProvider, new FxProgressPort());
    }

    // One of each type, and only the API one takes a key. Every question this screen asks a provider
    // is answered differently by these two, so a presenter reading the wrong one shows it.
    private static VisionProviderCatalog twoProviders() {
        return catalogOf(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, SETUP_GUIDE),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(), Set.of(), null, null, null, null));
    }

    private record Presenters(SettingsPresenter settings, VisionProviderPresenter vision) {
    }

    // The same anthropic/external-agent pair twoProviders() offers, but with a real answer for a
    // credential check, where catalogOf's own fixture throws AssertionError instead.
    private static Presenters presenterChecking(final Settings settings,
                                                final Function<String, ProviderCheck> checkById) {
        final var settingsUseCase = new FixedSettingsUseCase(settings);
        final var catalog = checkingCatalog(checkById);
        final var vision = new VisionProviderPresenter(new FixedSecretStore(new InKeyring()), catalog,
                settingsUseCase);
        return new Presenters(
                new SettingsPresenter(settingsUseCase, failingLibraryRootUseCase(), noViolations(), catalog,
                        vision, new FxProgressPort()),
                vision);
    }

    private static VisionProviderCatalog checkingCatalog(final Function<String, ProviderCheck> checkById) {
        return checkingCatalog(List.of(
                new VisionProviderDescriptor("anthropic", "Anthropic",
                        Set.of(ProviderSetting.MODEL, ProviderSetting.ENDPOINT, ProviderSetting.CREDENTIAL),
                        Set.of(ProviderSetting.MODEL), ANTHROPIC_KEY, MODELS, null, null),
                new VisionProviderDescriptor("external-agent", "External agent",
                        Set.of(), Set.of(), null, null, null, null)), checkById);
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
            public ProviderCheck check(final String id, final SiftProviderSettings candidate) {
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
                        Set.of(), Set.of(), null, null, null, null));
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
            public ProviderCheck check(final String id, final SiftProviderSettings candidate) {
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

    @SuppressWarnings("SameParameterValue")
    private static Settings settingsWithTheme(final ThemeChoice theme) {
        final Settings base = settings(null, null, null);
        return new Settings(base.paths(), base.provider(), base.providerSettingsById(), base.categories(),
                base.montage(), theme);
    }

    private static Settings settings(final @Nullable String workingRoot, final @Nullable String libraryRoot,
                                     final @Nullable String inbox) {
        return new Settings(new PathSettings(workingRoot, libraryRoot, inbox), "anthropic",
                Map.of("anthropic", new SiftProviderSettings("claude-opus-5", null, null)), List.of(),
                new MontageConfig(224, 5), ThemeChoice.SYSTEM);
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
        public Optional<SettingOverride> higherPrecedenceOverride(final String property) {
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
