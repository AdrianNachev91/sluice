package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Keeps a credential in the Windows Credential Manager, the machine's own credential store.
 *
 * <p>This is the tier that answers on Windows, and it outranks the protected file. The file tier
 * exists for machines with nothing better. Where the operating system offers a real credential
 * store, that store wins the save.
 *
 * <p>One entry per provider, named for it, so clearing one credential cannot disturb another. The
 * name is derived here rather than carried on the id, because what an entry is called is this
 * platform's business and no other tier's.
 *
 * <p>Reading needs no unlocking and raises no prompt. A generic credential belongs to the account
 * that stored it, and is already available to that account's own processes. Drawing a settings
 * label therefore costs nothing but the lookup.
 *
 * <p>What a read answers is the stored value stripped of surrounding whitespace, and nothing at all
 * when only whitespace is stored. That matches every other tier, so which one answered cannot change
 * what a provider receives.
 */
final class WindowsCredentialTier implements WritableSecretTier {

    static final int PRECEDENCE = 100;

    private static final String TARGET_PREFIX = "Sluice:";

    /**
     * The entry name the availability probe reads. Nothing ever stores it, so a healthy credential
     * store answers that it holds no such entry, and that answer is the proof being sought.
     *
     * <p>Capitalised so no provider can ever name it. A provider id is lower case by the rule that
     * validates one, which makes this name unreachable rather than merely unlikely.
     */
    private static final String PROBE_TARGET = TARGET_PREFIX + "AvailabilityProbe";

    private final WindowsCredentialManager credentials;

    /**
     * Creates the tier over the credential store it reads and writes through.
     *
     * @param credentials {@link WindowsCredentialManager} the machine's credential store
     */
    WindowsCredentialTier(final WindowsCredentialManager credentials) {
        this.credentials = credentials;
    }

    @Override
    public Optional<String> read(final SecretId id) {
        try {
            return this.credentials.read(target(id))
                    .map(stored -> new String(stored, StandardCharsets.UTF_8))
                    .map(String::strip)
                    .filter(credential -> !credential.isEmpty());
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
            return Optional.empty();
        }
    }

    @Override
    public boolean holds(final SecretId id) {
        return this.read(id).isPresent();
    }

    @Override
    public SecretStatus statusWhenAnswering(final SecretId id) {
        return new SecretStatus.InKeyring();
    }

    @Override
    public boolean available() {
        // Asked rather than assumed. A process with no user profile loaded gets a credential store
        // that refuses every call, and nothing here can tell in advance whether this is one. A
        // machine like that should still keep its credential in the tier below, which is what this
        // answer routes both a save and a read towards.
        try {
            this.credentials.read(PROBE_TARGET);
            return true;
        } catch (final SecretStoreException unreachable) {
            return false;
        }
    }

    @Override
    public int precedence() {
        return PRECEDENCE;
    }

    @Override
    public void write(final SecretId id, final String secret) {
        // The provider rides along as the entry's account name, which is what the Windows Credential
        // Manager shows a user browsing their own credentials. Nothing reads it back.
        this.credentials.write(target(id), id.provider(), secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void erase(final SecretId id) {
        try {
            this.credentials.delete(target(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
        }
    }

    /**
     * Decides what a refusal meant, by asking whether the credential store answers at all.
     *
     * <p>A store that answers other calls and refused this one has genuinely failed, and its caller
     * hears about it. Reporting that as an absent credential would send the user to replace a key
     * that is already stored.
     *
     * <p>A store refusing everything is a different fact, and returning quietly is what says so.
     * The protected file is where such a machine keeps its credential, because the same answer
     * sends its save there.
     *
     * <p>The trade, taken deliberately. A machine that stored a credential and lost access to its
     * store afterwards keeps that credential through a removal, and hears the removal succeed.
     * Failing every removal instead would punish the far commoner machine this exists to fix.
     *
     * @param refused {@link SecretStoreException} what the credential store threw
     * @throws SecretStoreException when the credential store is working and this call still failed
     */
    private void rethrowIfTheStoreIsWorking(final SecretStoreException refused) {
        if (this.available()) {
            throw refused;
        }
    }

    /**
     * Names the credential store entry one provider's credential lives in.
     *
     * @param id {@link SecretId} the credential to name
     * @return {@link String} that credential's entry name
     */
    private static String target(final SecretId id) {
        return TARGET_PREFIX + id.provider();
    }
}
