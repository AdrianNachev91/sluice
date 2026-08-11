package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretStoreException;

import java.util.Optional;

/**
 * The three operations Sluice needs from the Windows Credential Manager, named in its own terms
 * rather than in the credential store's.
 *
 * <p>It exists so the tier above it can be tested on every platform. The implementation binds native
 * functions and therefore runs on one runner out of three, which puts it outside the coverage gate.
 * Everything that decides what to store, how to name it and what an answer means stays on this side
 * of the seam and inside the gate.
 *
 * <p>A target name is opaque here. The tier derives one from a provider and this interface only
 * carries it through.
 */
interface WindowsCredentialManager {

    /**
     * Reads the bytes stored under the given target name.
     *
     * <p>An absent entry is an empty answer rather than a failure. Every other refusal is a
     * failure. A store that cannot say whether it holds something states a different fact from a
     * store holding nothing.
     *
     * @param target {@link String} the credential store's name for the entry
     * @return an {@link Optional} of byte array, empty when no entry carries that name
     * @throws SecretStoreException when the credential store refused the read for any other reason
     */
    Optional<byte[]> read(String target);

    /**
     * Stores the given bytes under the target name, replacing whatever that name already held.
     *
     * @param target {@link String} the credential store's name for the entry
     * @param userName {@link String} the account name shown beside the entry in the Windows
     *         Credential Manager; display only, never a way to find an entry
     * @param secret byte array the bytes to store
     * @throws SecretStoreException when the credential store refused the write
     */
    void write(String target, String userName, byte[] secret);

    /**
     * Removes the entry with the given target name. Removing one that does not exist is not a
     * failure.
     *
     * @param target {@link String} the credential store's name for the entry
     * @throws SecretStoreException when the credential store refused the removal
     */
    void delete(String target);
}
