package photos.sluice.application.port.out;

import java.util.Optional;

/**
 * Where a credential lives, across the tiers a machine offers.
 *
 * <p>Two methods rather than one, because the two callers want opposite things. Whatever calls a
 * provider's API needs the value and not the tier. A settings screen needs the tier and must never
 * hold the value. So {@link #status} cannot return a credential at all.
 *
 * <p>A read consults every tier and the first to answer wins, with an environment variable ahead of
 * anything stored. That is the precedence the rest of the app's configuration already follows.
 *
 * <p>A save never reaches an environment variable, and a remove clears every tier that can hold a
 * value. Clearing only the tier that answered would expose an older value underneath it.
 */
public interface SecretStore {

    /**
     * The credential in force for the given id, from whichever tier answers first.
     *
     * @param id {@link SecretId} which credential to read
     * @return an {@link Optional} of {@link String}, empty when no tier holds one
     * @throws SecretStoreException when a tier cannot determine what it holds
     */
    Optional<String> secret(SecretId id);

    /**
     * Which tier answers for the given id, without reporting what it holds.
     *
     * @param id {@link SecretId} which credential to report on
     * @return {@link SecretStatus} the answering tier, or absent when none holds a value
     * @throws SecretStoreException when a tier cannot determine what it holds
     */
    SecretStatus status(SecretId id);

    /**
     * Stores the given credential in the strongest tier this machine offers that can be written.
     * An environment variable already naming the same credential keeps winning every read, and the
     * caller learns that by asking {@link #status} afterwards.
     *
     * <p>A blank credential is refused before any tier is reached. Storing one would replace a
     * working credential with something no provider can accept, and the failure would surface later
     * as a rejected API call rather than here.
     *
     * <p>What is stored is the credential stripped of surrounding whitespace, so a caller that
     * reads it back gets that rather than what it passed. A key pasted out of a browser or a
     * terminal carries whatever came with it, and a provider counts that as part of the key.
     *
     * <p>Nothing is cleared until the write succeeds, so a failed save can never lose a credential
     * already stored. Once it does, every writable tier that outranks the one just written to is
     * cleared. An unavailable tier can otherwise leave a stale value behind for a later session to
     * rediscover. An environment variable is never touched by a save, matching {@link #remove}.
     *
     * @param id {@link SecretId} which credential to store
     * @param secret {@link String} the credential to store
     * @throws IllegalArgumentException when the credential is blank
     * @throws SecretStoreException when no tier on this machine can store one, when the tier that
     *         took it refused, or when the value was stored but a stale copy above it could not be
     *         cleared
     */
    void save(SecretId id, String secret);

    /**
     * Clears the given credential from every tier that can hold one. An environment variable is
     * left alone, so a credential named by one still answers afterwards.
     *
     * @param id {@link SecretId} which credential to clear
     * @throws SecretStoreException when a tier holding a value could not clear it
     */
    void remove(SecretId id);
}
