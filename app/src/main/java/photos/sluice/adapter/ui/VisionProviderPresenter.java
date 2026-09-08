package photos.sluice.adapter.ui;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import photos.sluice.application.port.in.SettingsUseCase;
import photos.sluice.application.port.in.VisionProviderCatalog;
import photos.sluice.application.port.out.CullProviderSettings;
import photos.sluice.application.port.out.ModelCatalog;
import photos.sluice.application.port.out.ProviderCheck;
import photos.sluice.application.port.out.ProviderSetting;
import photos.sluice.application.port.out.VisionProviderDescriptor;
import photos.sluice.secrets.SecretHolding;
import photos.sluice.secrets.SecretHolding.Holding;
import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStatus;
import photos.sluice.secrets.SecretStatus.InEnvironment;
import photos.sluice.secrets.SecretStatus.InKeyring;
import photos.sluice.secrets.SecretStatus.StoredLocation;
import photos.sluice.secrets.SecretStore;
import photos.sluice.secrets.SecretStoreException;
import photos.sluice.secrets.StaleSecretNotClearedException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Everything the VISION PROVIDER card does that never touches the settings document: a provider's
 * credential, its model catalogue, and a live connection check.
 *
 * <p>{@link SettingsPresenter} owns the document one Save button writes. This class is the side
 * channel to the keyring and the network that card also needs, and neither reads nor writes that
 * document itself.
 */
@Component
@Profile("!cli")
public class VisionProviderPresenter {

    private static final String NO_CREDENTIAL_TO_KEEP = "This provider takes no key.";

    // How long the start-up check is waited on. Nobody pressed anything to start it, but somebody
    // can be watching it: a Settings screen opened at launch shows the picker as loading until this
    // runs out. So this is the ceiling on how long that screen is unusable, not a guess at the
    // service. Five seconds is several times what listing models takes on a working connection, and
    // a connection slower than that cannot carry a cull either.
    private static final Duration BOOT_CHECK_BUDGET = Duration.ofSeconds(5);

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
    private static final String STORE_UNREADABLE = "The key saved for this provider could not be read. "
            + "The credential store on this computer refused to answer. Nothing else you have configured is "
            + "affected. Paste your key in again and save: that alone often fixes it, because saving also "
            + "clears any copy held somewhere Sluice ranks higher. If saving does not take, use Remove, "
            + "which clears the key from every place Sluice can reach, then save again. If it keeps "
            + "refusing, report this as a bug, quoting this: ";

    private final SecretStore secretStore;
    private final VisionProviderCatalog providers;
    private final SettingsUseCase settingsUseCase;

    // What the last live check said, per provider. A picker cannot draw a list nobody asked for, so
    // this is what tells modelPickerFor whether to show a provider's static floor or its account's
    // real one. Written by refreshModels, off the FX thread; read by modelPickerFor, which may run
    // concurrently with a refresh in flight. A provider with no entry here has never been checked
    // this session.
    private final Map<String, ProviderCheck> lastCheck = new ConcurrentHashMap<>();

    // Providers whose start-up check has been started and not yet given up on. A picker for one of
    // these has nothing honest to draw: the answer deciding what it offers is still on its way.
    // Written off the FX thread and read by modelPickerFor, the same as lastCheck above.
    //
    // The value completes when the wait ends, however it ends. A screen already showing a picker
    // when the check started has no other way to learn that it is now out of date.
    private final Map<String, CountDownLatch> checksInFlight = new ConcurrentHashMap<>();

