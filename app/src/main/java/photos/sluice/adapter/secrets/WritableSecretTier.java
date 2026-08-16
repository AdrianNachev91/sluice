package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;
import photos.sluice.application.port.out.SecretStoreException;

/**
 * A tier a credential can also be stored in and cleared from.
 *
 * <p>{@link #precedence} is declared by each tier rather than inferred from bean ordering. The
 * order a machine's tiers are tried in is then readable in the tier itself, and testable without
 * the framework.
 */
interface WritableSecretTier extends SecretTier {

    /**
     * How a settings screen names this tier. Takes no id, unlike {@link SecretTier#location},
     * because a tier a credential is stored in is the same place whichever credential it is. Only
     * the environment names itself per id, and nothing writes there.
     *
     * @return {@link SecretStatus.StoredLocation} this tier's place
     */
    SecretStatus.StoredLocation storedLocation();

    /**
     * Names this tier for a caller that does not know it can be written to.
     *
     * @param id {@link SecretId} the credential being reported on, unused here
     * @return {@link SecretStatus.Location} this tier's place
     */
    @Override
    default SecretStatus.Location location(final SecretId id) {
        return this.storedLocation();
    }

    /**
     * Whether this tier can be used on this machine right now. A credential store the platform does
     * not offer answers false.
     *
     * @return boolean true when this tier can hold a credential here
     */
    boolean available();

    /**
     * Where this tier sits when several are available. Higher wins.
     *
     * @return int this tier's precedence
     */
    int precedence();

    /**
     * Stores the given credential, replacing any this tier already holds for the id.
     *
     * @param id {@link SecretId} which credential to store
     * @param secret {@link String} the credential to store
     * @throws SecretStoreException when the credential was not stored
     */
    void write(SecretId id, String secret);

    /**
     * Clears any credential this tier holds for the id. Holding none is not an error.
     *
     * @param id {@link SecretId} which credential to clear
     * @throws SecretStoreException when a credential this tier holds could not be cleared
     */
    void erase(SecretId id);
}
