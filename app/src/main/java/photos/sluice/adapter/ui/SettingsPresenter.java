package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.JobInProgressException;
import photos.sluice.application.port.in.LibraryRootMoveOutcome;
import photos.sluice.application.port.in.LibraryRootMoveNeedsAResolutionException;
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
import photos.sluice.application.port.out.SecretHolding.Holding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStatus.InEnvironment;
import photos.sluice.application.port.out.SecretStatus.InKeyring;
import photos.sluice.application.port.out.SecretStatus.StoredLocation;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.SettingOverride.ByAnotherSource;
import photos.sluice.application.port.out.SettingOverride.ByEnvironmentVariable;
import photos.sluice.application.port.out.StaleSecretNotClearedException;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.job.WatchMode;
import photos.sluice.domain.paths.PathRole;
import photos.sluice.domain.paths.PathViolation;
import photos.sluice.domain.paths.PathViolation.NotADirectory;
import photos.sluice.domain.paths.PathViolation.NotAPath;
import photos.sluice.domain.paths.PathViolation.NotConfigured;
import photos.sluice.domain.paths.PathViolation.Overlap;
import photos.sluice.domain.paths.PathViolation.Unreadable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Decides what the Settings screen shows and carries out what a user does on it.
 *
 * <p>It knows no provider by name. Which ones exist, what each of them uses, and the credential
 * each authenticates with are all asked of {@link VisionProviderCatalog}. So a provider added
 * later reaches this screen without being named here.
 */
@Component
@Profile("!cli")
public class SettingsPresenter {

    private static final List<SettingsView.ThemeOption> THEMES = Arrays.stream(ThemeChoice.values())
            .map(choice -> new SettingsView.ThemeOption(choice.name(), themeLabel(choice)))
            .toList();

    // What a transport retry count is allowed to be, decided here rather than by whichever control
    // happens to render it. The number is a product judgement rather than a type limit. The SDK
    // retries with exponential backoff, so a call failing this many times running is one the user
    // wants told about rather than retried further.
    private static final int MAX_RETRIES_LIMIT = 10;

    // These four numbers are plausible rather than measured, unlike the retry limit above. Nobody
    // has derived them. Whoever next holds real cost data for a sheet replaces them and says why.
    private static final SettingsView.NumberRange TILE_SIZE_RANGE = new SettingsView.NumberRange(16, 1024, 16);
    private static final SettingsView.NumberRange TILES_PER_ROW_RANGE = new SettingsView.NumberRange(1, 12, 1);

    private static final String NO_CREDENTIAL_TO_KEEP = "This provider takes no key.";

    // A store's own refusal is written for a log. It names the entry Sluice asked for and whatever
    // code the platform handed back, and a user typed neither. It is still the only thing telling
    // one refusal from another. So it is kept, behind a sentence saying what happened, what
    // survives, and what to try.
    //
    // Two ways out rather than one, because they reach different places. A save lands in the
    // highest tier that says it can be used, then clears whatever sits above it. So a key that is
    // unreadable higher up is gone once a save succeeds below it. A remove is what reaches every
    // writable tier, including one a save would skip. Removing alone leaves the user with no key
    // at all, which is why both routes end on saving.
    private static final String STORE_UNREADABLE = "Sluice could not read the key saved for this provider. "
            + "The credential store on this computer refused to answer. Nothing else you have configured is "
            + "affected. Paste your key in again and save: that alone often fixes it, because saving also "
            + "clears any copy held somewhere Sluice ranks higher. If saving does not take, use Remove, "
            + "which clears the key from every place Sluice can reach, then save again. If it keeps "
            + "refusing, report this as a bug in Sluice, quoting this: ";

    private static final String FIELDS_ARE_MARKED =
            "These settings were not saved. What needs fixing is marked under each field that failed.";

    private static final Map<PathRole, String> ROLE_LABELS = Map.of(
            PathRole.REPO_ROOT, "Working root", PathRole.LIBRARY_ROOT, "Library root", PathRole.INBOX, "Inbox");

    private final SettingsUseCase settingsUseCase;
    private final LibraryRootUseCase libraryRootUseCase;
    private final SecretStore secretStore;
    private final PathValidationUseCase pathValidation;
    private final VisionProviderCatalog providers;

