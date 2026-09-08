package photos.sluice.application.port.out;

import photos.sluice.secrets.SecretId;
import photos.sluice.secrets.SecretStore;
import photos.sluice.secrets.SecretStoreException;

/**
 * Thrown when a provider needs a credential and no tier holds one.
 *
 * <p>Absence is not a {@link SecretStoreException}. A store that answers "nothing here" has worked
 * correctly, and {@link SecretStore#secret} reports that as an empty result rather than a failure.
 * This is the refusal a provider raises once it finds it cannot go on without one.
 *
 * <p>It carries the {@link SecretId}, so a surface can offer the two routes to storing one without
 * reading them back out of the message. Type and id are the contract; the sentence is wording, and
 * a caller matching on it breaks the first time that wording changes.
 *
 * <p>An {@link IllegalStateException} subtype, so a caller that only wants to know it was refused
 * needs no knowledge of this type at all.
 */
public final class MissingCredentialException extends IllegalStateException {

    private final transient SecretId id;

    /**
     * Creates the exception, naming the credential nothing holds.
     *
     * @param id {@link SecretId} the credential that was looked for
     * @param message {@link String} what was refused, and where a credential can be put
     */
    public MissingCredentialException(final SecretId id, final String message) {
        super(message);
        this.id = id;
    }

    /**
     * The credential nothing holds.
     *
     * @return {@link SecretId} the credential that was looked for
     */
    public SecretId id() {
        return this.id;
    }
}
