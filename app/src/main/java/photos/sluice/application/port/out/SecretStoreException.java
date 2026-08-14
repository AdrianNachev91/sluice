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
 *
 * <p>Every failure names the {@link Tier} it happened in. A surface rendering one branches on that
 * rather than reading prose out of the message. A keyring refusal and an unreadable credential file
 * need different sentences, and the message text is wording rather than a contract.
 */
public class SecretStoreException extends RuntimeException {

    /**
     * Where a failure happened, for the surface that has to word it.
     */
    public enum Tier {

        /**
         * The operating system's own credential store refused an operation.
         */
        KEYRING,

        /**
         * The protected credential file could not be read, written or cleared.
         */
        FILE,

        /**
         * The failure belongs to the store's composition rather than to any one tier. It covers a
         * save no tier could accept, a save that stored but could not clear a stale copy above it,
         * or a removal that partly failed. The refusing tiers' own failures ride along as
         * suppressed exceptions.
         */
        STORE
    }

    private final Tier tier;

    /**
     * Creates the exception with a message naming what failed.
     *
     * @param tier {@link Tier} where the failure happened
     * @param message {@link String} what failed, without the credential in it
     */
    public SecretStoreException(final Tier tier, final String message) {
        super(message);
        this.tier = tier;
    }

    /**
     * Creates the exception with a message and the underlying failure.
     *
     * @param tier {@link Tier} where the failure happened
     * @param message {@link String} what failed, without the credential in it
     * @param cause {@link Throwable} the underlying failure
     */
    public SecretStoreException(final Tier tier, final String message, final Throwable cause) {
        super(message, cause);
        this.tier = tier;
    }

    /**
     * Where the failure happened.
     *
     * @return {@link Tier} the tier this failure belongs to
     */
    public Tier tier() {
        return this.tier;
    }
}