    /**
     * Creates the presenter over the use cases the screen reads and writes through.
     *
     * @param settingsUseCase {@link SettingsUseCase} reads and saves the app's settings
     * @param libraryRootUseCase {@link LibraryRootUseCase} moves the library root when it changes
     * @param secretStore {@link SecretStore} where a provider's credential is stored
     * @param pathValidation {@link PathValidationUseCase} checks the folder roots for the per-field
     *         violation each row shows
     * @param providers {@link VisionProviderCatalog} which providers this install has, and what
     *         each of them uses
     */
    public SettingsPresenter(final SettingsUseCase settingsUseCase, final LibraryRootUseCase libraryRootUseCase,
                             final SecretStore secretStore, final PathValidationUseCase pathValidation,
                             final VisionProviderCatalog providers) {
        this.settingsUseCase = settingsUseCase;
        this.libraryRootUseCase = libraryRootUseCase;
        this.secretStore = secretStore;
        this.pathValidation = pathValidation;
        this.providers = providers;
    }

    /**
     * What to draw right now: the settings in force and the credential row freshly read.
     *
     * <p>Carries every override note a saved value would lose to at the next launch.
     *
     * @return {@link SettingsView} the screen's current display state
     */
    public SettingsView view() {
        final Settings settings = this.settingsUseCase.settings();
        final var paths = settings.paths();
        // The settings of whichever provider this screen shows as selected. That is not the
        // configured one when the configured id names a provider this install does not have.
        final String shownProvider = this.resolvedProviderId(settings.provider());
        final var providerSettings = settings.providerSettings(shownProvider);
        final var montage = settings.montage();
        final Map<PathRole, String> violations = this.violationsByRole(paths);
        return new SettingsView(
                folderField(paths.repoRoot(), workingRootSuggestion(), violations.get(PathRole.REPO_ROOT)),
                folderField(paths.libraryRoot(), librarySuggestion(), violations.get(PathRole.LIBRARY_ROOT)),
                folderField(paths.inbox(), inboxSuggestion(paths.repoRoot()), violations.get(PathRole.INBOX)),
                shownProvider, this.providerChoices(),
                this.overrideNote("sluice.cull.provider"),
                this.unrecognisedProviderNote(settings.provider()),
                providerSettings.model(), this.overrideNote(providerProperty(shownProvider, "model")),
                providerSettings.endpoint(), this.overrideNote(providerProperty(shownProvider, "endpoint")),
                providerSettings.maxRetries(),
                this.overrideNote(providerProperty(shownProvider, "max-retries")), MAX_RETRIES_LIMIT,
                settings.externalAgent().mode() == WatchMode.WATCH,
                this.overrideNote("sluice.cull.external-agent.mode"),
                this.secretRow(shownProvider),
                montage.tileSize(), TILE_SIZE_RANGE, this.overrideNote("sluice.montage.tile-size"),
                montage.tilesPerRow(), TILES_PER_ROW_RANGE, this.overrideNote("sluice.montage.tiles-per-row"),
                settings.theme().name(), THEMES, this.overrideNote("sluice.ui.theme"));
    }

    /**
     * Puts the saved look in force, for a window about to open.
     *
     * <p>Without this the app would follow the desktop until the session's first save. A user who
     * chose Light on a dark desktop would then meet a dark window every launch.
     */
    public void applySavedTheme() {
        ThemeSelection.set(this.settingsUseCase.settings().theme());
    }

    /**
     * Puts a theme in force and saves it, the moment it is picked.
     *
     * <p>Every other field on this screen waits for Save. A theme reads as a display setting
     * rather than a configuration one. The desktop's own theme switch already takes effect with no
     * confirmation, and picking one here is expected to behave the same way.
     *
     * <p>Built from the settings already on disk with only the theme swapped, never from what the
     * screen currently shows. An unsaved folder path or a typed-but-not-yet-saved model is then
     * never persisted by clicking a radio. The paths carried through are therefore always equal to
     * what is already in force. That is what lets the save seam take its unchanged-paths fast
     * path: no path validation, no job-in-progress check, nothing that could refuse a theme for a
     * reason that has nothing to do with it.
     *
     * @param themeId {@link String} the id of the {@link SettingsView.ThemeOption} just picked
     */
    public void chooseTheme(final String themeId) {
        final ThemeChoice theme = ThemeChoice.valueOf(themeId);
        ThemeSelection.set(theme);
        final Settings current = this.settingsUseCase.settings();
        this.settingsUseCase.save(new Settings(current.paths(), current.provider(),
                current.providerSettingsById(), current.categories(), current.externalAgent(),
                current.montage(), theme));
    }

    /**
     * The folder a working-root picker opens on when nothing is set.
     *
     * <p>Not Documents, which Windows commonly redirects into OneDrive. The working root is
     * transient staging and should not ride a cloud sync.
     *
     * @return {@link String} the suggested working-root folder
     */
    public static String workingRootSuggestion() {
        return home().resolve("Sluice").toString();
    }

