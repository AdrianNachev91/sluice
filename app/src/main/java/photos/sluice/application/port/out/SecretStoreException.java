package photos.sluice.application.port.out;

/**
 * Thrown when a tier cannot determine what it holds, or refuses to store or clear a credential.
 *
 * <p>Unchecked, because there is no recovery a caller can attempt. A credential store answering
 * neither yes nor no is a broken install rather than a state to branch on. Reporting it as "no
 * credential" would send a user to re-enter one already sitting there.
 *
 * <p>The message names the provider and where the failure happened, never the credential. A failure
 * gathered from several tiers carries each tier's own refusal as a suppressed exception.
 */
public class SecretStoreException extends RuntimeException {

    /**
     * Creates the exception with a message naming what failed.
     *
     * @param message {@link String} what failed, without the credential in it
     */
    public SecretStoreException(final String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and the underlying failure.
     *
     * @param message {@link String} what failed, without the credential in it
     * @param cause {@link Throwable} the underlying failure
     */
    public SecretStoreException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
