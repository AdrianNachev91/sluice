package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SecretHolding;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;
import photos.sluice.application.port.out.StaleSecretNotClearedException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Puts the machine's credential tiers in order and applies each operation to the tiers it belongs
 * to.
 *
 * <p>The three operations reach different tiers, and that asymmetry is the whole of this class. A
 * read tries all of them. A save writes to the writable one that ranks highest and says it can be
 * used here, then clears any tier that outranks it. A remove reaches every writable tier.
 *
 * <p>A remove clearing only the tier that answered would expose an older credential sitting in a
 * tier below it, which reads as the removal having silently failed. A save leaving a stale value
 * in a tier above the one it wrote to has the same shape. The fresh credential is stored, and
 * still loses every read to whatever was already sitting higher up.
 *
 * <p>The environment sits ahead of every stored tier on a read, matching the precedence the app's
 * configuration already follows.
 */
public class TieredSecretStore implements SecretStore {

    private final List<WritableSecretTier> writable;
    private final List<SecretTier> readOrder;

    /**
     * Builds the store over the tiers this machine offers. The one place a tier is registered, so
     * adding a platform's credential store is a change here and nowhere else. The tiers themselves
     * stay inside this package.
     *
     * <p>The file tier is always registered and the keyring tier only where the platform offers
     * one. So a machine with no credential store is not a machine with nowhere to keep a credential.
     *
     * @param environment a {@link Function} resolving an environment variable name to its value,
     *         normally {@code System::getenv}
     * @param osName {@link String} the raw OS name (e.g. system property os.name), which decides
     *         which platform's credential store is looked for
     * @param secretsDirectory {@link Path} the directory the file tier keeps credentials in
     * @return {@link SecretStore} the credential store for this machine
     */
    public static SecretStore forMachine(final Function<String, @Nullable String> environment,
            final String osName, final Path secretsDirectory) {
        final List<WritableSecretTier> tiers = new ArrayList<>(2);
        PlatformKeyring.forThisMachine(osName).ifPresent(tiers::add);
        tiers.add(new FileSecretTier(secretsDirectory));
        return new TieredSecretStore(new EnvironmentSecretTier(environment), tiers);
    }

    /**
     * Creates the store over the environment tier and whichever writable tiers this build offers,
     * ordered by their own declared precedence.
     *
     * @param environment {@link SecretTier} the environment-variable tier, always tried first
     * @param writable a {@link List} of {@link WritableSecretTier} the tiers a credential can be stored in
     */
    TieredSecretStore(final SecretTier environment, final List<WritableSecretTier> writable) {
        this.writable = writable.stream()
                .sorted(Comparator.comparingInt(WritableSecretTier::precedence).reversed())
                .toList();
        final List<SecretTier> order = new ArrayList<>(this.writable.size() + 1);
        order.add(environment);
        order.addAll(this.writable);
        this.readOrder = List.copyOf(order);
    }

    @Override
    public Optional<String> secret(final SecretId id) {
        return this.readOrder.stream()
                .flatMap(tier -> tier.read(id).stream())
                .findFirst();
    }

    @Override
    public SecretStatus status(final SecretId id) {
        return this.readOrder.stream()
                .filter(tier -> tier.holds(id))
                .findFirst()
                .map(tier -> (SecretStatus) tier.location(id))
                .orElseGet(SecretStatus.Absent::new);
    }

    @Override
    public List<SecretHolding> holdings(final SecretId id) {
        return this.readOrder.stream().map(tier -> askOneTier(tier, id)).toList();
    }

    @Override
    public Optional<SecretStatus.StoredLocation> whereASaveWouldStoreIt() {
        return this.tierASaveWouldStoreItIn().map(WritableSecretTier::storedLocation);
    }

    @Override
    public void save(final SecretId id, final String secret) {
        final String stored = vetted(id, secret);
        final WritableSecretTier target = this.tierASaveWouldStoreItIn()
                .orElseThrow(() -> new SecretStoreException(SecretStoreException.Tier.STORE,
                        "No tier on this machine can store the credential for provider '"
                                + id.provider() + "'"));
        target.write(id, stored);
        this.clearStaleValueInHigherTiers(id, target);
    }

