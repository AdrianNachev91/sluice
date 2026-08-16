package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

import java.util.Optional;

/**
 * Keeps a credential in the freedesktop Secret Service, the credential store Linux desktops share.
 *
 * <p>This is the tier that answers on Linux, and it outranks the protected file. The file tier
 * exists for machines with nothing better. Where the operating system offers a real credential
 * store, that store wins the save.
 *
 * <p>One entry per provider, named for it, so clearing one credential cannot disturb another. The
 * name is derived here rather than carried on the id, because what an entry is called is this
 * platform's business and no other tier's.
 *
 * <p>Whether an entry exists is answered without unlocking anything, so drawing a settings label
 * never puts an unlock dialog on screen. Reading the credential itself may, where the keyring is
 * locked, and that is the right place for the dialog: the app is about to use the key.
 *
 * <p>That split carries two consequences for a read, and they pull in opposite directions. An entry
 * someone hand-filled with only whitespace reports as held here and still answers nothing on a
 * read. Sluice itself can never store one, since a save strips the value and refuses a blank. A
 * locked collection is the commoner case and gets the opposite treatment. A read that finds
 * nothing asks whether the entry is there, and fails loudly when it is.
 *
 * <p>That second one costs a machine something, and the trade is deliberate. Where a locked
 * keyring holds the entry and the protected file holds an older copy, the failure stops the file
 * from answering. The file copy is the staler of the two by construction, and a status line
 * reading "in your keyring" sits above it either way. So the choice is between a wrong credential
 * served quietly and the right one refused loudly, and refusing is the honest half. An environment
 * variable overrides both, because it is read before this tier is reached.
 *
 * <p>What a read answers is the stored value stripped of surrounding whitespace, and nothing at all
 * when only whitespace is stored. That matches every other tier, so which one answered cannot change
 * what a provider receives.
 */
final class LinuxSecretServiceTier implements WritableSecretTier {

    static final int PRECEDENCE = 100;

    private static final String LABEL_PREFIX = "Sluice:";

    /**
     * The entry name the availability probe asks about. Nothing ever stores it, so a healthy
     * service answers that it holds no such entry, and that answer is the proof being sought.
     *
     * <p>Two separate things keep this name from matching something real, and they cover different
     * populations. Another application's secrets are excluded by the schema, which every call
     * carries and which the service matches on. A credential stored by anything else answers to a
     * different schema and is invisible to every lookup, search and removal made here.
     *
     * <p>This app's own credentials are excluded by the capitalisation. A provider id is lower
     * case by the rule that validates one, so no provider can ever name this entry. That puts the
     * name out of reach rather than merely making it unlikely.
     *
     * <p>Neither guarantee is load-bearing for the answer. The probe judges whether the call
     * worked and never looks at what came back, so a match would change nothing it reports.
     */
    private static final String PROBE_NAME = "AvailabilityProbe";

    private final LinuxSecretService secrets;

    /**
     * Creates the tier over the service it reads and writes through.
     *
     * @param secrets {@link LinuxSecretService} the machine's Secret Service
     */
    LinuxSecretServiceTier(final LinuxSecretService secrets) {
        this.secrets = secrets;
    }

    @Override
    public Optional<String> read(final SecretId id) {
        final Optional<String> stored;
        try {
            stored = this.secrets.read(name(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
            return Optional.empty();
        }
        if (stored.isEmpty()) {
            this.failIfTheEntryIsThereAndWouldNotOpen(id);
            return Optional.empty();
        }
        return stored.map(String::strip).filter(credential -> !credential.isEmpty());
    }

    @Override
    public boolean holds(final SecretId id) {
        try {
            return this.secrets.holds(name(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
            return false;
        }
    }

    @Override
    public SecretStatus.StoredLocation storedLocation() {
        return new SecretStatus.InKeyring();
    }

    @Override
    public boolean available() {
        // Asked rather than assumed. A machine can carry libsecret with nothing answering on its
        // bus, a headless server most of all. Nothing here can tell in advance whether this is
        // one. A machine like that should still keep its credential in the tier below, which is
        // what this answer routes both a save and a read towards.
        try {
            this.secrets.holds(PROBE_NAME);
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
        // The label is what a user browsing their keyring sees, so it names the app and the
        // provider. Nothing reads it back.
        this.secrets.write(name(id), LABEL_PREFIX + id.provider(), secret);
    }

    @Override
    public void erase(final SecretId id) {
        try {
            this.secrets.delete(name(id));
        } catch (final SecretStoreException refused) {
            this.rethrowIfTheStoreIsWorking(refused);
        }
    }

    /**
     * Fails where an entry exists that the service would not hand over.
     *
     * <p>A read that answers nothing has two causes on this platform. There is no such entry, which
     * is the ordinary one. Or the entry sits in a collection that stayed locked, and the service
     * gives up on it without reporting anything. Both arrive here as the same empty answer.
     *
     * <p>Telling them apart matters because they mean opposite things to a user. Absence sends them
     * to add a key. A locked collection means their key is right there and the machine would not
     * open it. Left undistinguished, a status line reading "in your keyring" would sit above a run
     * that could not find a credential, with nothing anywhere saying why.
     *
     * <p>The existence check reaches the service a second time, and only on the path where the
     * first call found nothing. A stored credential is answered by the first call alone.
     *
     * @param id {@link SecretId} the credential that answered nothing
     * @throws SecretStoreException when the service holds that entry and would not open it, and
     *         equally when a working service refuses the existence check itself. That second one
     *         carries the search's own message rather than this method's.
     */
    private void failIfTheEntryIsThereAndWouldNotOpen(final SecretId id) {
        if (this.holds(id)) {
            throw new SecretStoreException(SecretStoreException.Tier.KEYRING,
                    "The credential for provider '" + id.provider() + "' is in this machine's "
                            + "keyring, which would not open it. Unlocking the keyring is what "
                            + "makes it readable.");
        }
    }

    /**
     * Decides what a refusal meant, by asking whether the service answers at all.
     *
     * <p>A service that answers other calls and refused this one has genuinely failed, and its
     * caller hears about it. Reporting that as an absent credential would send the user to replace
     * a key that is already stored.
     *
     * <p>A service refusing everything is a different fact, and returning quietly is what says so.
     * The protected file is where such a machine keeps its credential, because the same answer
     * sends its save there.
     *
     * <p>The trade, taken deliberately. A machine that stored a credential and lost its service
     * afterwards keeps that credential through a removal, and hears the removal succeed. Failing
     * every removal instead would punish the far commoner machine this exists to fix.
     *
     * @param refused {@link SecretStoreException} what the service threw
     * @throws SecretStoreException when the service is working and this call still failed
     */
    private void rethrowIfTheStoreIsWorking(final SecretStoreException refused) {
        if (this.available()) {
            throw refused;
        }
    }

    /**
     * Names the service entry one provider's credential lives in.
     *
     * @param id {@link SecretId} the credential to name
     * @return {@link String} that credential's entry name
     */
    private static String name(final SecretId id) {
        return id.provider();
    }
}
