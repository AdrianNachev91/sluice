package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretStoreException;

import java.util.Optional;

/**
 * The four operations Sluice needs from the freedesktop Secret Service, named in its own terms
 * rather than in the service's.
 *
 * <p>It exists so the tier above it can be tested on every platform. The implementation binds native
 * functions and therefore runs on one runner out of three, which puts it outside the coverage gate.
 * Everything that decides what to store, how to name it and what an answer means stays on this side
 * of the seam and inside the gate.
 *
 * <p>An entry name is opaque here. The tier derives one from a provider and this interface only
 * carries it through.
 *
 * <p>{@link #holds} exists apart from {@link #read} because the two cost the user differently. The
 * service keeps entries in collections it may hold locked, and reading through a locked one is
 * allowed to put an unlock dialog on screen. Asking whether an entry exists is not.
 */
interface LinuxSecretService {

    /**
     * Reads the credential stored under the given entry name.
     *
     * <p>An empty answer means the service produced no value, which covers more than absence. An
     * entry in a collection that stayed locked arrives the same way, because the service abandons
     * the unlock without reporting anything. A caller that needs to tell those apart asks
     * {@link #holds}. A refusal the service does report is a failure rather than an empty answer.
     *
     * @param name {@link String} the service's name for the entry
     * @return an {@link Optional} of {@link String}, empty when no entry carries that name
     * @throws SecretStoreException when the service refused the read for any other reason
     */
    Optional<String> read(String name);

    /**
     * Whether an entry with the given name exists, answered without unlocking anything and without
     * touching the stored value.
     *
     * @param name {@link String} the service's name for the entry
     * @return boolean true when an entry carries that name
     * @throws SecretStoreException when the service refused to answer
     */
    boolean holds(String name);

    /**
     * Stores the given credential under the entry name, replacing whatever that name already held.
     *
     * @param name {@link String} the service's name for the entry
     * @param label {@link String} what a user browsing their own keyring sees the entry called;
     *         display only, never a way to find an entry
     * @param secret {@link String} the credential to store
     * @throws SecretStoreException when the service refused the write
     */
    void write(String name, String label, String secret);

    /**
     * Removes the entry with the given name. Removing one that does not exist is not a failure.
     *
     * @param name {@link String} the service's name for the entry
     * @throws SecretStoreException when the service refused the removal
     */
    void delete(String name);
}