    /**
     * Creates the presenter over the effects the VISION PROVIDER card reaches beyond the settings
     * document.
     *
     * @param secretStore {@link SecretStore} where a provider's credential is stored
     * @param providers {@link VisionProviderCatalog} which providers this install has, and what
     *         each of them uses
     * @param settingsUseCase {@link SettingsUseCase} reads the model saved for a provider, to
     *         preselect it in a freshly drawn picker
     */
    public VisionProviderPresenter(final SecretStore secretStore, final VisionProviderCatalog providers,
                                   final SettingsUseCase settingsUseCase) {
        this.secretStore = secretStore;
        this.providers = providers;
        this.settingsUseCase = settingsUseCase;
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
                    + "key again. If that keeps happening, report it as a bug, quoting this: "
                    + e.getMessage();
        } catch (final SecretStoreException e) {
            return "This key could not be saved. The credential store on this computer refused it. "
                    + "Nothing else you have configured is affected. Report this as a bug, "
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
            return "This key could not be cleared from everywhere it is held, so it may still be what "
                    + "gets used. Nothing else you have configured is affected. Report this as a bug, "
                    + "quoting this: " + e.getMessage();
        }
    }

    /**
     * What to ask before clearing a provider's key, and what to say once it is gone.
     *
     * <p>Both halves have to name what is left, and that is not the same answer every time. A key
     * held in an environment variable is not Sluice's to clear, so removing the stored one leaves
     * the provider working on that. Saying it stops working would be wrong there.
     *
     * @param providerId {@link String} the provider whose key is being cleared
     * @return {@link SecretRemoval} the question and the two things that can follow it
     */
    public SecretRemoval secretRemoval(final String providerId) {
        final boolean anotherKeyAnswers = this.credentialOf(providerId)
                .map(this::secretStatusOrAbsent)
                .filter(InEnvironment.class::isInstance)
                .isPresent();
        final String consequence = anotherKeyAnswers
                ? "An environment variable on this computer also holds a key for this provider, and "
                        + "that key will be used instead."
                : "This provider will stop working until you add a new key and activate it.";
        return new SecretRemoval("Remove your key?",
                "Sluice will clear this key from everywhere on this computer it can reach. " + consequence,
                anotherKeyAnswers
                        ? "Your key is removed. The key in your environment is used instead."
                        : "Your key is removed. This provider cannot run until you add a new key.");
    }

    /**
     * The words a credential removal needs, from the question to what it leaves behind.
     *
     * @param heading {@link String} what the question is about
     * @param detail {@link String} what removing does, and what it leaves
     * @param removed {@link String} what to say once it is gone
     */
    public record SecretRemoval(String heading, String detail, String removed) {
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
     * Checks one provider's stored credential against the real service and keeps the answer. The
     * next {@link #modelPickerFor(String)} then draws the account's real model list instead of the
     * provider's static floor.
     *
     * <p>Blocking, on the order of a network call, so a caller runs this off the FX thread. Called
     * after a successful save, and by Retry. {@link #refreshModelsAtStartup(String)} is the sibling
     * that runs once as the app opens.
     *
     * <p>Does nothing for a provider that offers no model catalog, since there is no picker for a
     * check to feed.
     *
     * @param providerId {@link String} the provider to check
     */
    public void refreshModels(final String providerId) {
        if (this.offersAModelCatalog(providerId)) {
            this.lastCheck.put(providerId, this.providers.check(providerId));
        }
    }

    /**
     * Checks the given provider once, as the app opens, and keeps the answer the same way
     * {@link #refreshModels} does.
     *
     * <p>Without it a provider that already holds a key is described by the provider's own guess at
     * what it offers, which is wrong rather than merely stale. Until the answer lands, that
     * provider's picker draws {@link SettingsView.ModelPicker.Pending} instead of a list. A list
     * drawn before the answer arrives would be a guess wearing the answer's clothes.
     *
     * <p>Blocking, so a caller runs this off the FX thread. Bounded too: it stops waiting after
     * {@code BOOT_CHECK_BUDGET}, and a check that outlasts it is recorded as one that could not be
     * reached. Falling back to the provider's own static list would put a list nobody confirmed
     * under a line saying nothing had been asked yet. That is the one thing this screen must not do.
     * An answer arriving after the budget is dropped rather than kept. It can then never land on top
     * of a newer one a save or Retry has since stored.
     *
     * <p>Which provider to check is the caller's answer, not this class's: {@link SettingsPresenter}
     * resolves an unrecognised configured id to one this install has before calling here.
     *
     * <p>Does nothing for a provider that offers no model catalog, since there is no picker for a
     * check to feed.
     *
     * @param providerId {@link String} the provider to check
     */
    public void refreshModelsAtStartup(final String providerId) {
        this.refreshModelsAtStartup(providerId, BOOT_CHECK_BUDGET);
    }

    /**
     * The same start-up check, over a stated budget.
     *
     * <p>Package-private for the test of what happens once that budget runs out. On the real one
     * that test would sit there for five seconds.
     *
     * @param providerId {@link String} the provider to check
     * @param budget {@link Duration} how long to wait for the answer before giving up on it
     */
    void refreshModelsAtStartup(final String providerId, final Duration budget) {
        if (!this.offersAModelCatalog(providerId)) {
            return;
        }
        final var settled = new CountDownLatch(1);
        this.checksInFlight.put(providerId, settled);
        // Everything after the put is inside the try, so no failure can leave a provider marked as
        // being checked. One that stayed marked would leave its picker waiting for good.
        try {
            final var answer = CompletableFuture.supplyAsync(() -> this.providers.check(providerId),
                    work -> Thread.ofVirtual().start(work));
            // Never over an answer already there. A save or a Retry made while this was in flight
            // asked the same provider more recently. Overwriting would put back what that newer
            // answer replaced.
            this.lastCheck.putIfAbsent(providerId, answer.get(budget.toMillis(), TimeUnit.MILLISECONDS));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final TimeoutException e) {
            this.lastCheck.putIfAbsent(providerId, new ProviderCheck.Unreachable("it timed out"));
        } catch (final ExecutionException e) {
            // A provider that throws rather than answering has broken what the port asks of it. It
            // is still this screen's job to say so, since the reader is the one left without a list.
            this.lastCheck.putIfAbsent(providerId, new ProviderCheck.Unreachable(String.valueOf(e.getCause())));
        } finally {
            // Keyed on this run's own value, so a second start-up check cannot have its entry
            // removed by the first one finishing. Cleared before the wait is released, so nothing
            // woken by it can read this provider as still being checked.
            this.checksInFlight.remove(providerId, settled);
            settled.countDown();
        }
    }

    /**
     * Whether one provider's start-up check is still out.
     *
     * <p>What {@link SettingsPresenter#save} asks, to word a refusal for a model the picker has
     * nothing selected in. Still waiting on an answer reads differently from a check that already
     * failed.
     *
     * @param providerId {@link String} the provider to ask about
     * @return boolean true while the check has not yet settled
     */
    boolean checkInFlight(final String providerId) {
        return this.checksInFlight.containsKey(providerId);
    }

    /**
     * Waits for one provider's start-up check to settle, and returns at once when none is out.
     *
     * <p>What a screen showing {@link SettingsView.ModelPicker.Pending} calls to learn when it has
     * something better to draw. Without it that screen would keep saying it is checking long after
     * the answer arrived, since nothing else redraws a picker already on display.
     *
     * <p>Settling is the wait ending, not the provider answering. A check that outlasts its budget
     * settles when the budget does, which is the moment the picker stops being right.
     *
     * <p>Blocking, so a caller runs this off the FX thread.
     *
     * @param providerId {@link String} the provider whose check to wait for
     */
    public void awaitStartUpCheck(final String providerId) {
        final CountDownLatch settled = this.checksInFlight.get(providerId);
        if (settled == null) {
            return;
        }
        try {
            settled.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tries a connection setting a screen is holding and has not saved, against the real service.
     *
     * <p>Reads the stored credential, same as {@link #refreshModels}, but checks the given endpoint
     * rather than what is stored. Does not touch what {@link #refreshModels} keeps: the picker
     * reflects the last saved configuration, never a value this screen is only trying out.
     *
     * @param providerId {@link String} the provider to check
     * @param endpoint {@link String} the endpoint field's text, possibly blank for the provider's own
     * @return {@link ConnectionCheckResult} what to say, and whether it succeeded
     */
    public ConnectionCheckResult testConnection(final String providerId, final String endpoint) {
        final ProviderCheck outcome = this.providers.check(providerId,
                new CullProviderSettings(null, blankToNull(endpoint), null));
        return new ConnectionCheckResult(wordCheckOutcome(outcome), outcome instanceof ProviderCheck.Accepted);
    }

    /**
     * What a connection check found, worded for a screen, and whether that was a working answer.
     *
     * @param message {@link String} what to tell the user
     * @param succeeded boolean whether the provider accepted the credential and answered with what
     *     it can run
     */
    public record ConnectionCheckResult(String message, boolean succeeded) {
    }

    /**
     * What the model picker should draw for one provider, and whether its saved model needs a
     * caution.
     *
     * <p>A provider with no model catalog draws nothing, so an empty violation is never read.
     *
     * <p>A provider whose start-up check has not answered yet draws nothing at all.
     *
     * <p>A provider never checked this session, or last answered {@link ProviderCheck.NoCredential},
     * is shown its own static floor. A fresh install has no key to check yet, and a failed check
     * must never fall back to a list wearing the clothes of a working one. Every other outcome
     * answers for itself.
     *
     * @param providerId {@link String} the provider to draw a picker for
     * @return {@link ModelPickerResult} what to draw, and the caution note beside it
     */
    public ModelPickerResult modelPickerFor(final String providerId) {
        return this.modelPickerFor(providerId,
                this.settingsUseCase.settings().providerSettings(providerId).model());
    }

    /**
     * What {@link #modelPickerFor(String)} draws, and the caution note beside it.
     *
     * @param picker {@link SettingsView.ModelPicker} what the picker draws, or null for a provider
     *     with no model setting to draw one for
     * @param unrecognisedNote a note that the saved model is not one the drawn catalog offers, or null
     */
    public record ModelPickerResult(SettingsView.@Nullable ModelPicker picker, @Nullable String unrecognisedNote) {
    }

    /**
     * Every provider this install can cull with, as a dropdown's own choices.
     *
     * @return a {@link List} of {@link SettingsView.ProviderChoice} one per registered provider
     */
    List<SettingsView.ProviderChoice> providerChoices() {
        return this.providers.providers().stream()
                .map(provider -> new SettingsView.ProviderChoice(provider.id(), provider.label(),
                        fieldsOf(provider), provider.defaultEndpoint(), provider.setupGuide()))
                .toList();
    }

    /**
     * Whether a provider has a model catalog, and so a picker for a check to feed.
     *
     * @param providerId {@link String} the provider to ask about
     * @return boolean true when it offers one
     */
    boolean offersAModelCatalog(final String providerId) {
        return this.providers.byId(providerId).filter(provider -> provider.models() != null).isPresent();
    }

    /**
     * One provider's credential status, or absent where the store will not say.
     *
     * <p>A store that refuses to answer is not evidence that a second key is waiting, so it reads
     * the same way as nothing being there. The wording that rests on this only ever softens what a
     * removal costs, and softening it on a guess is the half that would be wrong.
     *
     * @param id {@link SecretId} the credential to ask about
     * @return {@link SecretStatus} what holds it, or null when the store refused
     */
    private @Nullable SecretStatus secretStatusOrAbsent(final SecretId id) {
        try {
            return this.secretStore.status(id);
        } catch (final SecretStoreException e) {
            return null;
        }
    }

    /**
     * What the model picker should draw for one provider, given the model saved for it.
     *
     * @param providerId {@link String} the provider to draw a picker for
     * @param savedModel {@link String} the model id saved for this provider, possibly null or blank
     * @return {@link ModelPickerResult} what to draw, and the caution note beside it
     */
    private ModelPickerResult modelPickerFor(final String providerId, final @Nullable String savedModel) {
        final Optional<VisionProviderDescriptor> descriptor = this.providers.byId(providerId);
        if (descriptor.isEmpty() || descriptor.get().models() == null) {
            return new ModelPickerResult(null, null);
        }
        final ModelCatalog staticFloor = descriptor.get().models();
        final ProviderCheck last = this.lastCheck.get(providerId);
        // Only while nothing has answered. A save made during the start-up check has an answer of
        // its own, and drawing this over it would replace something known with a wait.
        if (last == null && this.checksInFlight.containsKey(providerId)) {
            return new ModelPickerResult(new SettingsView.ModelPicker.Pending("Loading..."), null);
        }
        if (last == null || last instanceof ProviderCheck.NoCredential) {
            return this.picked(staticFloor, savedModel,
                    "Assumed list before you connect to your provider for the first time.");
        }
        if (last instanceof ProviderCheck.Accepted(final ModelCatalog live)) {
            return this.picked(live, savedModel, "What your account can run, from the last connection or test.");
        }
        // NoCredential is handled above; reaching here it is one of the four failure outcomes.
        return new ModelPickerResult(new SettingsView.ModelPicker.Unavailable(wordCheckOutcome(last), savedModel), null);
    }

    /**
     * A picker offering the given catalog, with the saved model selected when the catalog offers it.
     *
     * @param catalog {@link ModelCatalog} the models to offer
     * @param savedModel {@link String} the model id saved for this provider, possibly null or blank
     * @param sourceNote {@link String} which list this is, for {@link SettingsView.ModelPicker.Options#sourceNote()}
     * @return {@link ModelPickerResult} the picker, and a caution when the saved model is not offered
     */
    private ModelPickerResult picked(final ModelCatalog catalog, final @Nullable String savedModel,
                                     final String sourceNote) {
        final boolean offered = savedModel != null
                && catalog.options().stream().anyMatch(option -> option.id().equals(savedModel));
        final String selected = offered ? savedModel
                : catalog.recommended() != null ? catalog.recommended() : catalog.options().getFirst().id();
        final List<SettingsView.ModelChoice> choices = catalog.options().stream()
                .map(option -> new SettingsView.ModelChoice(option.id(), option.label(),
                        option.id().equals(catalog.recommended())))
                .toList();
        // What the picker shows is not what a cull would run. The saved value is what the provider
        // is asked for, right up until a save replaces it. A note claiming the substitute is
        // already in force would send a user off to cull against a model that fails.
        final String caution = savedModel == null || savedModel.isBlank() || offered ? null
                : "Your configuration asks for a model called '" + savedModel + "', which this provider does "
                        + "not offer. Save to replace it with the one picked above. Until you do, a run "
                        + "fails on the model you configured.";
        return new ModelPickerResult(new SettingsView.ModelPicker.Options(choices, selected, sourceNote), caution);
    }

    /**
     * A sentence describing what a provider said to a credential check, for the Test connection button's own
     * result and for a picker with nothing to offer.
     *
     * @param outcome {@link ProviderCheck} what the provider said
     * @return {@link String} the sentence
     */
    private static String wordCheckOutcome(final ProviderCheck outcome) {
        return switch (outcome) {
            case ProviderCheck.Accepted(final ModelCatalog models) ->
                    "This works. This account can run " + models.options().size() + " model(s).";
            case final ProviderCheck.NoUsableModels _ -> "This key works, but this account cannot run any "
                    + "model Sluice needs.";
            case final ProviderCheck.NoCredential _ -> "Nothing is saved to check yet.";
            case final ProviderCheck.Rejected _ -> "This provider rejected the key.";
            case ProviderCheck.Refused(final String detail) ->
                    "This key is recognised, but this account is not allowed to do this: " + detail;
            case ProviderCheck.Unreachable(final String detail) ->
                    "This provider could not be reached: " + detail;
            case final ProviderCheck.NotApplicable _ -> "This provider takes no key to check.";
        };
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
                used.contains(ProviderSetting.CREDENTIAL));
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
     * the label naming what the button does to the key.
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
        return "More than one place on this computer holds a key for this provider. \"Remove\" "
                + "clears every one Sluice can reach."
                + (anyUnaskable ? " One place did not answer, so there may be another beyond these." : "");
    }

    private static @Nullable String blankToNull(final String value) {
        return value.isBlank() ? null : value;
    }
}