    /**
     * The folder a library-root picker opens on when nothing is set. Every desktop OS carries a
     * Pictures folder by convention.
     *
     * @return {@link String} the suggested library-root folder
     */
    public static String librarySuggestion() {
        return home().resolve("Pictures").resolve("Sluice").toString();
    }

    /**
     * The folder an inbox picker opens on when nothing is set: under the working root, tracking its
     * live value until the inbox field is edited directly.
     *
     * <p>A working root this system could never have is treated as no working root at all. That
     * value is text a user typed, or wrote into their own config file. This screen is where they go
     * to correct it, and a refusal to answer here would take that screen down with it.
     *
     * @param repoRoot the working root's own current text, possibly unset or unusable
     * @return {@link String} the suggested inbox folder
     */
    public static String inboxSuggestion(final @Nullable String repoRoot) {
        final String base = repoRoot == null || repoRoot.isBlank() ? workingRootSuggestion() : repoRoot;
        try {
            return Path.of(base).resolve("Inbox").toString();
        } catch (final InvalidPathException e) {
            return Path.of(workingRootSuggestion()).resolve("Inbox").toString();
        }
    }

    /**
     * Saves the given field values.
     *
     * <p>Creates a folder root that was left exactly as this screen suggested it. Our own suggestion
     * must never trip the save-time refusal against a folder that does not exist yet.
     *
     * @param workingRoot {@link String} the working-root field's text
     * @param libraryRoot {@link String} the library-root field's text
     * @param inbox {@link String} the inbox field's text
     * @param provider {@link String} the selected provider id
     * @param model {@link String} the model field's text, possibly blank
     * @param endpoint {@link String} the endpoint field's text, possibly blank
     * @param maxRetries {@link Integer} the transport retry count, or null to leave it unset
     * @param watchAutomatically boolean whether a waiting cull should resume on its own once ready;
     *         false waits for an explicit resume
     * @param tileSize int the montage tile size
     * @param tilesPerRow int the montage tiles per row
     * @param themeId {@link String} id of the look the user picked, from a {@link SettingsView.ThemeOption}
     * @return {@link SaveOutcome} what happened
     */
    public SaveOutcome save(final String workingRoot, final String libraryRoot, final String inbox,
                            final String provider, final String model, final String endpoint,
                            final @Nullable Integer maxRetries, final boolean watchAutomatically,
                            final int tileSize, final int tilesPerRow, final String themeId) {
        // Before anything touches the disk. What a provider needs is answerable from the field
        // values alone, so refusing on it leaves no folder behind.
        final String missing = this.whatThisProviderNeeds(provider, model);
        if (missing != null) {
            return SaveOutcome.Refused.markingTheModel(missing);
        }
        createIfItIsOurOwnSuggestion(workingRoot, workingRootSuggestion());
        createIfItIsOurOwnSuggestion(libraryRoot, librarySuggestion());
        createIfItIsOurOwnSuggestion(inbox, inboxSuggestion(workingRoot));
        final var watchMode = watchAutomatically ? WatchMode.WATCH : WatchMode.MANUAL;
        try {
            // Inside the guard with everything else that can refuse. An id this class never put in
            // the list it handed the screen is a refusal to report. Not an exception to escape into
            // a button handler.
            final ThemeChoice theme = ThemeChoice.valueOf(themeId);
            final var settings = new Settings(
                    new PathSettings(blankToNull(workingRoot), blankToNull(libraryRoot), blankToNull(inbox)),
                    provider,
                    this.providerSettingsWith(provider,
                            new CullProviderSettings(blankToNull(model), blankToNull(endpoint), maxRetries)),
                    this.settingsUseCase.settings().categories(), new ExternalAgentSettings(watchMode),
                    new MontageConfig(tileSize, tilesPerRow), theme);
            this.settingsUseCase.save(settings);
            // After the save rather than before it, so a refused save cannot leave the app wearing a
            // look its own settings do not name.
            ThemeSelection.set(theme);
            return new SaveOutcome.Saved();
        } catch (final LibraryRootMoveNeedsAResolutionException e) {
            return new SaveOutcome.NeedsLibraryRootResolution(Path.of(libraryRoot),
                    wordLibraryRootMove(e.previousLibraryRoot()));
        } catch (final RuntimeException e) {
            return this.refusalMarkingItsFields(e, new PathSettings(blankToNull(workingRoot),
                    blankToNull(libraryRoot), blankToNull(inbox)));
        }
    }