    @Override
    public void remove(final SecretId id) {
        final List<RuntimeException> failures = new ArrayList<>();
        // Every tier is attempted even after one refuses. Stopping at the first failure leaves the
        // tiers below it still holding the credential. That is the one outcome this method exists
        // to prevent.
        for (final WritableSecretTier tier : this.writable) {
            try {
                tier.erase(id);
            } catch (final RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            throw clearingFailed(id, failures);
        }
    }

    /**
     * The credential a tier is asked to hold: stripped, and refused when nothing useful is left or
     * when it runs past the ceiling.
     *
     * <p>Stripped because a credential pasted out of a browser or a terminal carries whatever
     * whitespace came with it. A trailing newline reaches the provider as part of the key and fails
     * a call for a reason nothing on screen would explain.
     *
     * <p>Both refusals are measured on the stripped value, since that is what would be stored. A
     * blank one would replace a working credential with something no provider accepts. An
     * over-long one would surface as whatever the platform store says when it runs out of room.
     *
     * @param id {@link SecretId} which credential this is, for the refusal to name
     * @param secret {@link String} the credential as the caller passed it
     * @return {@link String} the stripped credential, safe to hand a tier
     */
    private static String vetted(final SecretId id, final String secret) {
        final String stored = secret.strip();
        if (stored.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to store a blank credential for provider '" + id.provider() + "'");
        }
        if (stored.length() > SecretStore.MAX_SECRET) {
            throw new IllegalArgumentException("The credential for provider '" + id.provider()
                    + "' is longer than the " + SecretStore.MAX_SECRET
                    + " characters one may take: " + stored.length());
        }
        return stored;
    }

    /**
     * The tier a save would write to on this machine right now.
     *
     * <p>Shared by {@link #save} and {@link #whereASaveWouldStoreIt}. Restated in either one, a
     * sentence promising a tier could name one the save then misses.
     *
     * @return an {@link Optional} of {@link WritableSecretTier} the tier a save reaches, empty when
     *         none can be used here
     */
    private Optional<WritableSecretTier> tierASaveWouldStoreItIn() {
        return this.writable.stream().filter(WritableSecretTier::available).findFirst();
    }

    /**
     * Asks one tier what it holds, turning a refusal into an answer.
     *
     * <p>A caller listing every tier wants each tier's own verdict, and one tier that cannot be
     * asked must not take the others' answers with it. That is the machine the listing exists for:
     * a credential store refusing one entry, or an unreadable credential file.
     *
     * <p>Naming the tier sits outside the guard on purpose, so this swallows a failure to answer
     * and never a failure to exist. Every entry therefore names a real place, which is what lets a
     * caller report an unaskable tier rather than quietly leave it out of the count. A tier that
     * cannot say what kind of place it is would be a broken build rather than the broken machine
     * this method is for.
     *
     * <p>{@link RuntimeException} rather than {@link SecretStoreException} alone, so a tier failing
     * some way nobody typed is still one tier's problem. An {@link Error} is left to propagate, the
     * same line every catch-all in the app draws.
     *
     * @param tier {@link SecretTier} the tier to ask
     * @param id {@link SecretId} which credential to ask about
     * @return {@link SecretHolding} that tier's place, and what it answered
     */
    private static SecretHolding askOneTier(final SecretTier tier, final SecretId id) {
        final SecretStatus.Location location = tier.location(id);
        try {
            return new SecretHolding(location, tier.holds(id)
                    ? SecretHolding.Holding.HOLDS
                    : SecretHolding.Holding.EMPTY);
        } catch (final RuntimeException e) {
            return new SecretHolding(location, SecretHolding.Holding.COULD_NOT_BE_ASKED);
        }
    }

    /**
     * Builds the one failure a partly-cleared removal reports, carrying each tier's own refusal.
     *
     * @param id {@link SecretId} the credential that could not be cleared everywhere
     * @param failures a {@link List} of {@link RuntimeException} what each refusing tier threw
     * @return {@link SecretStoreException} the failure to report to the caller
     */
    private static SecretStoreException clearingFailed(final SecretId id,
            final List<RuntimeException> failures) {
        final var failure = new SecretStoreException(SecretStoreException.Tier.STORE,
                "The credential for provider '" + id.provider()
                        + "' was not cleared from every tier that can hold one, so it may still answer a read");
        failures.forEach(failure::addSuppressed);
        return failure;
    }

    /**
     * Asks every writable tier that outranks the one just written to give up what it holds. An
     * older value cannot then outrank the fresh one on the next read.
     *
     * <p>Reached only after {@code target}'s own write has already succeeded.
     *
     * <p>Best effort, and it cannot reach the case that motivates it. A tier outranks the target
     * only by having answered {@code available()} false, or it would have taken the write itself.
     * Every keyring tier returns quietly from an erase against a store it cannot reach, which is a
     * trade {@link #remove} documents and this inherits. Take the headline case: a session with no
     * D-Bus saves through the file tier, under a keyring still holding an older credential. The
     * clear does nothing, the save reports success, and the keyring answers the older value again
     * once it is reachable.
     *
     * <p>What this does catch is a store that answered as unreachable when the target was chosen
     * and is reachable by the time its erase runs, then refuses that erase. Narrow, and reported
     * through {@link #staleValueNotCleared} when it happens.
     *
     * <p>Every outranking tier is attempted even after one refuses, the same reasoning
     * {@link #remove} follows. Stopping at the first failure would leave a tier below it still
     * holding the stale value.
     *
     * @param id {@link SecretId} which credential was just stored
     * @param target {@link WritableSecretTier} the tier that took the write
     * @throws SecretStoreException when a stale value could not be cleared from every tier above
     *         {@code target}
     */
    private void clearStaleValueInHigherTiers(final SecretId id, final WritableSecretTier target) {
        final List<RuntimeException> failures = new ArrayList<>();
        final List<WritableSecretTier> outranking =
                this.writable.subList(0, this.writable.indexOf(target));
        for (final WritableSecretTier tier : outranking) {
            try {
                tier.erase(id);
            } catch (final RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            throw staleValueNotCleared(id, failures);
        }
    }

    /**
     * Builds the one failure a partly-cleared save reports, carrying each tier's own refusal.
     *
     * @param id {@link SecretId} the credential whose fresh write may still be shadowed
     * @param failures a {@link List} of {@link RuntimeException} what each refusing tier threw
     * @return {@link StaleSecretNotClearedException} the failure to report to the caller
     */
    private static StaleSecretNotClearedException staleValueNotCleared(final SecretId id,
            final List<RuntimeException> failures) {
        final var failure = new StaleSecretNotClearedException(
                "The credential for provider '" + id.provider()
                        + "' was stored, but an older value above it could not be cleared, so it"
                        + " may still answer a read");
        failures.forEach(failure::addSuppressed);
        return failure;
    }
}
