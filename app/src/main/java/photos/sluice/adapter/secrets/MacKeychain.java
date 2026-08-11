package photos.sluice.adapter.secrets;

import photos.sluice.application.port.out.SecretStoreException;

import java.util.Optional;

/**
 * The four operations Sluice needs from the macOS keychain, named in its own terms rather than in
 * the keychain's.
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
 * keychain may ask for a password before handing over a stored value, and it is allowed to put that
 * dialog on screen. Asking whether an entry exists reads only attributes, which are unencrypted, so
 * that question is answered without one.
 */
interface MacKeychain {

    /**
     * Reads the bytes stored under the given entry name.
     *
     * <p>An absent entry is an empty answer rather than a failure. Every other refusal is a failure,
     * including one the user caused by dismissing an unlock dialog. A keychain that cannot say
     * whether it holds something states a different fact from a keychain holding nothing.
     *
     * @param name {@link String} the keychain's name for the entry
     * @return an {@link Optional} of byte array, empty when no entry carries that name
     * @throws SecretStoreException when the keychain refused the read for any other reason,
     *         including an entry it matched and then handed over no bytes for
     */
    Optional<byte[]> read(String name);

    /**
     * Whether an entry with the given name exists, answered from attributes alone and without
     * touching the stored value.
     *
     * @param name {@link String} the keychain's name for the entry
     * @return boolean true when an entry carries that name
     * @throws SecretStoreException when the keychain refused to answer
     */
    boolean holds(String name);

    /**
     * Stores the given bytes under the entry name, replacing whatever that name already held.
     *
     * @param name {@link String} the keychain's name for the entry
     * @param label {@link String} what a user browsing their own keychain sees the entry called;
     *         display only, never a way to find an entry
     * @param secret byte array the bytes to store
     * @throws SecretStoreException when the keychain refused the write
     */
    void write(String name, String label, byte[] secret);

    /**
     * Removes the entry with the given name. Removing one that does not exist is not a failure.
     *
     * @param name {@link String} the keychain's name for the entry
     * @throws SecretStoreException when the keychain refused the removal
     */
    void delete(String name);
}
