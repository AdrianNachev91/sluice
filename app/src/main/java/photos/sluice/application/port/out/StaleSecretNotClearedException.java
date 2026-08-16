package photos.sluice.application.port.out;

/**
 * Thrown when a credential was stored and an older copy above it could not be cleared, so that
 * older copy may still answer a read.
 *
 * <p>A subtype rather than a third {@link Tier#STORE} message. A surface has to say the opposite
 * thing here from what it says when no tier could take the credential at all. That one sends a user
 * back to enter a key. This one says the key is saved and re-entering it is the wrong move. Both
 * arrive as {@code STORE}, and telling them apart by message prose is what the tier field exists to
 * avoid.
 *
 * <p>{@link SecretStore#remove}'s own partial failure needs no subtype of its own. It is the only
 * failure {@code remove} produces, so the call site already discriminates it.
 */
public final class StaleSecretNotClearedException extends SecretStoreException {

    /**
     * Creates the exception with a message naming the provider whose fresh credential may be
     * shadowed.
     *
     * @param message {@link String} what failed, without the credential in it
     */
    public StaleSecretNotClearedException(final String message) {
        super(Tier.STORE, message);
    }
}