    /**
     * Words a refusal, and marks whichever folder rows the refused values put at fault.
     *
     * <p>Judged against the values being saved rather than the ones in force. Those are what the
     * user is looking at, and a refused save leaves the ones in force untouched, so marking against
     * them would mark nothing at all.
     *
     * <p>The rows are marked whatever refused the save. A busy job and an unusable folder can both
     * be true, and the one reported is not always the one a field can show.
     *
     * @param refusal {@link RuntimeException} what the save seam threw
     * @param candidate {@link PathSettings} the roots this save was trying to put in force
     * @return {@link SaveOutcome.Refused} the refusal, with a message per row at fault
     */
    private SaveOutcome.Refused refusalMarkingItsFields(final RuntimeException refusal,
                                                        final PathSettings candidate) {
        final Map<PathRole, String> byRole = this.violationsByRole(candidate);
        // Once the rows say what is wrong in this screen's own words, the summary only has to send
        // the reader to them.
        final String summary = byRole.isEmpty() ? wordedForAUser(refusal) : FIELDS_ARE_MARKED;
        return new SaveOutcome.Refused(summary, byRole.get(PathRole.REPO_ROOT),
                byRole.get(PathRole.LIBRARY_ROOT), byRole.get(PathRole.INBOX), null);
    }

    /**
     * Carries out a library-root move already resolved by the user, blocking until it finishes.
     * Called off the FX thread: a move can copy a library for as long as the library takes.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @param resolution {@link LibraryRootResolution} what to do about the hash index
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRoot(final Path newLibraryRoot, final LibraryRootResolution resolution) {
        try {
            final LibraryRootMoveOutcome outcome =
                    this.libraryRootUseCase.moveLibraryRoot(newLibraryRoot, resolution).join();
            return new MoveOutcome(true, wordMoveOutcome(outcome));
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            return new MoveOutcome(false, cause == null ? e.getMessage() : cause.getMessage());
        } catch (final RuntimeException e) {
            return new MoveOutcome(false, e.getMessage());
        }
    }

    /**
     * Carries out a library-root move, copying the old library into the new one and keeping the
     * hash index. Named rather than taking a {@link LibraryRootResolution}, so the view that offers
     * this choice never has to name that type itself.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRootCopyingTheIndex(final Path newLibraryRoot) {
        return this.moveLibraryRoot(newLibraryRoot, LibraryRootResolution.COPY_AND_KEEP_INDEX);
    }

    /**
     * Carries out a library-root move, filing the old hash index aside and starting a fresh one.
     *
     * @param newLibraryRoot {@link Path} the folder the library moves to
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRootWithAFreshIndex(final Path newLibraryRoot) {
        return this.moveLibraryRoot(newLibraryRoot, LibraryRootResolution.START_A_FRESH_INDEX);
    }

    /**
     * Saves a fresh credential for one provider.
     *
     * @param providerId {@link String} the provider the key belongs to
     * @param key {@link String} the credential to store
     * @return {@link String} an error message when the store refused it, or null on success
     */
    public @Nullable String saveSecret(final String providerId, final String key) {
        final Optional<SecretId> credential = this.credentialOf(providerId);
        if (credential.isEmpty()) {
            return NO_CREDENTIAL_TO_KEEP;
        }
        try {
            this.secretStore.save(credential.get(), key);
            return null;
        } catch (final StaleSecretNotClearedException e) {
            return "Your new key was saved, but an older one that would usually take precedence could not "
                    + "be cleared, so the older one may still be what gets used. Try Remove, then save this "
                    + "key again. If that keeps happening, report it as a bug in Sluice, quoting this: "
                    + e.getMessage();
        } catch (final SecretStoreException e) {
            return "Sluice could not save this key. The credential store on this computer refused it. "
                    + "Nothing else you have configured is affected. Report this as a bug in Sluice, "
                    + "quoting this: " + e.getMessage();
        }
    }

    /**
     * Clears one provider's stored credential from every writable tier.
     *
     * @param providerId {@link String} the provider whose key to clear
     * @return {@link String} an error message when the clear was only partial, or null on success
     */
    public @Nullable String removeSecret(final String providerId) {
        final Optional<SecretId> credential = this.credentialOf(providerId);
        if (credential.isEmpty()) {
            return NO_CREDENTIAL_TO_KEEP;
        }
        try {
            this.secretStore.remove(credential.get());
            return null;
        } catch (final SecretStoreException e) {
            return "Sluice could not clear this key from everywhere it is held, so it may still be what "
                    + "gets used. Nothing else you have configured is affected. Report this as a bug in "
                    + "Sluice, quoting this: " + e.getMessage();
        }
    }

