package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Keeps a credential in the macOS keychain, the credential store the operating system offers.
 *
 * <p>This is the tier that answers on macOS, and it outranks the protected file. The file tier
 * exists for machines with nothing better. Where the operating system offers a real credential
 * store, that store wins the save.
 *
 * <p>One entry per provider, named for it, so clearing one credential cannot disturb another. The
 * name is derived here rather than carried on the id, because what an entry is called is this
 * platform's business and no other tier's.
 *
 * <p>Whether an entry exists is answered from its attributes, which the keychain keeps unencrypted,
 * so drawing a settings label never puts a password dialog on screen. Reading the credential itself
 * may, and that is the right place for the dialog: the app is about to use the key.
 *
 * <p>That split costs the same divergence the Linux tier records. An entry someone hand-filled with
 * only whitespace reports as held here and still answers nothing on a read. Sluice itself can never
 * store one, since a save strips the value and refuses a blank. The alternative was reading the
 * value to decide, which is the dialog the split exists to avoid.
 *
 * <p>Where this platform differs from the Linux one is what a locked store looks like. Apple
 * documents a distinct status code for a keychain that needs to ask something and cannot. An entry
 * that exists and will not open should therefore arrive as a refusal rather than as silence.
 * Nothing here asks a second time to tell that apart from an absent entry.
 *
 * <p>That rests on the documentation rather than on observation, and the difference is worth
 * keeping. No test has run against a locked keychain, because a hosted runner's is unlocked. The
 * residue is covered anyway: an item the keychain matches and then hands no bytes for fails loud
 * rather than reporting absence, whatever the reason for it.
 *
 * <p>What a read answers is the stored value stripped of surrounding whitespace, and nothing at all
 * when only whitespace is stored. That matches every other tier, so which one answered cannot change
 * what a provider receives.
 */
final class MacKeychainTier implements WritableSecretTier {

    static final int PRECEDENCE = 100;

    private static final String LABEL_PREFIX = "Sluice:";

    /**
     * The entry name the availability probe asks about. Nothing ever stores it, so a healthy
     * keychain answers that it holds no such entry, and that answer is the proof being sought.
     *
     * <p>Capitalised so this app can never name it itself. A provider id is lower case by the rule
     * that validates one, which puts this name out of reach rather than merely making it unlikely.
     *
     * <p>Another application naming the same entry is not excluded, because the service attribute
     * that groups this app's entries is a convention rather than a boundary. What that costs is
     * nothing. The probe judges whether the call worked and never looks at what came back. It also
     * asks only about existence, so a collision can neither produce a wrong answer nor read a
     * stranger's value.
     */
    private static final String PROBE_NAME = "AvailabilityProbe";

    private final MacKeychain keychain;

    /**
     * Creates the tier over the keychain it reads and writes through.
     *
     * @param keychain {@link MacKeychain} the machine's keychain
     */
    MacKeychainTier(final MacKeychain keychain) {
        this.keychain = keychain;
    }

    @Override
    public Optional<String> read(final SecretId id) {
        try {
            return this.keychain.read(name(id))
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
        try {
            return this.keychain.holds(name(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
            return false;
        }
    }

    @Override
    public SecretStatus statusWhenAnswering(final SecretId id) {
        return new SecretStatus.InKeyring();
    }

    @Override
    public boolean available() {
        // Asked rather than assumed. A process can carry a working Security.framework and still
        // reach no keychain, one running with no login session most of all. Nothing here can tell
        // in advance whether this is one. A machine like that should still keep its credential in
        // the tier below, which is what this answer routes both a save and a read towards.
        try {
            this.keychain.holds(PROBE_NAME);
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
        // The label is what a user browsing their own keychain sees, so it names the app and the
        // provider. Nothing reads it back.
        this.keychain.write(name(id), LABEL_PREFIX + id.provider(),
                secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void erase(final SecretId id) {
        try {
            this.keychain.delete(name(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
        }
    }

    /**
     * Decides what a refusal meant, by asking whether the keychain answers at all.
     *
     * <p>A keychain that answers other calls and refused this one has genuinely failed, and its
     * caller hears about it. Reporting that as an absent credential would send the user to replace
     * a key that is already stored. A locked keychain is the commonest way to reach this, and
     * unlocking it is the repair the message points toward.
     *
     * <p>A keychain refusing everything is a different fact, and returning quietly is what says so.
     * The protected file is where such a machine keeps its credential, because the same answer
     * sends its save there.
     *
     * <p>The trade, taken deliberately. A machine that stored a credential and lost its keychain
     * afterwards keeps that credential through a removal, and hears the removal succeed. Failing
     * every removal instead would punish the far commoner machine this exists to fix.
     *
     * @param refused {@link SecretStoreException} what the keychain threw
     * @throws SecretStoreException when the keychain is working and this call still failed
     */
    private void rethrowIfTheStoreIsWorking(final SecretStoreException refused) {
        if (this.available()) {
            throw refused;
        }
    }

    /**
     * Names the keychain entry one provider's credential lives in.
     *
     * @param id {@link SecretId} the credential to name
     * @return {@link String} that credential's entry name
     */
    private static String name(final SecretId id) {
        return id.provider();
    }
}
