package photos.sluice.application.port.out;

import java.util.List;
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
     * What every place holds for the given id, in the order a read consults them, carrying no
     * credential.
     *
     * <p>{@link #status} answers which place wins. This answers all of them, which is what lets a
     * screen say a fresh credential is shadowed by an older one somewhere above it. Reading it
     * back and comparing is the only way to know they differ, and no surface here is ever handed a
     * value to compare.
     *
     * <p>A place that refuses the question is reported as such rather than raised, because one
     * broken place would otherwise hide every healthy one's answer. That is the machine this exists
     * for, and it is why this can be called beside a {@link #status} that threw.
     *
     * <p>What it does not swallow is a place that cannot say what it is. Every entry names a real
     * place, which is what lets a caller count an unaskable one rather than leave it out silently.
     *
     * @param id {@link SecretId} which credential to report on
     * @return a {@link List} of {@link SecretHolding} one entry per place, in read order
     */
    List<SecretHolding> holdings(SecretId id);

    /**
     * Where {@link #save} would put a credential on this machine right now, or empty when no place
     * can take one.
     *
     * <p>For the sentence shown beside an entry field, before anything is typed. {@link #status}
     * cannot answer it: that reports where a credential already is, and says nothing about the
     * keyring when the answer is {@link SecretStatus.Absent}.
     *
     * <p>Takes no id, because nothing about the routing depends on one. It is decided by which
     * places this machine offers.
     *
     * @return an {@link Optional} of {@link SecretStatus.StoredLocation} where a save would land
     */
    Optional<SecretStatus.StoredLocation> whereASaveWouldStoreIt();

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
     * asked to clear what it holds. An environment variable is never touched by a save, matching
     * {@link #remove}.
     *
     * <p>That clearing is best effort, and its limit is worth knowing. A tier only outranks the one
     * written to when it could not be written to itself, which on this machine means it could not be
     * reached. A store that cannot be reached cannot be cleared either, and reports nothing. So the
     * case where a stale value survives a save is exactly the case this cannot fix, and the save
     * reports success. What settles which value is really in force is {@link #status}.
     *
     * @param id {@link SecretId} which credential to store
     * @param secret {@link String} the credential to store
     * @throws IllegalArgumentException when the credential is blank
     * @throws StaleSecretNotClearedException when the value was stored and a stale copy above it
     *         could not be cleared, so that copy may still answer a read
     * @throws SecretStoreException when no tier on this machine can store one, or when the tier
     *         that took it refused
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
