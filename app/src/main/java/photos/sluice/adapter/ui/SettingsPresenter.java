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
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.PathSettings;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.Settings;
import photos.sluice.application.port.out.SettingOverride;
import photos.sluice.application.port.out.SettingOverride.ByAnotherSource;
import photos.sluice.application.port.out.SettingOverride.ByEnvironmentVariable;
import photos.sluice.application.port.out.ThemeChoice;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.domain.cull.MontageConfig;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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

    // These four numbers are plausible rather than measured. Nobody has derived them. Whoever next
    // holds real cost data for a sheet replaces them and says why.
    private static final SettingsView.NumberRange TILE_SIZE_RANGE = new SettingsView.NumberRange(16, 1024, 16);
    private static final SettingsView.NumberRange TILES_PER_ROW_RANGE = new SettingsView.NumberRange(1, 12, 1);

    // Says which way to look. The line sits in the bar pinned above the page, so every field it is
    // about is below it.
    private static final String FIELDS_ARE_MARKED =
            "These settings were not saved. Each field that needs fixing is marked below.";

    private static final String MOVING = "Moving the library...";

    private final SettingsUseCase settingsUseCase;
    private final LibraryRootUseCase libraryRootUseCase;
    private final PathValidationUseCase pathValidation;
    private final VisionProviderCatalog providers;
    private final VisionProviderPresenter visionProvider;
    private final FxProgressPort progress;

    /**
     * Creates the presenter over the use cases the screen reads and writes through.
     *
     * @param settingsUseCase {@link SettingsUseCase} reads and saves the app's settings
     * @param libraryRootUseCase {@link LibraryRootUseCase} moves the library root when it changes
     * @param pathValidation {@link PathValidationUseCase} checks the folder roots for the per-field
     *         violation each row shows
     * @param providers {@link VisionProviderCatalog} which providers this install has, and what
     *         each of them uses
     * @param visionProvider {@link VisionProviderPresenter} the credential, model catalogue and
     *         connection check the VISION PROVIDER card needs beyond this document
     * @param progress {@link FxProgressPort} what a library move reports itself to
     */
    public SettingsPresenter(final SettingsUseCase settingsUseCase, final LibraryRootUseCase libraryRootUseCase,
                             final PathValidationUseCase pathValidation, final VisionProviderCatalog providers,
                             final VisionProviderPresenter visionProvider, final FxProgressPort progress) {
        this.settingsUseCase = settingsUseCase;
        this.libraryRootUseCase = libraryRootUseCase;
        this.pathValidation = pathValidation;
        this.providers = providers;
        this.visionProvider = visionProvider;
        this.progress = progress;
    }

    /**
     * Reports how far a library move has got, for as long as the returned handle is open.
     *
     * <p>A move copies a whole library, which is long enough that a static line reads as a screen
     * that has stopped. The copy already reports its phases to the same port a run's progress area
     * reads, and nothing on this screen was listening.
     *
     * <p>The line names the last phase reported rather than all of them, because a dialog has one
     * line rather than a page of bars. A phase that has not said how much work it has keeps the
     * plain line. A count with nothing to count against says less than no count at all.
     *
     * @param line a {@link Consumer} of {@link String} takes each line, on the application thread
     * @return {@link AutoCloseable} closing it stops the reporting
     */
    public AutoCloseable reportMoving(final Consumer<String> line) {
        line.accept(MOVING);
        return this.progress.alsoRedraw(() -> line.accept(movingLine(this.progress.phases())));
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
        final VisionProviderPresenter.ModelPickerResult modelPicker = this.visionProvider.modelPickerFor(shownProvider);
        return new SettingsView(
                folderField(paths.repoRoot(), workingRootSuggestion(), violations.get(PathRole.WORKING_ROOT)),
                folderField(paths.libraryRoot(), librarySuggestion(), violations.get(PathRole.LIBRARY_ROOT)),
                folderField(paths.inbox(), inboxSuggestion(paths.repoRoot()), violations.get(PathRole.INBOX)),
                shownProvider, this.visionProvider.providerChoices(),
                this.overrideNote("sluice.cull.provider"),
                this.unrecognisedProviderNote(settings.provider()),
                modelPicker.picker(), this.overrideNote(providerProperty(shownProvider, "model")),
                modelPicker.unrecognisedNote(),
                providerSettings.endpoint(), this.overrideNote(providerProperty(shownProvider, "endpoint")),
                this.visionProvider.secretRow(shownProvider),
                montage.tileSize(), TILE_SIZE_RANGE, this.overrideNote("sluice.montage.tile-size"),
                montage.tilesPerRow(), TILES_PER_ROW_RANGE, this.overrideNote("sluice.montage.tiles-per-row"),
                settings.theme().name(), THEMES, this.overrideNote("sluice.ui.theme"),
                CullProviderSettings.maxEndpoint(), PathSettings.maxRoot(), SecretStore.maxSecret());
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
     * Whether leaving now would lose something typed or picked on this screen.
     *
     * <p>Compared against what {@link #view()} draws from disk, so a field the reader edited and
     * put back is not a change. Blank stands for the saved value on a field the screen leaves
     * empty, since a provider with no model or endpoint draws nothing there.
     *
     * <p>Theme is not among these. {@link #chooseTheme} writes it the moment a radio is picked, so
     * there is never a theme change waiting to be lost.
     *
     * @param onScreen {@link SettingsEdits} every value the save would send, as it stands now
     * @return boolean true where leaving would lose something
     */
    public boolean hasUnsavedEdits(final SettingsEdits onScreen) {
        return !this.savedEdits().equals(onScreen);
    }

    /**
     * Every Settings value that waits for Save, in the terms the screen holds them.
     *
     * @param workingRoot {@link String} the working-root path as typed
     * @param libraryRoot {@link String} the library-root path as typed
     * @param inbox {@link String} the inbox path as typed
     * @param provider {@link String} the id of the provider chosen
     * @param model {@link String} the model id selected, or blank where the provider takes none
     * @param endpoint {@link String} the endpoint as typed, or blank where the provider takes none
     * @param tileSize int the sheet's tile size
     * @param tilesPerRow int how many tiles a sheet row holds
     */
    public record SettingsEdits(String workingRoot, String libraryRoot, String inbox, String provider,
                                String model, String endpoint,
                                int tileSize, int tilesPerRow) {
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
                current.providerSettingsById(), current.categories(),
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
     * @param tileSize int the montage tile size
     * @param tilesPerRow int the montage tiles per row
     * @param themeId {@link String} id of the look the user picked, from a {@link SettingsView.ThemeOption}
     * @return {@link SaveOutcome} what happened
     */
    public SaveOutcome save(final String workingRoot, final String libraryRoot, final String inbox,
                            final String provider, final String model, final String endpoint,
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
        final var paths = new PathSettings(blankToNull(workingRoot), blankToNull(libraryRoot),
                blankToNull(inbox));
        final Settings settings;
        try {
            // Inside the guard with everything else that can refuse. An id this class never put in
            // the list it handed the screen is a refusal to report. Not an exception to escape into
            // a button handler.
            final ThemeChoice theme = ThemeChoice.valueOf(themeId);
            // This screen never asks about the retry count. A save carries forward whatever is
            // already configured for this provider rather than losing it, the same as categories.
            final Integer maxRetries = this.settingsUseCase.settings().providerSettings(provider).maxRetries();
            settings = new Settings(paths, provider,
                    this.providerSettingsWith(provider,
                            new CullProviderSettings(blankToNull(model), blankToNull(endpoint), maxRetries)),
                    this.settingsUseCase.settings().categories(),
                    new MontageConfig(tileSize, tilesPerRow), theme);
        } catch (final RuntimeException e) {
            return this.refusalMarkingItsFields(e, paths);
        }
        try {
            this.settingsUseCase.save(settings);
            // After the save rather than before it, so a refused save cannot leave the app wearing a
            // look its own settings do not name.
            ThemeSelection.set(settings.theme());
            return new SaveOutcome.Saved();
        } catch (final LibraryRootMoveNeedsAResolutionException e) {
            // Emptying a configured library root raises the same refusal, since a clear followed by
            // a set is the move this exists to catch, done in two steps. There is no resolution to
            // state for it though: both answers are about what to do with a library arriving
            // somewhere, and a cleared field names nowhere. So it is refused on the field instead.
            if (paths.libraryRoot() == null) {
                return SaveOutcome.Refused.markingTheLibraryRoot("Your library folder cannot be left "
                        + "blank once it is set. To move your library, point this at the folder it "
                        + "should move to instead.");
            }
            // A move checks the roots already in force, so one of those being unset refuses it
            // whatever the reader answers. Asking the question first would spend a dialog to reach a
            // refusal that was certain before it opened.
            if (!this.pathValidation.violationsInForce().isEmpty()) {
                return this.storeEverythingExceptTheMove(settings, e.previousLibraryRoot());
            }
            // Nothing reached disk, so the whole document travels with the question. The resolution
            // answers for the library root alone, and everything else this save carried still has
            // to be stored once it is answered.
            return new SaveOutcome.NeedsLibraryRootResolution(Path.of(libraryRoot), settings,
                    wordLibraryRootMove(e.previousLibraryRoot()));
        } catch (final RuntimeException e) {
            return this.refusalMarkingItsFields(e, paths);
        }
    }

    /**
     * Stores a save that also asked to move the library, keeping everything except the move.
     *
     * <p>The move cannot run: it checks the roots in force, and one of those is unset. Refusing the
     * whole save would leave the reader unable to fix that, since filling the empty folder in is
     * itself a save, and it carries the same library root that raised this. The way out would be to
     * put the library field back by hand, which nothing tells them to do.
     *
     * <p>So the folders land and the library stays where it is. By the time the reader reads the
     * message, the roots that blocked the move are set, and asking again works.
     *
     * @param settings {@link Settings} everything this save was carrying
     * @param stayingAt {@link Path} where the library is now, which it keeps
     * @return {@link SaveOutcome} what happened, worded for the screen
     */
    private SaveOutcome storeEverythingExceptTheMove(final Settings settings, final Path stayingAt) {
        final PathSettings asked = settings.paths();
        final var keeping = new PathSettings(asked.repoRoot(), stayingAt.toString(), asked.inbox());
        try {
            this.settingsUseCase.save(new Settings(keeping, settings.provider(),
                    settings.providerSettingsById(), settings.categories(),
                    settings.montage(), settings.theme()));
            ThemeSelection.set(settings.theme());
        } catch (final RuntimeException e) {
            return this.refusalMarkingItsFields(e, keeping);
        }
        // Worded by what this save actually left behind. A save that filled the empty folders in has
        // cleared the way and only has to ask again. One made while they are still empty has not,
        // and telling that reader their folders were saved names folders they never gave.
        // The one refusal that says its own piece rather than sending the reader to the marked
        // field. Every other one is wholly a refusal, so pointing at the fault is the most useful
        // thing the summary can do. This one is half a save, and "the fields with a problem are
        // marked" would report the half that landed as a failure.
        if (this.pathValidation.violations(keeping).isEmpty()) {
            return new SaveOutcome.Refused("Your other folders were saved. Your library folder "
                    + "stayed where it is, because it could not move while another folder was empty. "
                    + "Now that the others are set, save again to move it.",
                    null, "This folder was not saved.", null, null, true);
        }
        return SaveOutcome.Refused.markingTheLibraryRoot("Your library folder cannot move while "
                + "another folder is empty. Fill in the folders that are still empty and save those "
                + "first. You can move your library afterwards.");
    }

    /**
     * Saves the three folder roots and the chosen provider, leaving every other setting as it is.
     *
     * <p>What the first-run card collects. It reaches the same save as the Settings screen. So a
     * refusal is worded once, and a folder root is checked the same way whichever card the user is
     * looking at.
     *
     * @param workingRoot {@link String} the working-root field's text
     * @param libraryRoot {@link String} the library-root field's text
     * @param inbox {@link String} the inbox field's text
     * @param providerId {@link String} the selected provider id
     * @return {@link SaveOutcome} what happened
     */
    public SaveOutcome saveFolderRootsAndProvider(final String workingRoot, final String libraryRoot,
                                                  final String inbox, final String providerId) {
        final Settings settings = this.settingsUseCase.settings();
        final CullProviderSettings provider = settings.providerSettings(providerId);
        final String endpoint = provider.endpoint() == null ? "" : provider.endpoint();
        return this.save(workingRoot, libraryRoot, inbox, providerId, this.modelToCarryFor(providerId), endpoint,
                settings.montage().tileSize(),
                settings.montage().tilesPerRow(), settings.theme().name());
    }

    /**
     * Carries out a library-root move already resolved by the user, blocking until it finishes.
     * Called off the FX thread: a move can copy a library for as long as the library takes.
     *
     * <p>The move itself writes the library root and nothing else, from the settings in force at
     * the moment it lands. So the refused save's own document is stored straight after, which is
     * what keeps every other value that save was carrying. By then the library root in force is
     * already the new one, so that second save moves nothing and asks nothing.
     *
     * @param refused {@link SaveOutcome.NeedsLibraryRootResolution} the save this answers, carrying
     *     both the folder to move to and everything else it was going to store
     * @param resolution {@link LibraryRootResolution} what to do about the hash index
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRoot(final SaveOutcome.NeedsLibraryRootResolution refused,
                                       final LibraryRootResolution resolution) {
        final LibraryRootMoveOutcome outcome;
        try {
            outcome = this.libraryRootUseCase.moveLibraryRoot(refused.newLibraryRoot(), resolution).join();
        } catch (final CompletionException e) {
            final Throwable cause = e.getCause();
            return failedMove(cause == null ? e : cause);
        } catch (final RuntimeException e) {
            return failedMove(e);
        }
        if (!movedTheLibrary(outcome)) {
            // A cancelled copy left the library where it was, so the rest of this save would be
            // stored against a library root that never changed. Said as a state of its own, so the
            // screen keeps every field as the user typed it and they can press Save again.
            return new MoveOutcome.NothingChanged(wordMoveOutcome(outcome));
        }
        try {
            this.settingsUseCase.save(refused.pending());
        } catch (final RuntimeException e) {
            // The move is already done, so this is not a failed move. Said as its own thing, since
            // what the user has to do about it is press Save again rather than answer the question
            // they just answered.
            return new MoveOutcome.Failed("Your library moved. The rest of what you were saving did not: "
                    + wordedForAUser(e));
        }
        ThemeSelection.set(refused.pending().theme());
        return new MoveOutcome.Moved(wordMoveOutcome(outcome));
    }

    /**
     * Carries out a library-root move, copying the old library into the new one and keeping the
     * hash index. Named rather than taking a {@link LibraryRootResolution}, so the view that offers
     * this choice never has to name that type itself.
     *
     * @param refused {@link SaveOutcome.NeedsLibraryRootResolution} the save this answers
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRootCopyingTheIndex(final SaveOutcome.NeedsLibraryRootResolution refused) {
        return this.moveLibraryRoot(refused, LibraryRootResolution.COPY_AND_KEEP_INDEX);
    }

    /**
     * Carries out a library-root move, filing the old hash index aside and starting a fresh one.
     *
     * @param refused {@link SaveOutcome.NeedsLibraryRootResolution} the save this answers
     * @return {@link MoveOutcome} what happened, worded for the screen
     */
    public MoveOutcome moveLibraryRootWithAFreshIndex(final SaveOutcome.NeedsLibraryRootResolution refused) {
        return this.moveLibraryRoot(refused, LibraryRootResolution.START_A_FRESH_INDEX);
    }

    /**
     * Checks the configured provider once, as the app opens.
     *
     * <p>Without it a provider that already holds a key is described by the provider's own guess at
     * what it offers, which is wrong rather than merely stale.
     *
     * <p>Blocking, so a caller runs this off the FX thread.
     *
     * <p>Whichever provider Settings would show is the one checked. So a configured id naming no
     * provider this install has is answered for by the substitute that screen selects instead. That
     * substitution is resolved here and passed down, rather than resolved a second time inside
     * {@link VisionProviderPresenter}.
     */
    public void refreshModelsAtStartup() {
        this.visionProvider.refreshModelsAtStartup(this.resolvedProviderId(this.settingsUseCase.settings().provider()));
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
     * <p>Two states produce a blank model, and each gets its own words. A picker offering anything
     * always has something selected, so a blank means the picker had nothing in it. Either a check
     * of this provider failed, which leaves a Retry beside the reason, or one is still running,
     * which leaves neither. Naming a Retry that is not on screen would send that second reader
     * looking for a control this app is not drawing.
     *
     * @param providerId {@link String} the provider being saved
     * @param model {@link String} the selected model id, blank when the picker has nothing to offer
     * @return {@link String} what to tell the user, or null when nothing is missing
     */
    private @Nullable String whatThisProviderNeeds(final String providerId, final String model) {
        final boolean modelIsRequired = this.providers.byId(providerId)
                .map(VisionProviderDescriptor::required)
                .orElseGet(Set::of)
                .contains(ProviderSetting.MODEL);
        if (!modelIsRequired || !model.isBlank()) {
            return null;
        }
        if (this.visionProvider.checkInFlight(providerId)) {
            return "Sluice is still asking this provider what your account can run. Try saving again "
                    + "in a moment.";
        }
        return "No model is chosen, and this provider needs one. Use Retry above, or choose a "
                + "provider that does not call a model.";
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
            case final LibraryRootMoveOutcome.CopyCancelled cancelled -> "You stopped the copy after "
                    + cancelled.filesCopied() + " of " + cancelled.filesFound()
                    + " files. The library is still at its old folder, but the files already copied now "
                    + "also exist in your new location. Resuming the library move continues copying the "
                    + "files instead of from the start. Sluice will never remove a library folder. If you "
                    + "want to do this you can go to " + cancelled.copiedInto() + " and remove it by hand.";
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
                + " Every one of them is still in " + copied.copiedFrom()
                + ", so remove that folder by hand once you have checked the new one.";
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
        return "This overlaps with the " + PathRoleLabels.of(other) + " folder.";
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
     * <p>Three states rather than a boolean, because a screen does three different things with
     * them. Only {@link Moved} may redraw the page: it is the one state where what is on disk has
     * changed, so what a redraw reads back is newer than what is on screen. Redrawing on either of
     * the others would throw away every edit the user made in the same press, having just told them
     * nothing happened.
     *
     * <p>Every state carries words. A screen renders whatever this holds, so an outcome with
     * nothing to say clears the line it lands in and reads as a move that quietly worked.
     */
    public sealed interface MoveOutcome {

        /**
         * What to tell the user.
         *
         * @return {@link String} the message
         */
        String message();

        /**
         * The library is somewhere new, and the rest of the save landed with it.
         *
         * @param message {@link String} what the move did with the files and the record
         */
        record Moved(String message) implements MoveOutcome {
        }

        /**
         * Nothing on disk changed. A copy the user cancelled is the case: the library stayed where
         * it was, so the save it was carrying is still unmade and every field is still as typed.
         *
         * @param message {@link String} what stopped, and where that leaves the library
         */
        record NothingChanged(String message) implements MoveOutcome {
        }

        /**
         * The move, or the save that follows it, did not go through.
         *
         * @param message {@link String} what went wrong
         */
        record Failed(String message) implements MoveOutcome {
        }
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
         * <p>Carries the whole document the refused save was trying to store. A move writes the
         * library root and nothing else. Without this, every other value that save carried is lost
         * the moment the user answers the question. On the first-run card those values are the
         * other two folders they had just typed.
         *
         * @param newLibraryRoot {@link Path} the folder the library would move to
         * @param pending {@link Settings} everything the refused save was going to store
         * @param message {@link String} what the app is asking the user to decide, always worded:
         *     a dialog with no question in it is one nobody can answer
         */
        record NeedsLibraryRootResolution(Path newLibraryRoot, Settings pending, String message)
                implements SaveOutcome {
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
         * @param warning boolean whether this is a caution rather than a plain refusal, which is
         *     the half-saved case: reporting it in the colour of a failure would have the reader
         *     typing again what is already on disk
         */
        record Refused(@Nullable String message, @Nullable String workingRoot, @Nullable String libraryRoot,
                       @Nullable String inbox, @Nullable String model, boolean warning)
                implements SaveOutcome {

            /**
             * A refusal that belongs to no field in particular.
             *
             * @param message {@link String} why
             */
            Refused(final @Nullable String message) {
                this(message, null, null, null, null, false);
            }

            /**
             * A refusal the library-root field is at fault for.
             *
             * @param message {@link String} what is wrong with the library root
             * @return {@link Refused} that refusal, marking the field and summarising above
             */
            static Refused markingTheLibraryRoot(final String message) {
                return new Refused(FIELDS_ARE_MARKED, null, message, null, null, false);
            }

            /**
             * A refusal the model field is at fault for.
             *
             * @param message {@link String} what the model field is missing
             * @return {@link Refused} that refusal, marking the model and summarising at the foot
             */
            static Refused markingTheModel(final String message) {
                return new Refused(FIELDS_ARE_MARKED, null, null, null, message, false);
            }

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
        return new SaveOutcome.Refused(summary, byRole.get(PathRole.WORKING_ROOT),
                byRole.get(PathRole.LIBRARY_ROOT), byRole.get(PathRole.INBOX), null, false);
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
     * Whether an outcome that did not throw actually left the library somewhere new.
     *
     * <p>A switch over every case rather than a test for the one that did not. A fourth outcome
     * fails to compile here until somebody says which side of the line it falls on.
     *
     * @param outcome {@link LibraryRootMoveOutcome} what the move reported
     * @return boolean true when the library root in force is now the new one
     */
    private static boolean movedTheLibrary(final LibraryRootMoveOutcome outcome) {
        return switch (outcome) {
            case LibraryRootMoveOutcome.CopiedAndMoved _, LibraryRootMoveOutcome.MovedWithAFreshIndex _ -> true;
            case LibraryRootMoveOutcome.CopyCancelled _ -> false;
        };
    }

    /**
     * A move that did not happen, always with something to show for it.
     *
     * <p>An exception carries no message of its own often enough to matter, and the screen renders
     * whatever this returns. Left as it came, a failure with nothing to say clears the status line
     * and reads as a move that quietly worked.
     *
     * @param failure {@link Throwable} what stopped the move
     * @return {@link MoveOutcome} the failure, worded for the screen
     */
    private static MoveOutcome failedMove(final Throwable failure) {
        final String said = failure.getMessage();
        return new MoveOutcome.Failed(said == null || said.isBlank()
                ? "The library did not move, and Sluice cannot say why. Your photos are still where they "
                        + "were. Report this as a bug in Sluice, quoting this: " + failure
                : said);
    }

    /**
     * The model to save for a provider on a card that offers no model picker.
     *
     * <p>Whatever is configured, and otherwise the provider's own recommendation. A provider that
     * needs a model refuses a save without one, and a card that never asked for one has no way to
     * say which.
     *
     * <p>The provider's own list rather than the account's, whatever a live check has since
     * answered. That is what the Settings picker offers somebody who has connected nothing yet,
     * which is who this card is for. A first run on a machine whose key sits in an environment
     * variable can therefore store a recommendation the account's real list would not have led
     * with. The Settings picker is where that is corrected, and it says which list it is showing.
     *
     * @param providerId {@link String} the provider being saved
     * @return {@link String} the model id to save, empty for a provider that runs no model
     */
    private String modelToCarryFor(final String providerId) {
        final CullProviderSettings saved = this.settingsUseCase.settings().providerSettings(providerId);
        if (saved.model() != null && !saved.model().isBlank()) {
            return saved.model();
        }
        return this.providers.byId(providerId)
                .map(VisionProviderDescriptor::models)
                .map(SettingsPresenter::defaultOf)
                .orElse("");
    }

    /**
     * The model a catalog starts a fresh install on.
     *
     * @param catalog {@link ModelCatalog} the provider's own list
     * @return {@link String} the recommended model, or the first offered where none is recommended
     */
    private static String defaultOf(final ModelCatalog catalog) {
        return catalog.recommended() == null ? catalog.options().getFirst().id() : catalog.recommended();
    }

    /**
     * What the move says it is doing, from what it has reported.
     *
     * @param phases a {@link List} of {@link ProgressPhase} what the move has reported so far
     * @return {@link String} the line to show
     */
    private static String movingLine(final List<ProgressPhase> phases) {
        if (phases.isEmpty()) {
            return MOVING;
        }
        final ProgressPhase now = phases.getLast();
        return now.total() > 0
                ? now.label() + "... " + counted(now.current()) + " of " + counted(now.total())
                : now.label() + "...";
    }

    /**
     * A number with thousands separated, the way somebody reading it would write it.
     *
     * @param value int the number
     * @return {@link String} the number written out
     */
    private static String counted(final int value) {
        return String.format(Locale.UK, "%,d", value);
    }

    /**
     * What {@link #hasUnsavedEdits} compares against: the same values, as they are on disk.
     *
     * @return {@link SettingsEdits} the saved side
     */
    private SettingsEdits savedEdits() {
        final SettingsView view = this.view();
        final String model = view.model() instanceof final SettingsView.ModelPicker.Options options
                ? options.selected() : "";
        return new SettingsEdits(view.workingRoot().value(), view.libraryRoot().value(),
                view.inbox().value(), view.provider(), model,
                view.endpoint() == null ? "" : view.endpoint(),
                view.tileSize(), view.tilesPerRow());
    }
}