    /**
     * The credential block for one provider, freshly read.
     *
     * @param providerId {@link String} the provider whose credential to describe
     * @return {@link SettingsView.SecretRow} what that block shows
     */
    public SettingsView.SecretRow secretRow(final String providerId) {
        final Optional<SecretId> credential = this.credentialOf(providerId);
        if (credential.isEmpty()) {
            return new SettingsView.SecretRow("", null, null, NO_CREDENTIAL_TO_KEEP, false);
        }
        final SecretId id = credential.get();
        final SecretStatus status;
        try {
            status = this.secretStore.status(id);
        } catch (final SecretStoreException e) {
            // Offered as holding something, on a store that would refuse to be counted too. What is
            // there cannot be established. The two controls this drives are the only way out:
            // Remove clears whatever is reachable, and a fresh key still saves over it.
            return new SettingsView.SecretRow(this.reassuranceLine(), null, null,
                    STORE_UNREADABLE + e.getMessage(), true);
        }
        final String environmentOverride = status instanceof InEnvironment(final String variableName)
                ? "The " + variableName + " environment variable outranks anything saved here, "
                        + "and keeps answering reads until it is unset."
                : null;
        return new SettingsView.SecretRow(this.reassuranceLine(), environmentOverride,
                this.multiHolderNote(id), null, this.hasAStoredValue(id));
    }

    /**
     * The credential identity one provider authenticates with.
     *
     * <p>Asked of the provider rather than mapped here. Which environment variable overrides a key,
     * and the name it is stored under, are the authenticating provider's own business.
     *
     * @param providerId {@link String} the provider to ask about
     * @return an {@link Optional} of {@link SecretId} its credential, empty when it takes none
     */
    private Optional<SecretId> credentialOf(final String providerId) {
        return this.providers.byId(providerId).map(VisionProviderDescriptor::credential);
    }

    /**
     * Whether any writable tier actually holds a credential, for {@code Remove}'s enabled state and
     * the Save-versus-Replace label.
     *
     * <p>Not the same question {@link #secretRow}'s status sentence answers. {@link SecretStatus}
     * names the tier a read would win from, and an environment variable can win a read while nothing
     * is stored underneath it. Asking that question here would leave {@code Remove} enabled, and
     * labelled as if there were something to replace, on a machine where it does nothing.
     *
     * @param id {@link SecretId} the credential to ask about
     * @return boolean true when a stored tier holds a value
     */
    private boolean hasAStoredValue(final SecretId id) {
        return this.secretStore.holdings(id).stream()
                .anyMatch(holding -> holding.location() instanceof StoredLocation && holding.holding() == Holding.HOLDS);
    }

    private String reassuranceLine() {
        final Optional<StoredLocation> whereASaveWouldLand = this.secretStore.whereASaveWouldStoreIt();
        final String base = whereASaveWouldLand.isPresent() && whereASaveWouldLand.get() instanceof InKeyring
                ? "Saved to this computer's own credential store."
                : "Saved to a protected file on this computer.";
        return base + " Never shown to any AI agent, only ever sent to that provider's own API.";
    }

    private @Nullable String multiHolderNote(final SecretId id) {
        final List<SecretHolding> holdings = this.secretStore.holdings(id);
        final long holders = holdings.stream().filter(h -> h.holding() == Holding.HOLDS).count();
        if (holders < 2) {
            return null;
        }
        final boolean anyUnaskable = holdings.stream().anyMatch(h -> h.holding() == Holding.COULD_NOT_BE_ASKED);
        return "More than one place on this computer holds a key for this provider. Remove clears "
                + "every one Sluice can reach."
                + (anyUnaskable ? " One place did not answer, so there may be another beyond these." : "");
    }

    private @Nullable String overrideNote(final String property) {
        return this.settingsUseCase.overriddenAboveTheConfigFile(property)
                .map(SettingsPresenter::wordOverride)
                .orElse(null);
    }

    /**
     * The property name one provider's own setting is configured under. Each provider keeps its own
     * block, so the note about an environment override has to name the block it belongs to.
     *
     * @param providerId {@link String} the provider whose block it sits in
     * @param setting {@link String} the setting's own key within that block
     * @return {@link String} the full property name
     */
    private static String providerProperty(final String providerId, final String setting) {
        return "sluice.cull.provider-settings." + providerId + "." + setting;
    }

    /**
     * The whole per-provider settings map with one provider's block replaced.
     *
     * <p>A save carries one provider's fields, because that is all a screen shows at a time.
     * Writing only those would drop every other provider's, and a user swapping provider and saving
     * would lose the model they had configured for the one they left.
     *
     * @param providerId {@link String} the provider being saved
     * @param edited {@link CullProviderSettings} the values that provider is being saved with
     * @return a {@link Map} of {@link String} to {@link CullProviderSettings} every provider's
     *         settings, with this one's replaced
     */
    private Map<String, CullProviderSettings> providerSettingsWith(final String providerId,
                                                                   final CullProviderSettings edited) {
        final var merged = new LinkedHashMap<>(this.settingsUseCase.settings().providerSettingsById());
        merged.put(providerId, edited);
        return merged;
    }

