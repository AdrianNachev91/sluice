package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretId;
import photos.sluice.application.port.out.SecretStatus;

import java.util.Optional;

/**
 * One place a credential can be read from.
 *
 * <p>Read-only on its own. An environment variable is a tier nothing can write to, so writing lives
 * on {@link WritableSecretTier} instead of here with a method that would have to refuse.
 */
interface SecretTier {

    /**
     * The credential this tier holds for the given id, ready to be handed to a provider rather than
     * as whatever shape it was stored in. Each tier says what that means for its own storage.
     *
     * @param id {@link SecretId} which credential to read
     * @return an {@link Optional} of {@link String}, empty when this tier holds none
     */
    Optional<String> read(SecretId id);

    /**
     * Whether this tier holds a credential for the given id.
     *
     * <p>Separate from {@link #read} so a tier that cannot be read cheaply has somewhere to answer
     * from. A machine's own credential store may ask the user to unlock it, and a screen drawing a
     * label has no business raising that prompt.
     *
     * @param id {@link SecretId} which credential to look for
     * @return boolean true when this tier holds one
     */
    boolean holds(SecretId id);

    /**
     * How a settings screen names this tier, whether or not it holds anything. A screen listing
     * every tier has to name one that answered nothing, and one that would not answer at all.
     *
     * @param id {@link SecretId} the credential being reported on, for tiers whose wording carries it
     * @return {@link SecretStatus.Location} this tier's place
     */
    SecretStatus.Location location(SecretId id);
}
