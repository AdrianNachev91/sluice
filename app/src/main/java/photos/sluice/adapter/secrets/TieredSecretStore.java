package photos.sluice.adapter.secrets;

import org.jspecify.annotations.Nullable;
import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStore;
import photos.sluice.application.port.out.SecretStoreException;

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
                .map(tier -> tier.statusWhenAnswering(id))
                .orElseGet(SecretStatus.Absent::new);
    }

    @Override
    public void save(final SecretId id, final String secret) {
        // Stored stripped, because a credential pasted out of a browser or a terminal carries
        // whatever whitespace came with it. A trailing newline reaches the provider as part of the
        // key and fails a call for a reason nothing on screen would explain.
        final String stored = secret.strip();
        if (stored.isEmpty()) {
            throw new IllegalArgumentException(
                    "Refusing to store a blank credential for provider '" + id.provider() + "'");
        }
        final WritableSecretTier target = this.writable.stream()
                .filter(WritableSecretTier::available)
                .findFirst()
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
     * Clears every writable tier that outranks the one just written to, so an older value cannot
     * outrank the fresh one on the next read.
     *
     * <p>Reached only after {@code target}'s own write has already succeeded. A session with no
     * D-Bus can save through the file tier while the keyring above it still holds an older
     * credential from an earlier session. Nothing on the desktop tells the two tiers apart, so the
     * keyring would keep answering first once it is reachable again.
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
     * @return {@link SecretStoreException} the failure to report to the caller
     */
    private static SecretStoreException staleValueNotCleared(final SecretId id,
            final List<RuntimeException> failures) {
        final var failure = new SecretStoreException(SecretStoreException.Tier.STORE,
                "The credential for provider '" + id.provider()
                        + "' was stored, but an older value above it could not be cleared, so it"
                        + " may still answer a read");
        failures.forEach(failure::addSuppressed);
        return failure;
    }
}