    /**
     * What the chosen provider is missing, or null when it has everything it needs.
     *
     * <p>Required is per provider, not per field. A model id is what an API-backed provider cannot
     * run without, and is meaningless to one whose judgement comes from an agent the user runs. So
     * the same blank field refuses one save and is correct in the other.
     *
     * <p>Caught here rather than at cull time, which is where the provider itself would raise it.
     * That is a run the user has already started, against settings they last saw accepted.
     *
     * @param providerId {@link String} the provider being saved
     * @param model {@link String} the model field's text, possibly blank
     * @return {@link String} what to tell the user, or null when nothing is missing
     */
    private @Nullable String whatThisProviderNeeds(final String providerId, final String model) {
        final boolean modelIsRequired = this.providers.byId(providerId)
                .map(VisionProviderDescriptor::required)
                .orElseGet(Set::of)
                .contains(ProviderSetting.MODEL);
        if (modelIsRequired && model.isBlank()) {
            return "This provider needs a model id before it can read any photos. "
                    + "Fill in Model, or choose a provider that does not call a model.";
        }
        return null;
    }

    /**
     * What a screen calls one look. A switch, so a fourth {@link ThemeChoice} fails to compile here
     * rather than reaching a user as a constant name.
     *
     * @param choice {@link ThemeChoice} the look to name
     * @return {@link String} the label for it
     */
    private static String themeLabel(final ThemeChoice choice) {
        return switch (choice) {
            case SYSTEM -> "System default";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }

    // Both halves earn their place. Told only that something outranks this field, a user reads it as
    // dead and stops. A save here does change the running app, and only stops applying at the next
    // launch, which is the part that decides whether saving is worth anything.
    /**
     * What to ask a user whose save would move the library root.
     *
     * <p>Worded here rather than taken from the refusal's own message. That one is written for a
     * log, and a sentence a person reads is this class's job, the same as every other on this
     * screen.
     *
     * @param previousLibraryRoot {@link Path} the root the library would move away from
     * @return {@link String} the question, in the same terms as the buttons answering it
     */
    private static String wordLibraryRootMove(final Path previousLibraryRoot) {
        return "Your library is at " + previousLibraryRoot + ", and Sluice keeps a record of what is "
                + "already in it. Moving the library leaves that record describing the old folder. "
                + "Copy the old library across to keep it, or start the record fresh and let Sluice "
                + "learn the new folder as you go.";
    }

    private static String wordOverride(final SettingOverride override) {
        return switch (override) {
            case final ByEnvironmentVariable env -> "The " + env.variableName() + " environment variable "
                    + "outranks what is saved here. What you save still applies for now, and the variable "
                    + "wins again the next time you open Sluice.";
            case final ByAnotherSource other -> other.source() + " outranks what is saved here. What you "
                    + "save still applies for now, and that source wins again the next time you open Sluice.";
        };
    }

    private static String wordMoveOutcome(final LibraryRootMoveOutcome outcome) {
        return switch (outcome) {
            case final LibraryRootMoveOutcome.CopiedAndMoved copied -> copiedAndMoved(copied);
            case final LibraryRootMoveOutcome.CopyCancelled cancelled -> "The copy was cancelled after "
                    + cancelled.filesCopied() + " of " + cancelled.filesFound()
                    + " file(s). The library is still at its old folder.";
            case final LibraryRootMoveOutcome.MovedWithAFreshIndex fresh -> fresh.previousIndexFiledAt() == null
                    ? "The library root moved. Sluice had no record yet of what was already in the library, "
                            + "so there was nothing to set aside."
                    : "The library root moved. Sluice's record of what was already in the library described "
                            + "the old folder, so it was kept at " + fresh.previousIndexFiledAt()
                            + " and a new one starts empty. Photos you import again will not be spotted as "
                            + "duplicates until it fills up.";
        };
    }

    /**
     * The copy-and-keep ending in the user's terms, including files it deliberately did not copy.
     *
     * <p>A copy into a folder already holding some of the library skips what is already there, so
     * the copied count can honestly be low or zero. Left unexplained that reads as a copy that did
     * not happen. The skip is said outright instead.
     *
     * @param copied {@link LibraryRootMoveOutcome.CopiedAndMoved} what the move reports
     * @return {@link String} what to show
     */
    private static String copiedAndMoved(final LibraryRootMoveOutcome.CopiedAndMoved copied) {
        final int alreadyThere = copied.filesFound() - copied.filesCopied();
        final String skipped = alreadyThere <= 0 ? ""
                : " " + alreadyThere + " file(s) were already in the new folder and were left as they were.";
        return "Copied " + copied.filesCopied() + " file(s) into the new library." + skipped
                + " The old folder is untouched; remove it by hand once you have checked it.";
    }

    private static SettingsView.FolderField folderField(final @Nullable String value, final String suggestion,
            final @Nullable String violation) {
        return new SettingsView.FolderField(value == null ? "" : value, suggestion, violation);
    }

    /**
     * The configured provider id, or what to fall back to when it names none this install has.
     *
     * <p>A screen has to select something in its dropdown. Falling back here, rather than in the
     * view, keeps that choice a decision this class makes. The alternative is a search-and-guess the
     * view performs on its own.
     *
     * <p>The fallback prefers a provider that needs no credential. Whoever lands here has a setting
     * naming something this build cannot cull with, and the next thing they do is likely to be
     * Save. Preselecting one that spends their money is the outcome worth ruling out. Left to
     * ordering it would be decided by a label's first letter.
     *
     * @param configured {@link String} the provider id the settings in force name
     * @return {@link String} that id, or what to preselect instead when it matches none
     */
    private String resolvedProviderId(final String configured) {
        if (this.providers.byId(configured).isPresent()) {
            return configured;
        }
        return this.providers.providers().stream()
                .filter(provider -> provider.credential() == null)
                .findFirst()
                .orElseGet(() -> this.providers.providers().getFirst())
                .id();
    }

    /**
     * What to say when the configured provider names none this install has, or null when it names
     * one.
     *
     * <p>Without this the substitution is silent. Someone whose configuration says one thing opens
     * this screen, reads another, and is given nothing connecting the two. Their next Save
     * overwrites the line they wrote. The only other place that value surfaces is a cull refusing
     * with a message written for a log.
     *
     * @param configured {@link String} the provider id the settings in force name
     * @return {@link String} what to tell the user, or null when the id is one this install has
     */
    private @Nullable String unrecognisedProviderNote(final String configured) {
        if (this.providers.byId(configured).isPresent()) {
            return null;
        }
        // The substitute is named by the dropdown this note sits under, so naming it again only
        // risks the two disagreeing.
        return "Your configuration asks for a vision provider called '" + configured + "', which this "
                + "version of Sluice does not have. The one selected above is being used instead. "
                + "Saving replaces the value you configured.";
    }

    /**
     * Every provider this install can cull with, as a dropdown's own choices.
     *
     * @return a {@link List} of {@link SettingsView.ProviderChoice} one per registered provider
     */
    private List<SettingsView.ProviderChoice> providerChoices() {
        return this.providers.providers().stream()
                .map(provider -> new SettingsView.ProviderChoice(
                        provider.id(), provider.label(), fieldsOf(provider)))
                .toList();
    }

    /**
     * Which settings a provider uses, translated into the controls a screen draws.
     *
     * <p>The provider answers, and this only translates that answer. So a new provider needs
     * nothing here, while a new setting does, which is right: a setting nothing draws is a setting
     * nobody can change.
     *
     * @param provider {@link VisionProviderDescriptor} the provider being described
     * @return {@link SettingsView.ProviderFields} which settings apply to it
     */
    private static SettingsView.ProviderFields fieldsOf(final VisionProviderDescriptor provider) {
        final Set<ProviderSetting> used = provider.settingsUsed();
        return new SettingsView.ProviderFields(used.contains(ProviderSetting.MODEL),
                used.contains(ProviderSetting.ENDPOINT),
                used.contains(ProviderSetting.RETRIES), used.contains(ProviderSetting.WATCH_MODE),
                used.contains(ProviderSetting.CREDENTIAL));
    }

    /**
     * Every current violation, one entry per role it names. An {@link Overlap} names two roles, so it
     * contributes an entry for each.
     *
     * @param paths {@link PathSettings} the folder roots to check
     * @return a {@link Map} of {@link PathRole} to {@link String} the violation message for each role
     *         that has one. A role that is usable or unset has no entry
     */
    private Map<PathRole, String> violationsByRole(final PathSettings paths) {
        final var byRole = new EnumMap<PathRole, String>(PathRole.class);
        for (final PathViolation violation : this.pathValidation.violations(paths)) {
            if (violation instanceof Overlap(final PathRole first, final PathRole second)) {
                byRole.put(first, wordOverlap(second));
                byRole.put(second, wordOverlap(first));
            } else if (!(violation instanceof NotConfigured)) {
                byRole.put(roleOf(violation), wordViolation(violation));
            }
        }
        return byRole;
    }

    private static PathRole roleOf(final PathViolation violation) {
        return switch (violation) {
            case final NotConfigured v -> v.role();
            case final NotAPath v -> v.role();
            case final NotADirectory v -> v.role();
            case final Unreadable v -> v.role();
            case final Overlap v -> v.first();
        };
    }

    private static String wordViolation(final PathViolation violation) {
        return switch (violation) {
            case final NotConfigured _ -> "";
            // One message for every way a path can be malformed. What the filesystem refused is not
            // reported back, so naming a cause would be a guess. A typo is the likely one, and
            // Browse is how somebody stops making them.
            case final NotAPath _ -> "This isn't a folder path Sluice can use. Most likely a typo, "
                    + "so try Browse instead.";
            case final NotADirectory _ -> "No folder could be found here.";
            // The folder exists and opening it still failed, so the two causes worth naming are the
            // ones a user can act on. Named as checks rather than as a diagnosis: what the
            // filesystem refused does not reach here, and permissions is one cause of several.
            case final Unreadable _ -> "This folder is there, but Sluice could not open it. Check that "
                    + "you have permission to open it, and that the drive or network share it sits on "
                    + "is connected.";
            case final Overlap v -> wordOverlap(v.second());
        };
    }

    /**
     * What to put at the foot of the page for a refusal no field is carrying.
     *
     * <p>One refusal is written for a user and is shown as it is: a job in progress, which says to
     * finish the run first.
     *
     * <p>Everything else carries a message written for a log, or none at all. An unforeseen failure
     * says so in this app's voice instead, and offers the one thing a user can do about it. A
     * stack's own words under the Save button offer nothing.
     *
     * @param refusal {@link RuntimeException} what the save seam threw
     * @return {@link String} what to show
     */
    private static String wordedForAUser(final RuntimeException refusal) {
        if (refusal instanceof JobInProgressException) {
            return refusal.getMessage();
        }
        return "These settings were not saved, and Sluice cannot say why. Nothing you had configured "
                + "has changed. Report this as a bug in Sluice, quoting this: " + refusal;
    }

    private static String wordOverlap(final PathRole other) {
        return "This overlaps with the " + ROLE_LABELS.get(other) + " folder.";
    }

    private static void createIfItIsOurOwnSuggestion(final String value, final String suggestion) {
        if (!value.equals(suggestion)) {
            return;
        }
        final Path path = Path.of(value);
        if (Files.isDirectory(path)) {
            return;
        }
        try {
            Files.createDirectories(path);
        } catch (final IOException e) {
            // Left for save-time validation to report as a folder it could not confirm. Creating our
            // own suggestion is a courtesy; failing to create it is not this method's failure to report.
        }
    }

    private static @Nullable String blankToNull(final String value) {
        return value.isBlank() ? null : value;
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    /**
     * What happened when the library-root move a settings save asked to resolve was carried out.
     *
     * @param succeeded boolean whether the move landed
     * @param message {@link String} what to tell the user
     */
    public record MoveOutcome(boolean succeeded, @Nullable String message) {
    }

    /**
     * What happened when a save was attempted.
     */
    public sealed interface SaveOutcome {

        /** The save landed. */
        record Saved() implements SaveOutcome {
        }

        /**
         * The library root changed and the save needs a resolution first.
         *
         * @param newLibraryRoot {@link Path} the folder the library would move to
         * @param message {@link String} what the app is asking the user to decide
         */
        record NeedsLibraryRootResolution(Path newLibraryRoot, @Nullable String message) implements SaveOutcome {
        }

        /**
         * The save was refused.
         *
         * <p>The summary says what happened. The field messages mark what is at fault. A user
         * scrolling to a field then finds the reason under it, rather than only at the foot of the
         * page. A refusal with no field to blame carries nulls throughout.
         *
         * @param message {@link String} why
         * @param workingRoot what is wrong with the working root, or null
         * @param libraryRoot what is wrong with the library root, or null
         * @param inbox what is wrong with the inbox, or null
         * @param model what is wrong with the model, or null
         */
        record Refused(@Nullable String message, @Nullable String workingRoot, @Nullable String libraryRoot,
                       @Nullable String inbox, @Nullable String model) implements SaveOutcome {

            /**
             * A refusal that belongs to no field in particular.
             *
             * @param message {@link String} why
             */
            Refused(final @Nullable String message) {
                this(message, null, null, null, null);
            }

            /**
             * A refusal the model field is at fault for.
             *
             * @param message {@link String} what the model field is missing
             * @return {@link Refused} that refusal, marking the model and summarising at the foot
             */
            static Refused markingTheModel(final String message) {
                return new Refused(FIELDS_ARE_MARKED, null, null, null, message);
            }
        }
    }
}
